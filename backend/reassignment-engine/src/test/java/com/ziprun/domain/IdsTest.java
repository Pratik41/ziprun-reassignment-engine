package com.ziprun.domain;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdsTest {

    @Test
    void idsAreTwelveHexDigitsAndUnique() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            String id = Ids.next("ORD", seen::contains);
            assertThat(id).matches("ORD-[0-9A-F]{12}");
            seen.add(id);
        }
        assertThat(seen).hasSize(10_000);
    }

    @Test
    void aTakenIdIsSkipped() {
        AtomicInteger checks = new AtomicInteger();
        String id = Ids.next("SUGG", candidate -> checks.incrementAndGet() == 1); // first candidate "exists"
        assertThat(id).startsWith("SUGG-");
        assertThat(checks).hasValue(2);
    }

    @Test
    void givesUpRatherThanLoopingForever() {
        assertThatThrownBy(() -> Ids.next("ORD", candidate -> true)).isInstanceOf(IllegalStateException.class);
    }
}
