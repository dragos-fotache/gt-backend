package com.dragosfotache.gtbackend.account;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** HTTP validation and safe status mapping for direct JSON account export. No SQL here. */
@RestController
public class AccountExportController {
    private static final Pattern UUID_TEXT =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Set<String> FIELDS = Set.of("accountId", "challengeId", "idToken");
    private static final JsonFactory FACTORY = JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .disable(JsonReadFeature.ALLOW_NON_NUMERIC_NUMBERS).build();
    private static final ObjectMapper JSON = new ObjectMapper(FACTORY);

    private final AccountExportService exports;

    public AccountExportController(AccountExportService exports) {
        this.exports = exports;
    }

    @PostMapping(value = "/v1/account/export", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<StreamingResponseBody> export(@RequestBody(required = false) byte[] body,
                                                       HttpServletRequest request,
                                                       HttpServletResponse response) {
        AccountExportService.Proof proof = parse(body, request);
        StreamingResponseBody stream = out -> exports.stream(proof, response, out);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .cacheControl(CacheControl.noStore())
                .header("Content-Disposition", "attachment; filename=\"gymtracka-export.json\"")
                .body(stream);
    }

    @ExceptionHandler(ExportHttpException.class)
    ResponseEntity<?> rejected(ExportHttpException e) {
        return ResponseEntity.status(e.status).cacheControl(CacheControl.noStore())
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("errorCode", e.code));
    }

    /**
     * Marks a post-byte export abort handled without writing any body. The
     * controller-local handler takes precedence over global error advice for this
     * exact type, so no error envelope is appended to the truncated stream. The
     * void return with response participation completes the async error dispatch
     * with no view rendering; the committed bytes stay a failed partial download.
     */
    @ExceptionHandler(AccountExportService.ExportStreamAborted.class)
    void streamAborted(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
    }

    private AccountExportService.Proof parse(byte[] body, HttpServletRequest request) {
        if (body == null || body.length == 0) throw new ExportHttpException(400, "invalid_request");
        if (request.getQueryString() != null && !request.getQueryString().isEmpty())
            throw new ExportHttpException(400, "invalid_request");
        ObjectNode json = parseBody(body);
        for (var names = json.fieldNames(); names.hasNext();) {
            if (!FIELDS.contains(names.next())) throw new ExportHttpException(422, "validation_error");
        }
        JsonNode account = json.get("accountId");
        if (account == null || !account.isTextual() || !isCanonicalUuid(account.textValue()))
            throw new ExportHttpException(422, "validation_error");
        JsonNode challenge = json.get("challengeId");
        if (challenge == null || !challenge.isTextual() || challenge.textValue().isBlank()
                || !isCanonicalUuid(challenge.textValue()))
            throw new ExportHttpException(401, "invalid_google_credential");
        JsonNode token = json.get("idToken");
        if (token == null || !token.isTextual() || token.textValue().isBlank())
            throw new ExportHttpException(401, "invalid_google_credential");
        return new AccountExportService.Proof(
                UUID.fromString(account.textValue()), UUID.fromString(challenge.textValue()),
                token.textValue());
    }

    private static ObjectNode parseBody(byte[] bytes) {
        try {
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes));
            try (JsonParser parser = FACTORY.createParser(bytes)) {
                JsonNode node = JSON.readTree(parser);
                if (node == null || parser.nextToken() != null) throw new ExportHttpException(400, "invalid_request");
                if (!(node instanceof ObjectNode object)) throw new ExportHttpException(400, "invalid_request");
                return object;
            }
        } catch (ExportHttpException e) {
            throw e;
        } catch (Exception e) {
            throw new ExportHttpException(400, "invalid_request");
        }
    }

    private static boolean isCanonicalUuid(String value) {
        if (!UUID_TEXT.matcher(value).matches()) return false;
        try {
            return UUID.fromString(value).toString().equals(value);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    static final class ExportHttpException extends RuntimeException {
        final int status;
        final String code;

        ExportHttpException(int status, String code) {
            super(code);
            this.status = status;
            this.code = code;
        }
    }
}
