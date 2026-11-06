package com.ledger.domain.model;

import java.util.UUID;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * The ledger's own settlement accounts, one per currency, created by the V2 migration. Their ids
 * end in the currency's ISO 4217 numeric code. They have no overdraft limit.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class SettlementAccounts {

    public static UUID of(LedgerCurrency currency) {
        return switch (currency) {
            case EUR -> UUID.fromString("00000000-0000-0000-0000-000000000978");
            case USD -> UUID.fromString("00000000-0000-0000-0000-000000000840");
            case JPY -> UUID.fromString("00000000-0000-0000-0000-000000000392");
        };
    }
}
