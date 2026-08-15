package com.example.spring_app.config;

import io.micrometer.registry.otlp.OtlpConfig;
import io.micrometer.registry.otlp.OtlpMeterRegistry;
import io.micrometer.core.instrument.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import org.springframework.beans.factory.annotation.Value;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Configuration
public class TelemetryDataConfig {

    @Bean
    public OtlpConfig otlpConfig(
            @Value("${spring.application.name}") String appName,
            @Value("${POD_NAME:unknown}") String podName,
            @Value("${POD_IP:unknown}") String podIp) {

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
                return Duration.ofSeconds(30);
            }

            @Override
            public Map<String, String> resourceAttributes() {
                Map<String, String> attributes = new HashMap<>();
                attributes.put("service.name", appName);
                attributes.put("k8s.pod.name", podName);
                attributes.put("k8s.pod.ip", podIp);
                return attributes;
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