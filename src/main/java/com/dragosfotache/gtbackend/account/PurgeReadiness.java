package com.dragosfotache.gtbackend.account;

/**
 * Purge-capability readiness for deletion acceptance.
 * Production reports unconditionally unavailable in 9b. Isolated tests may
 * replace this bean on the test classpath only. No runtime or request field
 * may turn the absent purger into readiness. Receipt recovery and status stay
 * independent of this gate.
 */
public interface PurgeReadiness {
    boolean isPurgeCapable();
}
