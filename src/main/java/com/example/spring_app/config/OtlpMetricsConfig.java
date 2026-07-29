package com.example.spring_app.config;

import io.micrometer.registry.otlp.OtlpConfig;
import io.micrometer.registry.otlp.OtlpMeterRegistry;
import io.micrometer.core.instrument.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class OtlpMetricsConfig {

    @Bean
    public OtlpConfig otlpConfig() {
        return new OtlpConfig() {
            @Override
            public String get(String key) {
                return null;
            }

            @Override
            public String url() {
                return "http://otel-collector.otel-system.svc.cluster.local:4318/v1/metrics";
            }

            @Override
            public Duration step() {
                return Duration.ofSeconds(15);
            }
        };
    }

    @Bean
    public OtlpMeterRegistry otlpMeterRegistry(OtlpConfig otlpConfig) {
        return new OtlpMeterRegistry(otlpConfig, Clock.SYSTEM);
    }
}