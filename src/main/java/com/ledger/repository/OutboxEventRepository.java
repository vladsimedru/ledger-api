package com.ledger.repository;

import com.ledger.entity.OutboxEvent;
import java.util.List;

/** The transactional outbox. Events are only ever inserted and then marked published. */
public interface OutboxEventRepository {

    /** Stores the event. Only call inside the database transaction that makes the change it announces. */
    void insert(OutboxEvent event);

    /**
     * Takes the relay lock for the rest of the current database transaction, without waiting.
     *
     * @return {@code false} if another instance holds it (and is relaying right now)
     */
    boolean tryLockRelay();

    /** The oldest events not yet published, in the order they were stored. */
    List<OutboxEvent> findUnpublished(int limit);

    void markPublished(List<Long> ids);
}
