package com.dragosfotache.gtbackend.account;

import com.dragosfotache.gtbackend.auth.GoogleTokenVerifier;
import org.springframework.dao.TransientDataAccessException;

/**
 * Shared non-signup Google verification boundary for lifecycle routes.
 * Malformed JWT is a credential rejection; provider transport failure is
 * retryable; genuinely unexpected faults stay internal. No account is
 * provisioned and no grant is minted here. Transaction retryability is a
 * separate persistence concern answered by {@link #isRetryableTransactionFault};
 * provider mapping below only classifies transport faults around the Google call.
 */
public final class LifecycleGoogle {
    private LifecycleGoogle() {
    }

    public interface ProofFailure {
        RuntimeException failure(int status, String code);
    }

    public static String verify(GoogleTokenVerifier google, String token, String nonce,
                                ProofFailure failures) {
        try {
            return google.verify(token, nonce).subject();
        } catch (GoogleTokenVerifier.InvalidGoogleToken e) {
            throw failures.failure(401, "invalid_google_credential");
        } catch (GoogleTokenVerifier.ProviderUnavailable e) {
            throw failures.failure(503, "temporarily_unavailable");
        } catch (RuntimeException e) {
            throw failures.failure(isTransient(e) ? 503 : 500,
                    isTransient(e) ? "temporarily_unavailable" : "internal_error");
        }
    }

    public static boolean isTransient(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.sql.SQLTransientException) return true;
            if (cause instanceof java.sql.SQLException sql && sql.getSQLState() != null
                    && (sql.getSQLState().startsWith("08") || sql.getSQLState().startsWith("40")
                            || "40001".equals(sql.getSQLState())))
                return true;
            if (cause instanceof TransientDataAccessException) return true;
        }
        return false;
    }

    /**
     * Retryable transaction completion versus programming and configuration
     * faults. Only explicit acquisition, timeout and in-doubt completion
     * failures are retryable. Definitive control, programming and rollback-only
     * states stay unexpected. Callers must consult
     * {@link #hasDefinitiveTransactionFault} over the entire chain first: an
     * outer completion wrapper never masks an inner definitive cause, and a
     * nested transient cause never overrides one either.
     */
    public static boolean isRetryableTransactionFault(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof org.springframework.transaction.CannotCreateTransactionException
                    || cause instanceof org.springframework.transaction.TransactionTimedOutException
                    || cause instanceof org.springframework.transaction.TransactionSystemException)
                return true;
        }
        return false;
    }

    /**
     * Definitive transaction control, programming and rollback-only faults
     * anywhere in the cause chain. These stay unexpected even when wrapped
     * by, or wrapping, a transient or completion failure.
     */
    public static boolean hasDefinitiveTransactionFault(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof org.springframework.transaction.UnexpectedRollbackException
                    || cause instanceof org.springframework.transaction.InvalidIsolationLevelException
                    || cause instanceof org.springframework.transaction.InvalidTimeoutException
                    || cause instanceof org.springframework.transaction.IllegalTransactionStateException
                    || cause instanceof org.springframework.transaction.NoTransactionException
                    || cause instanceof org.springframework.transaction.NestedTransactionNotSupportedException
                    || cause instanceof org.springframework.transaction.TransactionUsageException
                    || cause instanceof org.springframework.transaction.TransactionSuspensionNotSupportedException)
                return true;
        }
        return false;
    }

    public static boolean isRetryablePersistenceFault(Throwable failure) {
        if (hasDefinitiveTransactionFault(failure)) return false;
        return isTransient(failure) || isRetryableTransactionFault(failure);
    }
}
