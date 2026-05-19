ALTER TABLE provider_keys
  ADD COLUMN owner_user_id UUID REFERENCES gateway_users(id) ON DELETE CASCADE,
  ADD COLUMN health_status TEXT NOT NULL DEFAULT 'UNKNOWN',
  ADD COLUMN last_checked_at TIMESTAMPTZ,
  ADD COLUMN last_error TEXT,
  ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT now();

CREATE INDEX idx_provider_keys_owner_status_priority
  ON provider_keys(owner_user_id, status, priority);

CREATE INDEX idx_provider_keys_status_health
  ON provider_keys(status, health_status);
