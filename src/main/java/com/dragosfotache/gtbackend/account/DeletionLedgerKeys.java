package com.dragosfotache.gtbackend.account;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Independent external versioned key ring for deletion-ledger digests.
 * Bound from {@code account.deletion.ledger-keys} (environment
 * {@code ACCOUNT_DELETION_LEDGER_KEYS}), never from the auth encryption key.
 * Absence leaves lifecycle delete/status fenced with safe 503 without breaking
 * unrelated accepted routes or startup. A present but malformed value fails
 * startup with a sanitized configuration error that echoes no secret material.
 */
@Component
public class DeletionLedgerKeys {
    private final int activeVersion;
    private final Map<Integer, byte[]> keys;
    private final boolean configured;

    public DeletionLedgerKeys(@Value("${account.deletion.ledger-keys:}") String encoded) {
        if (encoded == null || encoded.isBlank()) {
            this.activeVersion = -1;
            this.keys = Map.of();
            this.configured = false;
            return;
        }
        try {
            JsonFactory factory = JsonFactory.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .disable(JsonReadFeature.ALLOW_NON_NUMERIC_NUMBERS).build();
            ObjectMapper json = new ObjectMapper(factory);
            JsonNode root;
            try (JsonParser parser = factory.createParser(encoded)) {
                root = json.readTree(parser);
                if (root == null || parser.nextToken() != null) throw new IllegalArgumentException();
            }
            if (!(root instanceof ObjectNode object) || object.size() != 2
                    || !object.has("activeVersion") || !object.has("keys"))
                throw new IllegalArgumentException();
            JsonNode active = root.get("activeVersion");
            JsonNode map = root.get("keys");
            if (!active.isTextual() || !map.isObject() || map.isEmpty()) throw new IllegalArgumentException();
            int activeVersion = parseVersion(active.asText());
            Map<Integer, byte[]> parsed = new LinkedHashMap<>();
            var fields = map.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                int version = parseVersion(entry.getKey());
                JsonNode value = entry.getValue();
                if (!value.isTextual()) throw new IllegalArgumentException();
                byte[] raw;
                try {
                    raw = Base64.getDecoder().decode(value.asText());
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException();
                }
                if (raw.length != 32) throw new IllegalArgumentException();
                if (parsed.put(version, raw.clone()) != null) throw new IllegalArgumentException();
            }
            if (!parsed.containsKey(activeVersion)) throw new IllegalArgumentException();
            this.activeVersion = activeVersion;
            this.keys = Collections.unmodifiableMap(parsed);
            this.configured = true;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Account deletion ledger keys are not configured correctly");
        }
    }

    private static int parseVersion(String text) {
        if (text == null || text.isEmpty() || text.length() > 5) throw new IllegalArgumentException();
        if (text.length() > 1 && text.charAt(0) == '0') throw new IllegalArgumentException();
        int value;
        try {
            value = Integer.parseInt(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException();
        }
        if (value < 1 || value > 32767) throw new IllegalArgumentException();
        if (!Integer.toString(value).equals(text)) throw new IllegalArgumentException();
        return value;
    }

    public boolean isConfigured() {
        return configured;
    }

    public int activeVersion() {
        if (!configured) throw new IllegalStateException("Deletion ledger keys are not configured");
        return activeVersion;
    }

    public byte[] keyForVersion(int version) {
        byte[] key = keys.get(version);
        if (key == null) throw new IllegalStateException("Deletion ledger key version is not configured");
        return key.clone();
    }

    public Set<Integer> configuredVersions() {
        return Collections.unmodifiableSet(new TreeSet<>(keys.keySet()));
    }
}
