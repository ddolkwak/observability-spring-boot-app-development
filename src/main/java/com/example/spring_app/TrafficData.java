package com.example.spring_app;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

@Entity
public class TrafficData {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String message;
    private String clientIp;

    @Column(name = "test_run_id", length = 128)
    private String testRunId;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    public TrafficData() {}

    public TrafficData(String message, String clientIp) {
        this(message, clientIp, null);
    }

    public TrafficData(String message, String clientIp, String testRunId) {
        this.message = message;
        this.clientIp = clientIp;
        this.testRunId = testRunId;
    }

    public Long getId() { return id; }
    public String getMessage() { return message; }
    public String getClientIp() { return clientIp; }
    public String getTestRunId() { return testRunId; }
    public Instant getCreatedAt() { return createdAt; }
}