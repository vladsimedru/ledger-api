-- One row, locked (FOR UPDATE NOWAIT) by whichever instance is relaying the outbox. The others skip
-- their turn, so announcements leave in a single stream, in outbox id order.
CREATE TABLE outbox_relay_lock (
    id INT NOT NULL,
    PRIMARY KEY (id)
) ENGINE = InnoDB;

INSERT INTO outbox_relay_lock (id) VALUES (1);
