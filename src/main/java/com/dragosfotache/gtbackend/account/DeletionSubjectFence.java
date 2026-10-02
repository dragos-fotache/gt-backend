package com.dragosfotache.gtbackend.account;

import com.dragosfotache.gtbackend.auth.AuthService;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Signup fence for non-replay exchanges. Under the caller's already-held
 * provider-subject advisory lock, requires every database subject version
 * globally configured before candidate lookup, then enforces a strictly
 * post-latest-deletion challenge. Empty ledger permits legacy sign-in.
 */
@Component
public class DeletionSubjectFence {
    private final AccountDeletionLedgerRepository ledger;
    private final DeletionLedgerKeys ring;
    private final DeletionLedgerCrypto crypto;

    public DeletionSubjectFence(AccountDeletionLedgerRepository ledger,
                                DeletionLedgerKeys ring,
                                DeletionLedgerCrypto crypto) {
        this.ledger = ledger;
        this.ring = ring;
        this.crypto = crypto;
    }

    public void checkNewExchangeAllowed(String subject, Instant challengeCreatedAt) {
        List<Integer> required = ledger.distinctSubjectVersions();
        if (required.isEmpty()) return;
        if (!ring.isConfigured()) throw new AuthService.AuthException(503, "temporarily_unavailable");
        for (int version : required) {
            try {
                ring.keyForVersion(version);
            } catch (IllegalStateException e) {
                throw new AuthService.AuthException(503, "temporarily_unavailable");
            }
        }
        Instant latest = null;
        for (int version : required) {
            byte[] digest = crypto.subjectDigest(ring.keyForVersion(version), version, subject);
            Instant candidate = ledger.latestDeletionForDigest(version, digest);
            if (candidate != null && (latest == null || candidate.isAfter(latest))) latest = candidate;
        }
        if (latest == null) return;
        if (challengeCreatedAt == null || !challengeCreatedAt.isAfter(latest))
            throw new AuthService.AuthException(401, "invalid_google_credential");
    }
}
