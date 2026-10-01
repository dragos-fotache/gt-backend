package com.dragosfotache.gtbackend.account;

import com.dragosfotache.gtbackend.auth.LifecycleProofService;
import com.dragosfotache.gtbackend.domain.CanonicalAggregates.ExerciseValue;
import com.dragosfotache.gtbackend.persistence.CanonicalAggregateRepository;
import com.dragosfotache.gtbackend.persistence.SyncSnapshotRepository;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLTransientException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Consistent direct JSON export streaming orchestrator. Proof verification, binding
 * authorization, and challenge consumption belong to {@link LifecycleProofService};
 * this service owns the writer-thread read snapshot, gated segment writes, and the
 * pre-byte versus post-byte response handling. No account is provisioned, no grant
 * is minted, and no export state is stored.
 */
@Service
public class AccountExportService {
    static final int SEGMENT_BYTES = 8192;
    private static final int ROOT_ID_CHUNK = 100;
    private static final String PINNED_CATALOGUE_VERSION = "gymtracka-catalogue-v1";

    private final JdbcTemplate bound;
    private final DataSource raw;
    private final TransactionTemplate streaming;
    private final LifecycleProofService proofs;
    private final CanonicalAggregateRepository aggregates;
    private final SyncSnapshotRepository snapshots;
    private final Clock clock;
    private final AccountExportHooks hooks;

    public AccountExportService(JdbcTemplate jdbc, DataSource dataSource,
                                PlatformTransactionManager transactionManager,
                                LifecycleProofService proofs,
                                CanonicalAggregateRepository aggregates,
                                SyncSnapshotRepository snapshots,
                                Clock clock, AccountExportHooks hooks) {
        if (dataSource instanceof TransactionAwareDataSourceProxy)
            throw new IllegalStateException("Export requires the raw pooled DataSource");
        this.bound = jdbc;
        this.raw = dataSource;
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setReadOnly(true);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.streaming = template;
        this.proofs = proofs;
        this.aggregates = aggregates;
        this.snapshots = snapshots;
        this.clock = clock;
        this.hooks = hooks;
    }

    public record Proof(UUID accountId, UUID challengeId, String idToken) {
    }

    /**
     * Streams the complete version-1 export. All asynchronous pre-output work runs
     * under response tracking: before the first byte, failures carry a sanitized
     * status; after bytes begin, failures abort the stream without appending an
     * error envelope and without finishing partial JSON.
     */
    public void stream(Proof proof, HttpServletResponse response, OutputStream out) throws IOException {
        Gate gate = new Gate(out, () -> checkLive(proof.accountId()));
        try {
            LifecycleProofService.ChallengeScreen preliminary =
                    proofs.preliminaryExportScreen(proof.challengeId());
            String subject = proofs.verifyGoogle(proof.idToken(), preliminary.nonce());
            LifecycleProofService.VerifiedProof verified = new LifecycleProofService.VerifiedProof(
                    proof.accountId(), proof.challengeId(), subject, preliminary.nonce());
            streaming.execute(status -> {
                runExport(verified, gate);
                return null;
            });
        } catch (LifecycleProofService.ProofException failure) {
            if (!gate.bytesAttempted() && !response.isCommitted()) {
                writePreByteError(response, failure.status, failure.code);
                return;
            }
            throw new ExportStreamAborted();
        } catch (ExportCancelled cancelled) {
            if (!gate.bytesAttempted() && !response.isCommitted()) {
                writePreByteError(response, 404, "not_found");
                return;
            }
            throw new ExportStreamAborted();
        } catch (ExportUnavailable unavailable) {
            if (!gate.bytesAttempted() && !response.isCommitted()) {
                writePreByteError(response, 503, "temporarily_unavailable");
                return;
            }
            throw new ExportStreamAborted();
        } catch (ExportFault fault) {
            if (!gate.bytesAttempted() && !response.isCommitted()) {
                writePreByteError(response, 500, "internal_error");
                return;
            }
            throw new ExportStreamAborted();
        } catch (ExportAbort abort) {
            throw new ExportStreamAborted();
        } catch (RuntimeException unexpected) {
            if (!gate.bytesAttempted() && !response.isCommitted()) {
                writePreByteError(response, isTransient(unexpected) ? 503 : 500,
                        isTransient(unexpected) ? "temporarily_unavailable" : "internal_error");
                return;
            }
            throw new ExportStreamAborted();
        }
    }

