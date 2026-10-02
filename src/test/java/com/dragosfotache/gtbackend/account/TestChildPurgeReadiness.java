package com.dragosfotache.gtbackend.account;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Test-classpath-only purge readiness for child service processes.
 *
 * <p>Active only when {@code task9b.child-purge-capable} is true. Production remains
 * unconditionally unavailable; this replacement exists only on the test classpath for
 * isolated child acceptance. Receipt recovery and status stay independent of the gate.
 */
@Component
@ConditionalOnProperty(name = "task9b.child-purge-capable", havingValue = "true")
@Primary
public class TestChildPurgeReadiness implements PurgeReadiness {
    @Override
    public boolean isPurgeCapable() {
        return true;
    }
}
