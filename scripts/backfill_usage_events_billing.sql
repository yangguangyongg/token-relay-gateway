BEGIN;

-- 1) Backfill billable tokens from existing usage columns for historical rows.
UPDATE usage_events ue
SET
  billable_prompt_tokens = CASE
    WHEN ue.billable_prompt_tokens > 0 THEN ue.billable_prompt_tokens
    WHEN ue.prompt_tokens > 0 THEN ue.prompt_tokens
    WHEN ue.total_tokens > ue.completion_tokens THEN ue.total_tokens - ue.completion_tokens
    WHEN ue.total_tokens > 0 AND ue.completion_tokens = 0 THEN ue.total_tokens
    ELSE 0
  END,
  billable_completion_tokens = CASE
    WHEN ue.billable_completion_tokens > 0 THEN ue.billable_completion_tokens
    WHEN ue.completion_tokens > 0 THEN ue.completion_tokens
    ELSE 0
  END
WHERE ue.total_tokens > 0
  AND ue.billable_prompt_tokens = 0
  AND ue.billable_completion_tokens = 0;

-- 2) Ensure billing status is initialized.
UPDATE usage_events
SET billing_status = 'DRAFT'
WHERE billing_status IS NULL OR btrim(billing_status) = '';

-- 3) Match historical usage rows to active pricing rules and backfill estimated cost.
WITH candidate_pricing AS (
  SELECT
    ue.id AS usage_event_id,
    mp.id AS pricing_rule_id,
    mp.prompt_price_per_1m_tokens AS prompt_price_per_1m_tokens,
    mp.completion_price_per_1m_tokens AS completion_price_per_1m_tokens,
    ROW_NUMBER() OVER (
      PARTITION BY ue.id
      ORDER BY
        CASE
          WHEN upper(mp.model_pattern) = upper(coalesce(ue.model, '')) THEN 3000 + char_length(mp.model_pattern)
          WHEN right(mp.model_pattern, 1) = '*'
            AND upper(coalesce(ue.model, '')) LIKE upper(left(mp.model_pattern, char_length(mp.model_pattern) - 1)) || '%' THEN 2000 + char_length(mp.model_pattern)
          WHEN mp.model_pattern = '*' THEN 1000
          ELSE 0
        END DESC,
        mp.effective_from DESC
    ) AS rn
  FROM usage_events ue
  JOIN model_pricing mp
    ON mp.status = 'ACTIVE'
    AND (
      upper(mp.provider) = upper(coalesce(ue.provider, ''))
      OR upper(mp.provider) IN ('*', 'ANY')
    )
    AND (
      upper(mp.model_pattern) = upper(coalesce(ue.model, ''))
      OR (
        right(mp.model_pattern, 1) = '*'
        AND upper(coalesce(ue.model, '')) LIKE upper(left(mp.model_pattern, char_length(mp.model_pattern) - 1)) || '%'
      )
      OR mp.model_pattern = '*'
    )
  WHERE ue.total_tokens > 0
    AND (ue.pricing_rule_id IS NULL OR ue.estimated_cost_usd = 0)
),
selected_pricing AS (
  SELECT
    usage_event_id,
    pricing_rule_id,
    prompt_price_per_1m_tokens,
    completion_price_per_1m_tokens
  FROM candidate_pricing
  WHERE rn = 1
)
UPDATE usage_events ue
SET
  pricing_rule_id = sp.pricing_rule_id,
  estimated_cost_usd = round(
    (
      (sp.prompt_price_per_1m_tokens * ue.billable_prompt_tokens::numeric / 1000000.0) +
      (sp.completion_price_per_1m_tokens * ue.billable_completion_tokens::numeric / 1000000.0)
    ),
    8
  )
FROM selected_pricing sp
WHERE ue.id = sp.usage_event_id
  AND ue.total_tokens > 0
  AND (
    ue.pricing_rule_id IS DISTINCT FROM sp.pricing_rule_id
    OR ue.estimated_cost_usd = 0
  );

COMMIT;

SELECT
  count(*) AS total_usage_rows,
  count(*) FILTER (WHERE total_tokens > 0) AS non_zero_usage_rows,
  count(*) FILTER (
    WHERE total_tokens > 0
      AND (billable_prompt_tokens > 0 OR billable_completion_tokens > 0)
  ) AS billable_backfilled_rows,
  count(*) FILTER (WHERE total_tokens > 0 AND pricing_rule_id IS NOT NULL) AS matched_pricing_rows,
  count(*) FILTER (WHERE total_tokens > 0 AND estimated_cost_usd > 0) AS cost_backfilled_rows
FROM usage_events;
