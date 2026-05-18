ALTER TABLE usage_events
  ADD COLUMN billable_prompt_tokens BIGINT NOT NULL DEFAULT 0,
  ADD COLUMN billable_completion_tokens BIGINT NOT NULL DEFAULT 0,
  ADD COLUMN estimated_cost_usd NUMERIC(20, 8) NOT NULL DEFAULT 0,
  ADD COLUMN pricing_rule_id UUID,
  ADD COLUMN billing_status TEXT NOT NULL DEFAULT 'DRAFT',
  ADD COLUMN billed_at TIMESTAMPTZ;

CREATE TABLE model_pricing (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  provider TEXT NOT NULL,
  model_pattern TEXT NOT NULL,
  currency TEXT NOT NULL DEFAULT 'USD',
  prompt_price_per_1m_tokens NUMERIC(20, 8) NOT NULL,
  completion_price_per_1m_tokens NUMERIC(20, 8) NOT NULL,
  status TEXT NOT NULL DEFAULT 'ACTIVE',
  effective_from TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_model_pricing_provider_status_effective
  ON model_pricing(provider, status, effective_from DESC);

CREATE TABLE monthly_bills (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  bill_month DATE NOT NULL,
  user_id UUID NOT NULL REFERENCES gateway_users(id) ON DELETE CASCADE,
  currency TEXT NOT NULL DEFAULT 'USD',
  status TEXT NOT NULL DEFAULT 'DRAFT',
  total_requests BIGINT NOT NULL DEFAULT 0,
  prompt_tokens BIGINT NOT NULL DEFAULT 0,
  completion_tokens BIGINT NOT NULL DEFAULT 0,
  total_tokens BIGINT NOT NULL DEFAULT 0,
  total_cost_usd NUMERIC(20, 8) NOT NULL DEFAULT 0,
  note TEXT,
  sent_at TIMESTAMPTZ,
  paid_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (bill_month, user_id)
);

CREATE INDEX idx_monthly_bills_month_status ON monthly_bills(bill_month, status);

CREATE TABLE user_billing_policies (
  user_id UUID PRIMARY KEY REFERENCES gateway_users(id) ON DELETE CASCADE,
  currency TEXT NOT NULL DEFAULT 'USD',
  monthly_budget_usd NUMERIC(20, 8) NOT NULL DEFAULT 0,
  alert_threshold_percent NUMERIC(5, 2) NOT NULL DEFAULT 80,
  auto_disable_api_keys BOOLEAN NOT NULL DEFAULT FALSE,
  webhook_url TEXT,
  status TEXT NOT NULL DEFAULT 'ACTIVE',
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_usage_events_user_created ON usage_events(user_id, created_at DESC);
CREATE INDEX idx_usage_events_billing_status ON usage_events(billing_status, created_at);

INSERT INTO model_pricing (provider, model_pattern, currency, prompt_price_per_1m_tokens, completion_price_per_1m_tokens, status)
VALUES
  ('OPENAI', 'gpt-4o-mini', 'USD', 0.15000000, 0.60000000, 'ACTIVE'),
  ('OPENAI', 'gpt-4o', 'USD', 2.50000000, 10.00000000, 'ACTIVE'),
  ('ANTHROPIC', 'claude-3-5-sonnet*', 'USD', 3.00000000, 15.00000000, 'ACTIVE'),
  ('ANTHROPIC', 'claude-3-haiku*', 'USD', 0.25000000, 1.25000000, 'ACTIVE'),
  ('GEMINI', 'gemini-2.5-flash*', 'USD', 0.15000000, 0.60000000, 'ACTIVE');

INSERT INTO user_billing_policies (user_id, currency, monthly_budget_usd, alert_threshold_percent, auto_disable_api_keys, status)
SELECT id, 'USD', 0, 80, FALSE, 'ACTIVE'
FROM gateway_users
ON CONFLICT (user_id) DO NOTHING;
