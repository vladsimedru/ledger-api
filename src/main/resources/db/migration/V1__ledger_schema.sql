-- Amounts are stored as BIGINT minor units (cents, yen): integer arithmetic is exact.

CREATE TABLE accounts (
    id              CHAR(36)     NOT NULL,
    currency        CHAR(3)      NOT NULL,
    -- NULL means no limit (the ledger's own settlement accounts)
    overdraft_limit BIGINT       NULL,
    balance         BIGINT       NOT NULL DEFAULT 0,
    label           VARCHAR(100) NULL,
    created_at      TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT ck_accounts_overdraft_non_negative CHECK (overdraft_limit IS NULL OR overdraft_limit >= 0),
    CONSTRAINT ck_accounts_within_overdraft CHECK (overdraft_limit IS NULL OR balance >= -overdraft_limit)
) ENGINE = InnoDB;

CREATE TABLE transactions (
    id          CHAR(36)     NOT NULL,
    description VARCHAR(500) NOT NULL,
    currency    CHAR(3)      NOT NULL,
    recorded_at TIMESTAMP(6) NOT NULL,
    -- set on a reversal; UNIQUE makes "reversed at most once" a database guarantee
    reverses_id CHAR(36)     NULL,
    -- set when recorded from a payment-settled message; UNIQUE deduplicates redeliveries
    payment_id  VARCHAR(100) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_transactions_reverses (reverses_id),
    UNIQUE KEY uk_transactions_payment (payment_id),
    CONSTRAINT fk_transactions_reverses FOREIGN KEY (reverses_id) REFERENCES transactions (id)
) ENGINE = InnoDB;

CREATE TABLE entries (
    -- global, monotonically increasing: orders an account's entries
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    transaction_id CHAR(36)     NOT NULL,
    entry_index    INT          NOT NULL,
    account_id     CHAR(36)     NOT NULL,
    direction      VARCHAR(6)   NOT NULL,
    amount         BIGINT       NOT NULL,
    balance_after  BIGINT       NOT NULL,
    recorded_at    TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_entries_transaction_index (transaction_id, entry_index),
    KEY ix_entries_account_id (account_id, id),
    KEY ix_entries_account_recorded (account_id, recorded_at, id),
    CONSTRAINT fk_entries_transaction FOREIGN KEY (transaction_id) REFERENCES transactions (id),
    CONSTRAINT fk_entries_account FOREIGN KEY (account_id) REFERENCES accounts (id),
    CONSTRAINT ck_entries_amount_positive CHECK (amount > 0),
    CONSTRAINT ck_entries_direction CHECK (direction IN ('DEBIT', 'CREDIT'))
) ENGINE = InnoDB;

-- Transactions and entries are append-only. The application never updates or deletes
-- them; these triggers make sure nothing else does either.
CREATE TRIGGER trg_transactions_no_update BEFORE UPDATE ON transactions FOR EACH ROW
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'transactions are immutable';

CREATE TRIGGER trg_transactions_no_delete BEFORE DELETE ON transactions FOR EACH ROW
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'transactions are immutable';

CREATE TRIGGER trg_entries_no_update BEFORE UPDATE ON entries FOR EACH ROW
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'entries are immutable';

CREATE TRIGGER trg_entries_no_delete BEFORE DELETE ON entries FOR EACH ROW
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'entries are immutable';

CREATE TABLE idempotency_keys (
    idem_key      VARCHAR(255) NOT NULL,
    -- SHA-256 of method, path and canonical body: a different request under the same key is refused
    request_hash  CHAR(64)     NOT NULL,
    status_code   INT          NULL,
    response_body MEDIUMTEXT   NULL,
    location      VARCHAR(255) NULL,
    created_at    TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (idem_key),
    KEY ix_idempotency_created (created_at)
) ENGINE = InnoDB;

-- Transactional outbox: written in the same DB transaction as the ledger change,
-- published to Kafka by a single relay in id order.
CREATE TABLE outbox_events (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    event_id     CHAR(36)     NOT NULL,
    event_type   VARCHAR(100) NOT NULL,
    topic        VARCHAR(200) NOT NULL,
    message_key  VARCHAR(200) NOT NULL,
    payload      MEDIUMTEXT   NOT NULL,
    request_id   VARCHAR(128) NULL,
    created_at   TIMESTAMP(6) NOT NULL,
    published_at TIMESTAMP(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_outbox_event (event_id),
    KEY ix_outbox_unpublished (published_at, id)
) ENGINE = InnoDB;
