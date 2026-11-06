package com.ledger.repository;

import com.ledger.entity.OutboxEvent;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
class OutboxEventRepositoryImpl implements OutboxEventRepository {

    /** MySQL's error for {@code NOWAIT} finding the row locked. */
    private static final int MYSQL_LOCK_NOWAIT = 3572;

    private final JdbcClient jdbcClient;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void insert(OutboxEvent event) {
        jdbcClient.sql("""
                        INSERT INTO outbox_events
                            (event_id, event_type, topic, message_key, payload, request_id, created_at)
                        VALUES (:eventId, :eventType, :topic, :messageKey, :payload, :requestId, :createdAt)""")
                .param("eventId", event.eventId())
                .param("eventType", event.eventType())
                .param("topic", event.topic())
                .param("messageKey", event.messageKey())
                .param("payload", event.payload())
                .param("requestId", event.requestId())
                .param("createdAt", Timestamp.from(event.createdAt()))
                .update();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean tryLockRelay() {
        try {
            jdbcClient.sql("SELECT id FROM outbox_relay_lock WHERE id = 1 FOR UPDATE NOWAIT").query(Integer.class).single();
            return true;
        } catch (DataAccessException e) {
            if (e.getMostSpecificCause() instanceof SQLException sql && sql.getErrorCode() == MYSQL_LOCK_NOWAIT) {
                return false;
            }
            throw e;
        }
    }

    @Override
    public List<OutboxEvent> findUnpublished(int limit) {
        return jdbcClient.sql("""
                        SELECT id, event_id, event_type, topic, message_key, payload, request_id, created_at, published_at
                        FROM outbox_events WHERE published_at IS NULL ORDER BY id LIMIT :limit""")
                .param("limit", limit)
                .query((rs, n) -> toOutboxEvent(rs))
                .list();
    }

    @Override
    public void markPublished(List<Long> ids) {
        if (ids.isEmpty()) {
            return;
        }
        jdbcClient.sql("UPDATE outbox_events SET published_at = CURRENT_TIMESTAMP(6) WHERE id IN (:ids)")
                .param("ids", ids)
                .update();
    }

    private static OutboxEvent toOutboxEvent(ResultSet rs) throws SQLException {
        return new OutboxEvent(rs.getLong("id"), rs.getString("event_id"), rs.getString("event_type"),
                rs.getString("topic"), rs.getString("message_key"), rs.getString("payload"),
                rs.getString("request_id"), toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("published_at")));
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
