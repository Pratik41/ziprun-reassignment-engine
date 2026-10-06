package com.ziprun.domain;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.function.Predicate;

/**
 * Readable ids like ORD-3F9A1C07B2E4: 12 hex digits (48 random bits).
 *
 * 8 digits (32 bits) would reach a 50% chance of some collision after ~77,000 ids; 48 bits
 * pushes that past 16 million, and each new id is also checked against the table, so a
 * clash picks another id instead of failing the insert.
 */
public final class Ids {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int BYTES = 6;
    private static final int MAX_ATTEMPTS = 5;

    private Ids() {
    }

    public static String next(String prefix, Predicate<String> exists) {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            byte[] bytes = new byte[BYTES];
            RANDOM.nextBytes(bytes);
            String id = prefix + "-" + HexFormat.of().withUpperCase().formatHex(bytes);
            if (!exists.test(id)) {
                return id;
            }
        }
        throw new IllegalStateException("Could not find a free " + prefix + " id");
    }
}
