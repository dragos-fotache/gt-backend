package com.dragosfotache.gtbackend.auth;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs one bounded cleanup batch; subsequent ticks drain backlogs.
 */
@Component
public class AuthCleanupScheduler {
    private final AuthService authService;

    public AuthCleanupScheduler(AuthService authService) {
        this.authService = authService;
    }

    @Scheduled(fixedDelayString = "${auth.cleanup.fixed-delay-ms:60000}", initialDelayString = "${auth.cleanup.initial-delay-ms:60000}")
    public void cleanup() {
        authService.cleanupExpired();
    }
}
