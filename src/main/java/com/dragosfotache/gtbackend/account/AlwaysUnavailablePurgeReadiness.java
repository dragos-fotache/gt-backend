package com.dragosfotache.gtbackend.account;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Production 9b readiness: the physical purger is absent, so ordinary new
 * acceptance is always unavailable. Identical receipt recovery and status do
 * not consult this gate for already accepted rows. Active by default and
 * whenever {@code account.deletion.purge.enabled} is not explicitly true;
 * enabling the verified purger activates the config-backed bean instead.
 */
@Component
@ConditionalOnProperty(prefix = "account.deletion.purge", name = "enabled",
        havingValue = "false", matchIfMissing = true)
public class AlwaysUnavailablePurgeReadiness implements PurgeReadiness {
    @Override
    public boolean isPurgeCapable() {
        return false;
    }
}
