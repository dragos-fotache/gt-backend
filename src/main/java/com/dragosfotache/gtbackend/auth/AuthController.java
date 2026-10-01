package com.dragosfotache.gtbackend.auth;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/v1/auth")
public class AuthController {
    private final AuthService service;
    private final ObjectMapper mapper;

    public AuthController(AuthService service, ObjectMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    @PostMapping(value = "/google/challenge", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> challenge(@RequestBody byte[] body) {
        JsonNode j = parse(body);
        strict(j, Set.of("purpose"));
        JsonNode p = j.get("purpose");
        if (p != null && !p.isTextual()) throw new AuthService.AuthException(422, "validation_error");
        return noStore(service.challenge(p == null ? "signIn" : p.asText()));
    }

    @PostMapping(value = "/google", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> exchange(@RequestHeader("Idempotency-Key") UUID key, @RequestBody byte[] body) {
        JsonNode j = parse(body);
        strict(j, Set.of("challengeId", "idToken"));
        return raw(service.exchange(key, challengeId(j), text(j, "idToken", "invalid_google_credential"), body));
    }

    @PostMapping(value = "/refresh", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> refresh(@RequestHeader("Idempotency-Key") UUID key, @RequestBody byte[] body) {
        JsonNode j = parse(body);
        strict(j, Set.of("refreshToken"));
        return raw(service.refresh(key, text(j, "refreshToken", "reauthentication_required"), body));
    }

    @PostMapping(value = "/logout", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> logout(@RequestBody byte[] body) {
        JsonNode j = parse(body);
        strict(j, Set.of("refreshToken"));
        service.logout(text(j, "refreshToken", "invalid_request"));
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    private JsonNode parse(byte[] body) {
        try {
            mapper.getFactory().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            JsonNode j = mapper.readTree(body);
            if (j == null || !j.isObject()) throw new IllegalArgumentException();
            return j;
        } catch (Exception e) {
            throw new AuthService.AuthException(400, "invalid_request");
        }
    }

    private void strict(JsonNode j, Set<String> allowed) {
        j.fieldNames().forEachRemaining(n -> {
            if (!allowed.contains(n)) throw new AuthService.AuthException(422, "validation_error");
        });
    }

    private String text(JsonNode j, String field, String error) {
        JsonNode n = j.get(field);
        if (n == null || !n.isTextual() || n.asText().isBlank())
            throw new AuthService.AuthException(error.equals("invalid_request") ? 400 : 401, error);
        return n.asText();
    }

    private UUID challengeId(JsonNode j) {
        String raw = text(j, "challengeId", "invalid_google_credential");
        try {
            UUID id = UUID.fromString(raw);
            if (!id.toString().equals(raw)) throw new IllegalArgumentException();
            return id;
        } catch (Exception e) {
            throw new AuthService.AuthException(401, "invalid_google_credential");
        }
    }

    private ResponseEntity<byte[]> raw(byte[] b) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.APPLICATION_JSON).body(b);
    }

    private ResponseEntity<?> noStore(Object body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
