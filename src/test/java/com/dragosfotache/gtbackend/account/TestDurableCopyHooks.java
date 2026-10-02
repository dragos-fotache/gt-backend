package com.dragosfotache.gtbackend.account;

import com.dragosfotache.gtbackend.persistence.DurableCopyHooks;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Test-classpath-only read-side durable-copy gates.
 *
 * <p>One selected pause stage per case with an explicit target account; other stages
 * never block. The UUID callback argument is filtered against the armed target.
 * Backend PIDs at deterministic markers are captured for pg_blocking_pids proof.
 * Gate timeouts fail, never auto-release into a claimed success. Hook
 * implementations never perform product writes.
 */
@Component
@Primary
public class TestDurableCopyHooks implements DurableCopyHooks {
    public enum PauseStage {
        BEFORE_CURSOR_FENCE, AFTER_CURSOR_SHARE, BEFORE_SNAPSHOT_FENCE, AFTER_SNAPSHOT_SHARE
    }

    private final JdbcTemplate jdbc;
    private final AtomicReference<PauseStage> selected = new AtomicReference<>(null);
    private final AtomicReference<UUID> target = new AtomicReference<>(null);
    private final AtomicReference<CountDownLatch> beforeCursor = new AtomicReference<>(new CountDownLatch(1));
    private final AtomicReference<CountDownLatch> afterCursor = new AtomicReference<>(new CountDownLatch(1));
    private final AtomicReference<CountDownLatch> beforeSnapshot = new AtomicReference<>(new CountDownLatch(1));
    private final AtomicReference<CountDownLatch> afterSnapshot = new AtomicReference<>(new CountDownLatch(1));
    private final AtomicReference<CountDownLatch> release = new AtomicReference<>(new CountDownLatch(1));
    private final ConcurrentMap<String, Integer> markerPids = new ConcurrentHashMap<>();

    public TestDurableCopyHooks(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void arm(PauseStage stage, UUID targetAccount) {
        selected.set(stage);
        target.set(targetAccount);
        beforeCursor.set(new CountDownLatch(1));
        afterCursor.set(new CountDownLatch(1));
        beforeSnapshot.set(new CountDownLatch(1));
        afterSnapshot.set(new CountDownLatch(1));
        release.set(new CountDownLatch(1));
        markerPids.clear();
    }

    /** Backwards-compatible arm without target filtering; matches any account. */
    public void arm(PauseStage stage) {
        arm(stage, null);
    }

    public void disarm() {
        selected.set(null);
        target.set(null);
        release.get().countDown();
    }

    public boolean awaitBeforeCursor() throws InterruptedException {
        return beforeCursor.get().await(30, TimeUnit.SECONDS);
    }

    public boolean awaitAfterCursor() throws InterruptedException {
        return afterCursor.get().await(30, TimeUnit.SECONDS);
    }

    public boolean awaitBeforeSnapshot() throws InterruptedException {
        return beforeSnapshot.get().await(30, TimeUnit.SECONDS);
    }

    public boolean awaitAfterSnapshot() throws InterruptedException {
        return afterSnapshot.get().await(30, TimeUnit.SECONDS);
    }

    public Integer markerPid(String marker) {
        return markerPids.get(marker);
    }

    private boolean appliesTo(UUID accountId) {
        UUID wanted = target.get();
        return wanted == null || wanted.equals(accountId);
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
    public void beforeCursorFence(UUID accountId) {
        if (PauseStage.BEFORE_CURSOR_FENCE.equals(selected.get()) && appliesTo(accountId)) {
            capturePid("beforeCursor");
            beforeCursor.get().countDown();
            awaitOrFail(release, "beforeCursorFence");
        }
    }

    @Override
    public void afterCursorFence(UUID accountId) {
        if (PauseStage.AFTER_CURSOR_SHARE.equals(selected.get()) && appliesTo(accountId)) {
            capturePid("afterCursor");
            afterCursor.get().countDown();
            awaitOrFail(release, "afterCursorFence");
        }
    }

    @Override
    public void beforeSnapshotFence(UUID accountId) {
        if (PauseStage.BEFORE_SNAPSHOT_FENCE.equals(selected.get()) && appliesTo(accountId)) {
            capturePid("beforeSnapshot");
            beforeSnapshot.get().countDown();
            awaitOrFail(release, "beforeSnapshotFence");
        }
    }

    @Override
    public void afterSnapshotFence(UUID accountId) {
        if (PauseStage.AFTER_SNAPSHOT_SHARE.equals(selected.get()) && appliesTo(accountId)) {
            capturePid("afterSnapshot");
            afterSnapshot.get().countDown();
            awaitOrFail(release, "afterSnapshotFence");
        }
    }

    private void awaitOrFail(AtomicReference<CountDownLatch> gate, String stage) {
        try {
            if (!gate.get().await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("durable-copy gate timeout at " + stage);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("durable-copy gate interrupted at " + stage, e);
        }
    }
}
