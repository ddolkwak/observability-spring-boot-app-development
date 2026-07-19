package com.example.spring_app;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

@Entity
public class TrafficData {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String message;
    private String clientIp;

    public TrafficData() {}

    public TrafficData(String message, String clientIp) {
        this.message = message;
        this.clientIp = clientIp;
    }

    public Long getId() { return id; }
    public String getMessage() { return message; }
    public String getClientIp() { return clientIp; }
}