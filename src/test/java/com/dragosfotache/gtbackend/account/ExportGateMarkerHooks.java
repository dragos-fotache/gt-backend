package com.dragosfotache.gtbackend.account;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Test-only deterministic gate for the restarted-process export test. Active only when
 * the {@code task9a.export-gate.dir} property is set (a JVM argument of a test-owned
 * child service process); inert in every other context, including all parent test
 * contexts and production, which never sets the property and never sees this class.
 *
 * <p>After the durable consumption commit and before the first output byte, it records
 * a coordination marker and blocks until the test releases it or a fail-safe timeout
 * passes. The marker carries no account, token, challenge, or payload data — only the
 * fact that the gate was reached. The streaming transaction holds no row locks at this
 * gate, so blocking here cannot deadlock account writers.
 */
@Component
@ConditionalOnProperty(name = "task9a.export-gate.dir")
@Primary
public class ExportGateMarkerHooks implements AccountExportHooks {
    private final Path dir;

    public ExportGateMarkerHooks(@Value("${task9a.export-gate.dir}") String dir) {
        this.dir = Path.of(dir);
    }

    @Override
    public void afterConsumptionCommitted() {
        try {
            Files.writeString(dir.resolve("after-consumption"), "reached", StandardCharsets.UTF_8);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
            while (System.nanoTime() < deadline) {
                if (Files.exists(dir.resolve("release"))) {
                    return;
                }
                Thread.sleep(100);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            throw new IllegalStateException("task9a-test-gate-fault", e);
        }
    }
}
