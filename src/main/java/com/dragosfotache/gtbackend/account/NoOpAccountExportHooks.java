package com.dragosfotache.gtbackend.account;

import org.springframework.stereotype.Component;

/**
 * Production hooks: every gate is a no-op. Tests coordinate through real HTTP, spied
 * {@code JdbcTemplate} gates, raw SQL fixtures, and restarted processes instead.
 */
@Component
public class NoOpAccountExportHooks implements AccountExportHooks {
}
