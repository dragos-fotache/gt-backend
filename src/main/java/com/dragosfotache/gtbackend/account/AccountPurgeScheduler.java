package com.dragosfotache.gtbackend.account;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Fixed-delay purge sweep. Claims due intents ordered by {@code purge_by} and
 * runs one bounded pass per account. Per-account faults are recorded on the
 * intent row without aborting the sweep. Deadlines are never extended.
 */
@Component
@ConditionalOnProperty(prefix = "account.deletion.purge", name = "enabled",
        havingValue = "true", matchIfMissing = false)
public class AccountPurgeScheduler {
    private static final Logger log = LoggerFactory.getLogger(AccountPurgeScheduler.class);

    private final AccountPurgeService purger;
    private final int accountBudget;

    public AccountPurgeScheduler(AccountPurgeService purger,
                                 @Value("${account.deletion.purge.account-budget:20}") int accountBudget) {
        if (accountBudget < 1) throw new IllegalStateException("Purge account budget must be positive");
        this.purger = purger;
        this.accountBudget = accountBudget;
    }

    @Scheduled(fixedDelayString = "${account.deletion.purge.fixed-delay-ms:60000}",
            initialDelayString = "${account.deletion.purge.initial-delay-ms:60000}")
    public void sweep() {
        try {
            purger.purgeDue();
        } catch (RuntimeException failure) {
            // Fixed sanitized message: no account identifier, deadline, SQL or payload.
            log.warn("Account purge sweep invocation failed; committed phases stay committed.");
        }
    }
}
