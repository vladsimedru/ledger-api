package com.ledger.model;

import java.util.List;

/**
 * One page of an account's entries, newest first.
 *
 * @param nextCursor the id of the last entry on this page; {@code null} on the last page
 */
public record EntryPage(List<AccountEntryView> items, Long nextCursor) {
}
