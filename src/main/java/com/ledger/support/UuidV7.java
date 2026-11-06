package com.ledger.support;

import java.security.SecureRandom;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * RFC 9562 version 7 UUIDs: a millisecond timestamp followed by random bits. Time-ordered, so new
 * rows land at the end of InnoDB's primary-key index instead of splitting pages at random.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class UuidV7 {

    private static final SecureRandom RANDOM = new SecureRandom();

    public static UUID next() {
        long millis = System.currentTimeMillis();
        long msb = (millis << 16) | 0x7000L | (RANDOM.nextInt() & 0x0FFFL);
        long lsb = (RANDOM.nextLong() & 0x3FFF_FFFF_FFFF_FFFFL) | 0x8000_0000_0000_0000L;
        return new UUID(msb, lsb);
    }
}
