package com.ledger.mapper;

import com.ledger.model.LedgerCurrency;
import com.ledger.model.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.mapstruct.Context;
import org.mapstruct.Mapper;
import org.mapstruct.Named;

/**
 * Conversions shared by every mapper. Amounts leave the service as plain decimal strings ("125.50",
 * "15000"), never JSON numbers, and never through a float.
 *
 * <p>Stored amounts are whole minor units, and a {@code long} means nothing without its currency, so
 * there is deliberately no automatic {@code long -> String} conversion: each amount is mapped
 * explicitly with {@code qualifiedByName = "amount"} and the currency passed as {@link Context}.
 */
@Mapper
public interface CommonMapper {

    @Named("amount")
    default String toAmount(long minorUnits, @Context LedgerCurrency currency) {
        return new Money(minorUnits, currency).toPlainString();
    }

    default String toAmount(Money money) {
        return money == null ? null : money.toPlainString();
    }

    /** Amounts in requests are validated against the contract's pattern before they get here. */
    default BigDecimal toBigDecimal(String amount) {
        return amount == null ? null : new BigDecimal(amount);
    }

    /** Times are given in UTC. */
    default OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}
