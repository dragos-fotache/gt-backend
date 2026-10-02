package com.dragosfotache.gtbackend.account;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Set;

/** Strict HTTP parsing for authenticated account status. No SQL here. */
@RestController
public class AccountStatusController {
    private static final Set<String> FIELDS = Set.of("accountId", "challengeId", "idToken");

    private final AccountStatusService statuses;

    public AccountStatusController(AccountStatusService statuses) {
        this.statuses = statuses;
    }

    @PostMapping(value = "/v1/account/status", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> status(@RequestBody(required = false) byte[] body,
                                    HttpServletRequest request) {
        AccountLifecycleParse.Failures failures = new AccountLifecycleParse.Failures() {
            @Override
            public RuntimeException invalidRequest() {
                return new AccountStatusService.StatusException(400, "invalid_request");
            }

            @Override
            public RuntimeException validationError() {
                return new AccountStatusService.StatusException(422, "validation_error");
            }

            @Override
            public RuntimeException credential() {
                return new AccountStatusService.StatusException(401, "invalid_google_credential");
            }
        };
        var json = AccountLifecycleParse.body(body, request, FIELDS, failures);
        var proof = AccountLifecycleParse.proof(json, failures);
        Map<String, Object> result = statuses.status(proof.accountId(), proof.challengeId(), proof.idToken());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .contentType(MediaType.APPLICATION_JSON).body(result);
    }

    @ExceptionHandler(AccountStatusService.StatusException.class)
    ResponseEntity<?> rejected(AccountStatusService.StatusException e) {
        return ResponseEntity.status(e.status).cacheControl(CacheControl.noStore())
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("errorCode", e.code));
    }
}
