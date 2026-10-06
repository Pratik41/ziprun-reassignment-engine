package com.ziprun.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductionSafetyCheckTest {

    private static MockEnvironment profile(String name) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(name);
        return env;
    }

    @Test
    void devAndTestAcceptLaptopDefaults() {
        assertThatCode(() -> new ProductionSafetyCheck(profile("dev"), false, "ziprun", true)).doesNotThrowAnyException();
        assertThatCode(() -> new ProductionSafetyCheck(profile("test"), false, "ziprun", true)).doesNotThrowAnyException();
    }

    @Test
    void prodRefusesTheDefaultOrAShortPassword() {
        assertThatThrownBy(() -> new ProductionSafetyCheck(profile("prod"), true, "ziprun", false))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("OPS_PASSWORD");
        assertThatThrownBy(() -> new ProductionSafetyCheck(profile("prod"), true, "short", false))
            .hasMessageContaining("shorter than 10");
    }

    @Test
    void prodRefusesSignInOffAndTheH2Console() {
        assertThatThrownBy(() -> new ProductionSafetyCheck(profile("prod"), false, "a-long-enough-password", true))
            .hasMessageContaining("security.enabled=false")
            .hasMessageContaining("H2 console");
    }

    @Test
    void prodStartsWithProperSettings() {
        assertThatCode(() -> new ProductionSafetyCheck(profile("prod"), true, "a-long-enough-password", false))
            .doesNotThrowAnyException();
    }
}
