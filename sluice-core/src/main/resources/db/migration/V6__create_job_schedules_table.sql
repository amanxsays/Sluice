CREATE TABLE job_schedules (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    cron_expression TEXT NOT NULL,
    job_type         TEXT NOT NULL,
    payload          JSONB NOT NULL,
    priority         INT NOT NULL DEFAULT 0,
    next_run_at      TIMESTAMPTZ NOT NULL,
    enabled          BOOLEAN NOT NULL DEFAULT true,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);