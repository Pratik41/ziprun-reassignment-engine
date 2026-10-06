package com.ziprun.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Refuses to start outside the dev/test profiles with settings that are only safe on a laptop.
 *
 * Local runs and tests use the "dev" profile (spring.profiles.default), where the defaults
 * (ops / ziprun, an H2 console) are convenient. Docker Compose runs "prod", and there a
 * forgotten default should stop the app with a clear message, not ship quietly.
 */
@Component
public class ProductionSafetyCheck {
    private static final Logger log = LoggerFactory.getLogger(ProductionSafetyCheck.class);

    static final int MIN_PASSWORD_LENGTH = 10;

    public ProductionSafetyCheck(Environment env,
                                 @Value("${security.enabled:true}") boolean securityEnabled,
                                 @Value("${ops.password:" + SecurityConfig.DEFAULT_PASSWORD + "}") String password,
                                 @Value("${spring.h2.console.enabled:false}") boolean h2Console) {
        if (env.acceptsProfiles(Profiles.of("dev", "test"))) {
            return;
        }
        List<String> problems = new ArrayList<>();
        if (!securityEnabled) {
            problems.add("security.enabled=false (sign-in off) is only allowed in the dev/test profiles");
        }
        if (SecurityConfig.DEFAULT_PASSWORD.equals(password) || password.length() < MIN_PASSWORD_LENGTH) {
            problems.add("OPS_PASSWORD is the default or shorter than " + MIN_PASSWORD_LENGTH + " characters");
        }
        if (h2Console) {
            problems.add("the H2 console (H2_CONSOLE_ENABLED) must stay off outside dev");
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Refusing to start with profile(s) " + String.join(",", env.getActiveProfiles())
                + ": " + String.join("; ", problems));
        }
        log.info("Production safety check passed");
    }
}
