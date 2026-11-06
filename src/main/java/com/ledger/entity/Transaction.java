package com.ledger.entity;

import com.ledger.model.LedgerCurrency;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.MappedCollection;
import org.springframework.data.relational.core.mapping.Table;

/**
 * A recorded, balanced, immutable movement of money: one row of {@code transactions} with its
 * {@code entries} rows, written together by a single insert. Never saved a second time (which in
 * Spring Data JDBC would delete and re-insert the entries); the database's triggers refuse that anyway.
 * A mistake is corrected by a reversal, see {@link com.ledger.service.PostingService}.
 *
 * @param reversesId the transaction this one reverses, or {@code null}
 * @param paymentId  the settled payment this records, or {@code null}
 * @param entries    in request order; the position is stored as {@code entry_index}
 */
@Table("transactions")
public record Transaction(@Id UUID id, String description, LedgerCurrency currency, Instant recordedAt,
                          UUID reversesId, String paymentId,
                          @MappedCollection(idColumn = "transaction_id", keyColumn = "entry_index")
                          List<Entry> entries) {

    public Transaction {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(recordedAt, "recordedAt");
        entries = List.copyOf(entries);
    }
}
