package com.dragosfotache.gtbackend.account;

import org.springframework.stereotype.Component;

/**
 * Production 9b readiness: the physical purger is absent, so ordinary new
 * acceptance is always unavailable. Identical receipt recovery and status do
 * not consult this gate for already accepted rows.
 */
@Component
public class AlwaysUnavailablePurgeReadiness implements PurgeReadiness {
    @Override
    public boolean isPurgeCapable() {
        return false;
    }
}
