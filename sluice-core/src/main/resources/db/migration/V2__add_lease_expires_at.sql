ALTER TABLE jobs
    ADD COLUMN lease_expires_at TIMESTAMPTZ;

CREATE INDEX idx_jobs_claimed_lease_expiry ON jobs (lease_expires_at)
    WHERE status = 'claimed';