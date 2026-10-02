package com.dragosfotache.gtbackend.account;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Test-only deterministic gates for deletion acceptance.
 *
 * <p>Inert by default. Gates never perform product writes. Blocking is brief and never holds row locks
 * across slow streams, so no artificial circular wait is introduced.
 */
@Component
@Primary
public class TestAccountDeletionHooks implements AccountDeletionHooks {
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    public TestAccountDeletionHooks(org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }
    private final AtomicReference<CountDownLatch> scopedKeyEntered = new AtomicReference<>(new CountDownLatch(1));
    private final AtomicReference<CountDownLatch> scopedKeyRelease = new AtomicReference<>(new CountDownLatch(1));
    private final AtomicReference<Boolean> scopedKeyArmed = new AtomicReference<>(false);
    private final AtomicReference<RuntimeException> beforeCommitFault = new AtomicReference<>();
    private final AtomicReference<CountDownLatch> beforeCommitEntered = new AtomicReference<>(new CountDownLatch(0));
    private final AtomicReference<CountDownLatch> finalChecksEntered = new AtomicReference<>(new CountDownLatch(1));
    private final AtomicReference<CountDownLatch> finalChecksRelease = new AtomicReference<>(new CountDownLatch(1));
    private final AtomicReference<Boolean> finalChecksArmed = new AtomicReference<>(false);

    public void armScopedKeyGate() {
        scopedKeyArmed.set(true);
        scopedKeyEntered.set(new CountDownLatch(1));
        scopedKeyRelease.set(new CountDownLatch(1));
    }

    public boolean awaitScopedKeyEntered() throws InterruptedException {
        return scopedKeyEntered.get().await(15, TimeUnit.SECONDS);
    }

    public void releaseScopedKey() {
        scopedKeyRelease.get().countDown();
    }

    public void armBeforeCommitFault(RuntimeException fault) {
        beforeCommitEntered.set(new CountDownLatch(1));
        beforeCommitFault.set(fault);
    }

    public boolean awaitBeforeCommitEntered() throws InterruptedException {
        return beforeCommitEntered.get().await(15, TimeUnit.SECONDS);
    }

    public void armFinalChecksGate() {
        finalChecksArmed.set(true);
        finalChecksEntered.set(new CountDownLatch(1));
        finalChecksRelease.set(new CountDownLatch(1));
    }

    public boolean awaitFinalChecksEntered() throws InterruptedException {
        return finalChecksEntered.get().await(15, TimeUnit.SECONDS);
    }

    public void releaseFinalChecks() {
        finalChecksRelease.get().countDown();
    }

    private final java.util.concurrent.ConcurrentMap<String, Integer> markerPids =
            new java.util.concurrent.ConcurrentHashMap<>();

    public Integer markerPid(String marker) {
        return markerPids.get(marker);
    }

    private void capturePid(String marker) {
        try {
            Integer pid = jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
            if (pid != null) {
                markerPids.put(marker, pid);
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    public void afterScopedKeyLock() {
        if (!scopedKeyArmed.get()) {
            return;
        }
        scopedKeyEntered.get().countDown();
        try {
            if (!scopedKeyRelease.get().await(15, TimeUnit.SECONDS)) {
                throw new IllegalStateException("deletion scoped-key gate timeout");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("deletion scoped-key gate interrupted", e);
        } finally {
            scopedKeyArmed.set(false);
        }
    }

    @Override
    public void afterFinalChecks() {
        if (!finalChecksArmed.get()) {
            return;
        }
        capturePid("finalChecks");
        finalChecksEntered.get().countDown();
        try {
            if (!finalChecksRelease.get().await(15, TimeUnit.SECONDS)) {
                throw new IllegalStateException("deletion final-checks gate timeout");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("deletion final-checks gate interrupted", e);
        } finally {
            finalChecksArmed.set(false);
        }
    }

    @Override
    public void beforeCommit() {
        beforeCommitEntered.get().countDown();
        RuntimeException fault = beforeCommitFault.getAndSet(null);
        if (fault != null) {
            throw fault;
        }
    }
}
