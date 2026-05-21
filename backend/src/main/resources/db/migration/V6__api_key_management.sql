ALTER TABLE api_keys
  ADD COLUMN monthly_token_quota BIGINT;

CREATE TABLE api_key_model_scopes (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  api_key_id UUID NOT NULL REFERENCES api_keys(id) ON DELETE CASCADE,
  model_pattern TEXT NOT NULL,
  status TEXT NOT NULL DEFAULT 'ACTIVE',
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (api_key_id, model_pattern)
);

CREATE INDEX idx_api_key_model_scopes_key_status
  ON api_key_model_scopes(api_key_id, status);
