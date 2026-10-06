package com.ziprun.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Every timestamp leaves the API with its UTC offset, e.g. "2026-10-07T14:32:10.123+05:30"
 * (or "...Z" in a UTC container).
 *
 * The entities use LocalDateTime, written with the server's clock (LocalDateTime.now()).
 * Without an offset, a browser in another time zone would read "14:32" as *its* 14:32:
 * with the backend in a UTC Docker container and ops in India, every deadline showed
 * 5½ hours off. Attaching the server's offset on the way out makes the value an exact
 * instant the browser converts to local time. Stored data is unchanged.
 */
@Configuration
public class JacksonConfig {

    private static final DateTimeFormatter ISO_WITH_OFFSET = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    @Bean
    Jackson2ObjectMapperBuilderCustomizer timestampsWithOffset() {
        SimpleModule module = new SimpleModule("timestamps-with-offset");
        module.addSerializer(LocalDateTime.class, new JsonSerializer<>() {
            @Override
            public void serialize(LocalDateTime value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
                gen.writeString(value.atZone(ZoneId.systemDefault()).toOffsetDateTime().format(ISO_WITH_OFFSET));
            }
        });
        return builder -> builder.modulesToInstall(module);
    }
}
