-- V1__init.sql
CREATE TABLE traffic_data (
    id BIGSERIAL PRIMARY KEY,
    message VARCHAR(255),
    client_ip VARCHAR(255)
);