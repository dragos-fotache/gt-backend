package com.dragosfotache.gtbackend.account;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Deterministic receipt encoding from persisted normalized ledger columns.
 * Pinned field order and UTC timestamp encoding keep first responses and
 * replays byte-identical. No raw body or credential is stored.
 */
public final class DeletionReceipts {
    private DeletionReceipts() {
    }

    public static Map<String, Object> deleted(UUID accountId, Instant deletedAt, Instant purgeBy) {
        Map<String, Object> receipt = new LinkedHashMap<>();
        receipt.put("accountId", accountId.toString());
        receipt.put("state", "deleted");
        receipt.put("deletedAt", deletedAt.toString());
        receipt.put("purgeBy", purgeBy.toString());
        return receipt;
    }

    public static Map<String, Object> active(UUID accountId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("accountId", accountId.toString());
        body.put("state", "active");
        return body;
    }

    public static Map<String, Object> fromLedger(AccountDeletionLedgerRepository.LedgerRow row) {
        return deleted(row.oldAccountId(), row.deletedAt(), row.purgeBy());
    }
}
