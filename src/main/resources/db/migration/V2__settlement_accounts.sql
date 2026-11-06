-- The ledger's own settlement accounts. Fixed ids (ending in the ISO 4217 numeric code)
-- so every instance and every environment agrees on them. No overdraft limit.
INSERT INTO accounts (id, currency, overdraft_limit, balance, label, created_at) VALUES
    ('00000000-0000-0000-0000-000000000978', 'EUR', NULL, 0, 'Settlement EUR', CURRENT_TIMESTAMP(6)),
    ('00000000-0000-0000-0000-000000000840', 'USD', NULL, 0, 'Settlement USD', CURRENT_TIMESTAMP(6)),
    ('00000000-0000-0000-0000-000000000392', 'JPY', NULL, 0, 'Settlement JPY', CURRENT_TIMESTAMP(6));
