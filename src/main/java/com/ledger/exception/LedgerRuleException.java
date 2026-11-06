package com.ledger.exception;

import java.util.List;
import lombok.Getter;

/** A request refused by a ledger rule. Nothing has been recorded when this is thrown. */
@Getter
public class LedgerRuleException extends RuntimeException {

    private final List<Violation> violations;

    public LedgerRuleException(List<Violation> violations) {
        super(summary(violations));
        if (violations.isEmpty()) {
            throw new IllegalArgumentException("at least one violation is required");
        }
        this.violations = List.copyOf(violations);
    }

    public LedgerRuleException(String pointer, ErrorCode code, String detail) {
        this(List.of(new Violation(pointer, code, detail)));
    }

    /** The code of the first violation: the headline reason. */
    public ErrorCode code() {
        return violations.get(0).code();
    }

    private static String summary(List<Violation> violations) {
        return violations.isEmpty() ? "" : violations.get(0).detail()
                + (violations.size() > 1 ? " (and " + (violations.size() - 1) + " more)" : "");
    }
}
