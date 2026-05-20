ALTER TABLE gateway_users
  ADD COLUMN password_hash TEXT,
  ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT now();

CREATE TABLE workspaces (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  name TEXT NOT NULL,
  slug TEXT NOT NULL UNIQUE,
  status TEXT NOT NULL DEFAULT 'ACTIVE',
  created_by_user_id UUID NOT NULL REFERENCES gateway_users(id) ON DELETE RESTRICT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE workspace_memberships (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  workspace_id UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
  user_id UUID NOT NULL REFERENCES gateway_users(id) ON DELETE CASCADE,
  role TEXT NOT NULL,
  status TEXT NOT NULL DEFAULT 'ACTIVE',
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (workspace_id, user_id)
);

CREATE INDEX idx_workspace_memberships_user_status
  ON workspace_memberships(user_id, status);

CREATE INDEX idx_workspace_memberships_workspace_status
  ON workspace_memberships(workspace_id, status);

ALTER TABLE api_keys
  ADD COLUMN workspace_id UUID REFERENCES workspaces(id) ON DELETE CASCADE;

CREATE TABLE workspace_model_configs (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  workspace_id UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
  provider TEXT NOT NULL,
  model_pattern TEXT NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  max_tokens INT,
  status TEXT NOT NULL DEFAULT 'ACTIVE',
  created_by_user_id UUID NOT NULL REFERENCES gateway_users(id) ON DELETE RESTRICT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (workspace_id, provider, model_pattern)
);

CREATE INDEX idx_workspace_model_configs_workspace_status
  ON workspace_model_configs(workspace_id, status);

INSERT INTO workspaces (name, slug, status, created_by_user_id)
SELECT
  u.display_name || ' Workspace',
  'default-' || replace(u.id::text, '-', ''),
  'ACTIVE',
  u.id
FROM gateway_users u
ON CONFLICT (slug) DO NOTHING;

INSERT INTO workspace_memberships (workspace_id, user_id, role, status)
SELECT
  w.id,
  u.id,
  'OWNER',
  'ACTIVE'
FROM gateway_users u
JOIN workspaces w
  ON w.created_by_user_id = u.id
 AND w.slug = 'default-' || replace(u.id::text, '-', '')
ON CONFLICT (workspace_id, user_id) DO NOTHING;

UPDATE api_keys a
SET workspace_id = w.id
FROM workspaces w
WHERE a.workspace_id IS NULL
  AND w.created_by_user_id = a.user_id
  AND w.slug = 'default-' || replace(a.user_id::text, '-', '');

ALTER TABLE api_keys
  ALTER COLUMN workspace_id SET NOT NULL;

CREATE INDEX idx_api_keys_workspace_status
  ON api_keys(workspace_id, status);
