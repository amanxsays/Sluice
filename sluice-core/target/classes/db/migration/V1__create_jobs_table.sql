CREATE TABLE jobs (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    job_type         TEXT NOT NULL,
    payload          JSONB NOT NULL,
    idempotency_key  TEXT UNIQUE,
    status           TEXT NOT NULL DEFAULT 'pending'
                       CHECK (status IN ('pending', 'claimed', 'completed', 'failed')),
    claimed_by       TEXT,
    claimed_at       TIMESTAMPTZ,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_jobs_status_created_at ON jobs (status, created_at)
    WHERE status = 'pending';