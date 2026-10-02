package com.dragosfotache.gtbackend.account;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.UUID;

import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

/**
 * Test-owned purge invocation seam for the dedicated 9c suites.
 *
 * <p>All 9c suites invoke production purge only through this driver. Oracles stay
 * independent: they assert contract-defined database and HTTP behaviour, never
 * production mapping or observed responses.
 *
 * <p>Seam assumption: the Worker provides a Spring bean holding the production
 * purge entry point. The bean and method names below are the QA proposal; if the
 * approved Worker design names them differently, only this file is realigned in
 * the explicit verification window and no suite file changes. Resolution is
 * reflection-only so these suites compile before the production purge exists.
 * A missing entry point fails loudly instead of silently passing.
 */
@Component
public class AccountPurgeTestDriver {
    private static final String[] PURGE_BEAN_CANDIDATES = {
        "accountPurgeService", "accountPurger", "purgeService"
    };
    private static final String[] PURGE_ONCE_CANDIDATES = {
        "purgeOnce", "purge", "purgeAccount"
    };
    private static final String[] PURGE_DUE_CANDIDATES = {
        "purgeDue", "purgeDueAccounts", "runOnce", "run"
    };
    private static final String[] PURGE_TYPE_CANDIDATES = {
        "com.dragosfotache.gtbackend.account.AccountPurgeService",
        "com.dragosfotache.gtbackend.account.AccountPurger"
    };

    private final ApplicationContext context;

    public AccountPurgeTestDriver(ApplicationContext context) {
        this.context = context;
    }

    /**
     * Runs production purge once for one old account and returns when that
     * invocation finishes. Purge progress is durable; repeated invocations are
     * safe and must not regress completed work.
     */
    public void purgeOnce(UUID oldAccountId) {
        Object target = resolvePurgeBean();
        invokeSingleUuid(target, PURGE_ONCE_CANDIDATES, oldAccountId);
    }

    /**
     * Runs the production scheduled purge path once over due intents and
     * returns when that invocation finishes. An empty backlog is a safe no-op.
     */
    public void purgeDue() {
        Object target = resolvePurgeBean();
        invokeNoArg(target, PURGE_DUE_CANDIDATES);
    }

    private Object resolvePurgeBean() {
        String override = System.getProperty("account.purge.bean");
        if (override != null && !override.isBlank()) {
            if (context.containsBean(override)) {
                return context.getBean(override);
            }
            throw new AssertionError("9c seam: account.purge.bean names a missing bean: " + override);
        }
        for (String name : PURGE_BEAN_CANDIDATES) {
            if (context.containsBean(name)) {
                return context.getBean(name);
            }
        }
        for (String typeName : PURGE_TYPE_CANDIDATES) {
            try {
                Class<?> type = Class.forName(typeName);
                String[] names = context.getBeanNamesForType(type);
                if (names.length > 0) {
                    return context.getBean(names[0]);
                }
            } catch (ClassNotFoundException expectedBeforeWorkerDelivery) {
                // Try the next candidate; failure below names the seam explicitly.
            }
        }
        throw new AssertionError(
                "9c seam: no production purge bean found. Worker must provide the approved "
                        + "callable purge entry point before the verification window.");
    }

    private void invokeSingleUuid(Object target, String[] candidates, UUID accountId) {
        String override = System.getProperty("account.purge.method");
        if (override != null && !override.isBlank()) {
            invoke(target, override, new Class<?>[] {UUID.class}, new Object[] {accountId});
            return;
        }
        AssertionError missing = new AssertionError(
                "9c seam: production purge bean " + target.getClass().getName()
                        + " has no single-UUID purge method. Worker must provide it.");
        for (String name : candidates) {
            try {
                invoke(target, name, new Class<?>[] {UUID.class}, new Object[] {accountId});
                return;
            } catch (AssertionError notThisOne) {
                missing.addSuppressed(notThisOne);
            }
        }
        throw missing;
    }

    private void invokeNoArg(Object target, String[] candidates) {
        AssertionError missing = new AssertionError(
                "9c seam: production purge bean " + target.getClass().getName()
                        + " has no no-arg scheduled purge method. Worker must provide it.");
        for (String name : candidates) {
            try {
                invoke(target, name, new Class<?>[0], new Object[0]);
                return;
            } catch (AssertionError notThisOne) {
                missing.addSuppressed(notThisOne);
            }
        }
        throw missing;
    }

    private void invoke(Object target, String method, Class<?>[] params, Object[] args) {
        Method found;
        try {
            found = target.getClass().getMethod(method, params);
        } catch (NoSuchMethodException e) {
            throw new AssertionError("9c seam: method " + method + " not found on "
                    + target.getClass().getName(), e);
        }
        try {
            found.invoke(target, args);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new AssertionError("9c seam: purge invocation failed: " + method, cause);
        } catch (IllegalAccessException e) {
            throw new AssertionError("9c seam: purge method not accessible: " + method, e);
        }
    }
}
