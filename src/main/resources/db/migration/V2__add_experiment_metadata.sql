-- V2__add_experiment_metadata.sql
-- Preserve unknown metadata of existing rows as NULL.
ALTER TABLE traffic_data
    ADD COLUMN created_at TIMESTAMP(6) WITH TIME ZONE,
    ADD COLUMN test_run_id VARCHAR(128);

-- Apply the default only to future inserts that omit created_at.
ALTER TABLE traffic_data
    ALTER COLUMN created_at SET DEFAULT CURRENT_TIMESTAMP;