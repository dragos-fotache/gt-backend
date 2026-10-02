package com.dragosfotache.gtbackend.account;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Strict HTTP parsing for irreversible account deletion. No SQL here. */
@RestController
public class AccountDeletionController {
    private static final Set<String> FIELDS = Set.of("accountId", "challengeId", "idToken", "confirm");

    private final AccountDeletionService deletions;

    public AccountDeletionController(AccountDeletionService deletions) {
        this.deletions = deletions;
    }

    @PostMapping(value = "/v1/account/delete", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> delete(@RequestBody(required = false) byte[] body,
                                    @RequestHeader(value = "Idempotency-Key", required = false) String keyText,
                                    HttpServletRequest request) {
        AccountLifecycleParse.Failures failures = new AccountLifecycleParse.Failures() {
            @Override
            public RuntimeException invalidRequest() {
                return new AccountDeletionService.DeletionException(400, "invalid_request");
            }

            @Override
            public RuntimeException validationError() {
                return new AccountDeletionService.DeletionException(422, "validation_error");
            }

            @Override
            public RuntimeException credential() {
                return new AccountDeletionService.DeletionException(401, "invalid_google_credential");
            }
        };
        UUID key = AccountLifecycleParse.idempotencyKey(keyText, failures);
        var json = AccountLifecycleParse.body(body, request, FIELDS, failures);
        AccountLifecycleParse.requireConfirmedTrue(json, failures);
        var proof = AccountLifecycleParse.proof(json, failures);
        Map<String, Object> receipt =
                deletions.delete(proof.accountId(), proof.challengeId(), proof.idToken(), key, body);
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore())
                .contentType(MediaType.APPLICATION_JSON).body(receipt);
    }

    @ExceptionHandler(AccountDeletionService.DeletionException.class)
    ResponseEntity<?> rejected(AccountDeletionService.DeletionException e) {
        return ResponseEntity.status(e.status).cacheControl(CacheControl.noStore())
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("errorCode", e.code));
    }
}
