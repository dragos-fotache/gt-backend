package com.dragosfotache.gtbackend;

import com.dragosfotache.gtbackend.catalogue.CatalogueImporter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;

@SpringBootApplication
@EnableScheduling
public class GtBackendApplication {
    static void main(String[] args) {
        SpringApplication.run(GtBackendApplication.class, args);
    }

    @Bean
    ObjectMapper authObjectMapper() {
        return new ObjectMapper();
    }

    @Bean
    Clock serviceClock() {
        return Clock.systemUTC();
    }

    @Bean
    ApplicationRunner installCatalogue(CatalogueImporter importer) {
        return args -> importer.install();
    }
}
