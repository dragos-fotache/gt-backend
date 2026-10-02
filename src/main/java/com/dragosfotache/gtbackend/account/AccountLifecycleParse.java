package com.dragosfotache.gtbackend.account;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;

import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Shared strict HTTP parsing for non-signup lifecycle routes.
 * Malformed UTF-8, duplicate keys, non-objects and trailing bytes are
 * transport rejections; unknown fields and domain-shape violations are
 * validation rejections; malformed credential fields keep credential mapping.
 */
public final class AccountLifecycleParse {
    private AccountLifecycleParse() {
    }

    public static final Pattern UUID_TEXT =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final JsonFactory FACTORY = JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .disable(JsonReadFeature.ALLOW_NON_NUMERIC_NUMBERS).build();
    private static final ObjectMapper JSON = new ObjectMapper(FACTORY);

    public interface Failures {
        RuntimeException invalidRequest();

        RuntimeException validationError();

        RuntimeException credential();
    }

    public record Proof(UUID accountId, UUID challengeId, String idToken) {
    }

    public static ObjectNode body(byte[] raw, HttpServletRequest request, Set<String> allowed,
                                  Failures failures) {
        if (raw == null || raw.length == 0) throw failures.invalidRequest();
        if (request.getQueryString() != null && !request.getQueryString().isEmpty())
            throw failures.invalidRequest();
        ObjectNode json = parseBody(raw, failures);
        for (var names = json.fieldNames(); names.hasNext();) {
            if (!allowed.contains(names.next())) throw failures.validationError();
        }
        return json;
    }

    public static Proof proof(ObjectNode json, Failures failures) {
        JsonNode account = json.get("accountId");
        if (account == null || !account.isTextual() || !isCanonicalUuid(account.textValue()))
            throw failures.validationError();
        JsonNode challenge = json.get("challengeId");
        if (challenge == null || !challenge.isTextual() || challenge.textValue().isBlank()
                || !isCanonicalUuid(challenge.textValue()))
            throw failures.credential();
        JsonNode token = json.get("idToken");
        if (token == null || !token.isTextual() || token.textValue().isBlank())
            throw failures.credential();
        return new Proof(UUID.fromString(account.textValue()),
                UUID.fromString(challenge.textValue()), token.textValue());
    }

    public static UUID idempotencyKey(String keyText, Failures failures) {
        if (keyText == null || keyText.isBlank() || !isCanonicalUuid(keyText))
            throw failures.invalidRequest();
        return UUID.fromString(keyText);
    }

    public static void requireConfirmedTrue(ObjectNode json, Failures failures) {
        JsonNode confirm = json.get("confirm");
        if (confirm == null || !confirm.isBoolean() || !confirm.booleanValue())
            throw failures.validationError();
    }

    private static ObjectNode parseBody(byte[] bytes, Failures failures) {
        try {
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes));
            try (JsonParser parser = FACTORY.createParser(bytes)) {
                JsonNode node = JSON.readTree(parser);
                if (node == null || parser.nextToken() != null) throw failures.invalidRequest();
                if (!(node instanceof ObjectNode object)) throw failures.invalidRequest();
                return object;
            }
        } catch (AccountDeletionService.DeletionException | AccountStatusService.StatusException e) {
            throw e;
        } catch (RuntimeException e) {
            throw failures.invalidRequest();
        } catch (Exception e) {
            throw failures.invalidRequest();
        }
    }

    public static boolean isCanonicalUuid(String value) {
        if (!UUID_TEXT.matcher(value).matches()) return false;
        try {
            return UUID.fromString(value).toString().equals(value);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
