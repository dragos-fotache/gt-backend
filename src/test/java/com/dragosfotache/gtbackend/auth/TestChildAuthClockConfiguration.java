package com.dragosfotache.gtbackend.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

/**
 * Test-classpath-only fixed Clock for child service processes in auth precision
 * restart tests.
 *
 * <p>Active only when {@code auth.qa.child-clock} is set to an instant string. Only
 * test-owned child processes set this property via their own command line; ordinary
 * tests never set it and production never sets it, so neither is affected.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "auth.qa.child-clock")
public class TestChildAuthClockConfiguration {
    @Bean
    @Primary
    Clock childAuthClock(@Value("${auth.qa.child-clock}") String instant) {
        return Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);
    }
}
