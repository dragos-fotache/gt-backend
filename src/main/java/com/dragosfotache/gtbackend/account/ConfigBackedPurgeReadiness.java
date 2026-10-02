package com.dragosfotache.gtbackend.account;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Config-backed purge readiness. Active only when
 * {@code account.deletion.purge.enabled} is explicitly true; the production
 * default stays false so ordinary deletion remains fail-closed until the
 * section 11 release conditions are verified and the flag is deliberately
 * enabled. Test-classpath replacements keep working in isolation.
 */
@Component
@ConditionalOnProperty(prefix = "account.deletion.purge", name = "enabled",
        havingValue = "true", matchIfMissing = false)
public class ConfigBackedPurgeReadiness implements PurgeReadiness {
    private final boolean enabled;

    public ConfigBackedPurgeReadiness(
            @Value("${account.deletion.purge.enabled:false}") boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public boolean isPurgeCapable() {
        return enabled;
    }
}
