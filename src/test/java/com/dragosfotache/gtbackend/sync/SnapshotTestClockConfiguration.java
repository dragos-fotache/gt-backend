package com.dragosfotache.gtbackend.sync;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

/** Test-only Clock override for a restarted service process at a controlled expiry boundary. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "snapshot.qa", name = "fixed-clock")
public class SnapshotTestClockConfiguration {
    @Bean
    @Primary
    Clock snapshotTestClock(@Value("${snapshot.qa.fixed-clock}") String instant) {
        return Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);
    }
}
