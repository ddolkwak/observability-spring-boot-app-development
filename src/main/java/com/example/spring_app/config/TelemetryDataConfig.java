package com.example.spring_app.config;

import io.micrometer.registry.otlp.OtlpConfig;
import io.micrometer.registry.otlp.OtlpMeterRegistry;
import io.micrometer.core.instrument.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;

import java.time.Duration;

@Configuration
public class TelemetryDataConfig {

    @Bean
    public OtlpConfig otlpConfig() {
        return new OtlpConfig() {
            @Override
            public String get(String key) {
                return null;
            }

            @Override
            public String url() {
                return "http://otel-collector-service.otel-system.svc.cluster.local:4318/v1/metrics";
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

    @Bean
    public OtlpHttpSpanExporter otlpHttpSpanExporter() {
        return OtlpHttpSpanExporter.builder()
                .setEndpoint("http://otel-collector-service.otel-system.svc.cluster.local:4318/v1/traces")
                .setTimeout(Duration.ofSeconds(5))
                .setCompression("gzip")
                .build();
    }
}