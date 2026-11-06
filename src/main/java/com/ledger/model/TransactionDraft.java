package com.ledger.model;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * A transaction as requested: through the API, from a payment-settled message, or as a reversal.
 *
 * @param reversesId the transaction this one reverses, or {@code null}
 * @param paymentId  the settled payment this records, or {@code null}
 */
public record TransactionDraft(String description, List<EntryDraft> entries, UUID reversesId, String paymentId) {

    public TransactionDraft {
        Objects.requireNonNull(description, "description");
        entries = List.copyOf(entries);
    }

    public static TransactionDraft of(String description, List<EntryDraft> entries) {
        return new TransactionDraft(description, entries, null, null);
    }

    /** The accounts the entries post to, in request order, without duplicates. */
    public Set<UUID> accountIds() {
        Set<UUID> ids = new LinkedHashSet<>();
        entries.forEach(e -> ids.add(e.accountId()));
        return ids;
    }
}
