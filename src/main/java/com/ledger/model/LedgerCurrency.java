package com.ledger.model;

import com.ledger.exception.ErrorCode;
import com.ledger.exception.LedgerRuleException;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.regex.Pattern;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** The currencies the ledger supports, with their ISO 4217 decimal places. */
@RequiredArgsConstructor
@Getter
public enum LedgerCurrency {
    EUR(2),
    USD(2),
    JPY(0);

    private static final Pattern ALPHA_CODE = Pattern.compile("[A-Z]{3}");

    private final int decimalPlaces;

    /**
     * Resolves an ISO 4217 alphabetic code (upper case, as ISO writes it).
     *
     * @throws LedgerRuleException INVALID_CURRENCY if it isn't an ISO 4217 code,
     *                             UNSUPPORTED_CURRENCY if it is, but the ledger doesn't hold it
     */
    public static LedgerCurrency of(String code, String pointer) {
        if (code == null || !ALPHA_CODE.matcher(code).matches() || !isIso4217(code)) {
            throw new LedgerRuleException(pointer, ErrorCode.INVALID_CURRENCY,
                    "'" + code + "' is not an ISO 4217 currency code");
        }
        for (LedgerCurrency c : values()) {
            if (c.name().equals(code)) {
                return c;
            }
        }
        throw new LedgerRuleException(pointer, ErrorCode.UNSUPPORTED_CURRENCY,
                code + " is a valid ISO 4217 code, but the ledger supports only EUR, USD and JPY");
    }

    private static boolean isIso4217(String code) {
        try {
            Currency.getInstance(code);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** Whether the amount has no more decimal places than this currency allows. Trailing zeros don't count. */
    public boolean fits(BigDecimal amount) {
        return amount.stripTrailingZeros().scale() <= decimalPlaces;
    }

    public String precisionRule() {
        return decimalPlaces == 0
                ? name() + " has no decimal places"
                : name() + " allows at most " + decimalPlaces + " decimal places";
    }
}
