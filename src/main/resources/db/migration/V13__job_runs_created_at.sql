-- OLV-120 follow-up: align job_runs table with JobRun entity audit field.

ALTER TABLE job_runs
    ADD COLUMN created_at TIMESTAMPTZ NOT NULL DEFAULT now();
