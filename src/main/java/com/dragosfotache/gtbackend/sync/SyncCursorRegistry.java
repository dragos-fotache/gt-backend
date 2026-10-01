package com.dragosfotache.gtbackend.sync;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;

/** Shared durable account-bound opaque cursor issuance and resolution. */
@Repository
public class SyncCursorRegistry {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final JdbcTemplate jdbc;

    public SyncCursorRegistry(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public OptionalLong resolve(UUID accountId, String token) {
        List<Long> boundaries = jdbc.query("select cursor from sync_cursor_tokens where account_id=? and token=?",
                (rs, row) -> rs.getLong(1), accountId, token);
        return boundaries.isEmpty() ? OptionalLong.empty() : OptionalLong.of(boundaries.get(0));
    }

    /**
     * Returns the stable token registered for this boundary. A concurrent first registration may
     * be invisible to this transaction's repeatable-read snapshot; callers retry the whole read in
     * a fresh transaction rather than attempting an unsafe upsert or inventing another token.
     */
    public String issue(UUID accountId, long boundary) {
        if (boundary < 0) throw new IllegalArgumentException("Cursor boundary must be nonnegative");
        List<String> existing = jdbc.query("select token from sync_cursor_tokens where account_id=? and cursor=?",
                (rs, row) -> rs.getString(1), accountId, boundary);
        if (!existing.isEmpty()) return existing.get(0);

        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        List<String> inserted = jdbc.query("insert into sync_cursor_tokens(token,account_id,cursor) values(?,?,?) "
                        + "on conflict (account_id,cursor) do nothing returning token",
                (rs, row) -> rs.getString(1), token, accountId, boundary);
        if (!inserted.isEmpty()) return inserted.get(0);
        throw new ConcurrentRegistration();
    }

    /** Signals a harmless registry conflict that needs a fresh transaction snapshot. */
    public static final class ConcurrentRegistration extends RuntimeException {
    }
}