    private void runExport(LifecycleProofService.VerifiedProof verified, Gate gate) {
        LifecycleProofService.ChallengeScreen screen = proofs.snapshotScreen(verified.challengeId());
        if (!verified.nonce().equals(screen.nonce()) || !"export".equals(screen.purpose())
                || screen.consumed() || !clock.instant().isBefore(screen.expires()))
            throw new LifecycleProofService.ProofException(401, "invalid_google_credential");
        hooks.afterSnapshotEstablished();
        proofs.authorizeSnapshot(verified.accountId(), verified.subject());
        String catalogue = selectCatalogueVersion();
        Instant exportedAt = clock.instant();
        proofs.consume(verified);
        hooks.afterConsumptionCommitted();
        gate.checkLive();
        hooks.beforeFirstByte();
        writeExport(verified.accountId(), gate, catalogue, exportedAt);
        gate.flushChecked();
    }

    private void writeExport(UUID owner, Gate gate, String catalogue, Instant exportedAt) {
        gate.writeToken("{\"exportVersion\":1,\"accountId\":\"" + owner + "\",\"exportedAt\":\""
                + exportedAt + "\",\"catalogueVersion\":" + jsonString(catalogue)
                + ",\"customExercises\":[");
        writeRoots(gate, owner, SyncSnapshotRepository.RootType.CUSTOM_EXERCISE);
        gate.writeToken("],\"routines\":[");
        writeRoots(gate, owner, SyncSnapshotRepository.RootType.ROUTINE);
        gate.writeToken("],\"workouts\":[");
        writeRoots(gate, owner, SyncSnapshotRepository.RootType.WORKOUT);
        hooks.beforeFinalClosure();
        gate.writeToken("]}");
    }

    private void writeRoots(Gate gate, UUID owner, SyncSnapshotRepository.RootType type) {
        UUID afterId = null;
        boolean first = true;
        int elementIndex = 0;
        while (true) {
            List<UUID> ids = snapshots.nextRootIds(owner, type, afterId, ROOT_ID_CHUNK);
            if (ids.isEmpty()) return;
            for (UUID id : ids) {
                hooks.beforeSegment(elementIndex++);
                String record = switch (type) {
                    case CUSTOM_EXERCISE -> {
                        ExerciseValue value = aggregates.readVisibleExercise(owner, id);
                        yield AccountExportMapper.customExercise(value);
                    }
                    case ROUTINE -> AccountExportMapper.routine(aggregates.readRoutine(owner, id));
                    case WORKOUT -> AccountExportMapper.workout(aggregates.readWorkout(owner, id));
                };
                if (!first) gate.writeToken(",");
                first = false;
                gate.writeElement(record);
                afterId = id;
            }
        }
    }

    private String selectCatalogueVersion() {
        List<String> versions = bound.query("SELECT version FROM catalogue_versions",
                (rs, row) -> rs.getString(1));
        if (versions.size() != 1 || !PINNED_CATALOGUE_VERSION.equals(versions.get(0)))
            throw new IllegalStateException("export catalogue fault");
        return versions.get(0);
    }

    private void checkLive(UUID id) {
        try (Connection connection = raw.getConnection();
             PreparedStatement query = connection.prepareStatement(
                     "SELECT deleted_at FROM accounts WHERE id=?")) {
            query.setObject(1, id);
            try (ResultSet rs = query.executeQuery()) {
                if (!rs.next() || rs.getTimestamp(1) != null) throw new ExportCancelled();
            }
        } catch (SQLException e) {
            if (e instanceof SQLTransientException) throw new ExportUnavailable();
            String state = e.getSQLState();
            if (state != null && (state.startsWith("08") || state.startsWith("40")))
                throw new ExportUnavailable();
            throw new ExportFault();
        }
    }

