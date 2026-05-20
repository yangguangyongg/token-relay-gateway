ALTER TABLE workspaces
  ADD COLUMN type TEXT NOT NULL DEFAULT 'ORGANIZATION';

UPDATE workspaces
SET type = 'PERSONAL'
WHERE slug LIKE 'default-%';

UPDATE workspaces w
SET type = 'PERSONAL'
WHERE type <> 'PERSONAL'
  AND lower(w.name) = (
    SELECT lower(u.display_name || ' workspace')
    FROM gateway_users u
    WHERE u.id = w.created_by_user_id
  )
  AND (
    SELECT count(*)
    FROM workspace_memberships wm
    WHERE wm.workspace_id = w.id
      AND wm.status = 'ACTIVE'
  ) = 1
  AND EXISTS (
    SELECT 1
    FROM workspace_memberships wm
    WHERE wm.workspace_id = w.id
      AND wm.user_id = w.created_by_user_id
      AND upper(wm.role) = 'OWNER'
      AND wm.status = 'ACTIVE'
  );

CREATE INDEX idx_workspaces_type_status
  ON workspaces(type, status);
