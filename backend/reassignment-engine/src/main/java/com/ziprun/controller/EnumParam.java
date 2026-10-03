package com.ziprun.controller;

import java.util.Arrays;

/**
 * Case-insensitive enum parsing for query params and request bodies, with an
 * error message that lists the allowed values (mapped to 400 by the handler).
 */
final class EnumParam {

    private EnumParam() {
    }

    static <E extends Enum<E>> E parse(Class<E> type, String value, String fieldName) {
        try {
            return Enum.valueOf(type, value.trim().toUpperCase());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalArgumentException(String.format("Invalid %s '%s'. Allowed: %s",
                fieldName, value, Arrays.toString(type.getEnumConstants())));
        }
    }
}
