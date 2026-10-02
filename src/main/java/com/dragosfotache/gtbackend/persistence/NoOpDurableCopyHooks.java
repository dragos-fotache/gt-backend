package com.dragosfotache.gtbackend.persistence;

import org.springframework.stereotype.Component;

/**
 * Inert production durable-copy hooks. No property, endpoint, storage,
 * logging, decision flag or readiness effect.
 */
@Component
public class NoOpDurableCopyHooks implements DurableCopyHooks {
}