    private void writePreByteError(HttpServletResponse response, int status, String code) {
        try {
            response.reset();
            response.setStatus(status);
            response.setContentType("application/json");
            response.setHeader("Cache-Control", "no-store");
            byte[] body = ("{\"errorCode\":\"" + code + "\"}").getBytes(StandardCharsets.UTF_8);
            response.getOutputStream().write(body);
            response.getOutputStream().flush();
        } catch (IOException | IllegalStateException e) {
            throw new ExportStreamAborted();
        }
    }

    private static boolean isTransient(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLTransientException) return true;
            if (cause instanceof SQLException sql && sql.getSQLState() != null
                    && (sql.getSQLState().startsWith("08") || sql.getSQLState().startsWith("40")))
                return true;
            if (cause instanceof TransientDataAccessException) return true;
        }
        return false;
    }

    private static String jsonString(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' || c == '\\') out.append('\\');
            out.append(c);
        }
        return out.append('"').toString();
    }

    /**
     * Outward gate: every segment of at most {@link #SEGMENT_BYTES} passes a
     * current-view live check first, as do the first output and the final closure.
     * The attempted flag is set before each write attempt, because an
     * {@code IOException} may already have released bytes. No failure path flushes
     * pending bytes or completes partial JSON; the only flush is the guarded
     * success flush after the complete envelope is written.
     */
    private final class Gate {
        private final OutputStream out;
        private final Runnable check;
        private boolean attempted;
        private long segmentOrdinal;

        private Gate(OutputStream out, Runnable check) {
            this.out = out;
            this.check = check;
        }

        private boolean bytesAttempted() {
            return attempted;
        }

        private void checkLive() {
            check.run();
        }

        private void writeToken(String ascii) {
            nextSegment();
            checkLive();
            writeBytes(ascii.getBytes(StandardCharsets.UTF_8));
        }

        private void writeElement(String json) {
            StringBuilder segment = new StringBuilder();
            int bytes = 0;
            for (int i = 0; i < json.length();) {
                int point = json.codePointAt(i);
                int size = point < 0x80 ? 1 : point < 0x800 ? 2 : point < 0x10000 ? 3 : 4;
                if (bytes + size > SEGMENT_BYTES && bytes > 0) {
                    nextSegment();
                    checkLive();
                    writeBytes(segment.toString().getBytes(StandardCharsets.UTF_8));
                    segment.setLength(0);
                    bytes = 0;
                }
                segment.appendCodePoint(point);
                bytes += size;
                i += Character.charCount(point);
            }
            if (segment.length() > 0 || !attempted) {
                nextSegment();
                checkLive();
                writeBytes(segment.toString().getBytes(StandardCharsets.UTF_8));
            }
        }

        private void nextSegment() {
            hooks.beforeByteSegment(segmentOrdinal++);
        }

        private void flushChecked() {
            hooks.beforeSuccessFlush();
            checkLive();
            try {
                out.flush();
            } catch (IOException e) {
                throw new ExportAbort();
            }
        }

        private void writeBytes(byte[] bytes) {
            attempted = true;
            try {
                out.write(bytes);
            } catch (IOException e) {
                throw new ExportAbort();
            }
        }
    }

    static final class ExportCancelled extends RuntimeException {
    }

    static final class ExportUnavailable extends RuntimeException {
    }

    static final class ExportFault extends RuntimeException {
    }

    static final class ExportAbort extends RuntimeException {
    }

    /**
     * Sanitized post-byte abort marker and the only exception permitted to escape
     * the streaming callback. It carries no SQL, token, payload, or cause, and a
     * controller-local no-body handler marks the async dispatch handled so the
     * global error advice never appends a body to the truncated stream.
     */
    static final class ExportStreamAborted extends RuntimeException {
    }
}
