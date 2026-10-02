package com.dragosfotache.gtbackend.account;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Test-classpath-only purge readiness replacement for isolated 9b acceptance tests.
 *
 * <p>Production {@code PurgeReadiness} remains unconditionally unavailable with no
 * runtime or request bypass. This replacement is present only under {@code src/test}
 * and is explicitly installed by isolated acceptance suites. Receipt recovery and
 * status remain independent of the gate.
 *
 * <p>Seam assumption: Worker provides
 * {@code com.dragosfotache.gtbackend.account.PurgeReadiness} with a single
 * {@code isPurgeCapable()} query. If the production type name differs, this file
 * will be aligned in the explicit verification window; no production file is touched
 * from QA.
 */
@TestConfiguration
public class TestPurgeReadinessReplacement {
    @Bean
    @Primary
    PurgeReadiness testCapablePurgeReadiness() {
        return () -> true;
    }
}
