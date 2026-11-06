package com.ledger.config;

import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;
import org.springframework.data.convert.WritingConverter;
import org.springframework.data.jdbc.repository.config.AbstractJdbcConfiguration;

/**
 * Spring Data JDBC setup. Ids are {@link UUID}s in the entities and {@code CHAR(36)} in the tables;
 * enums ({@code LedgerCurrency}, {@code Direction}) are stored by name out of the box.
 */
@Configuration
public class JdbcConfig extends AbstractJdbcConfiguration {

    @Override
    protected List<?> userConverters() {
        return List.of(UuidToString.INSTANCE, StringToUuid.INSTANCE);
    }

    @WritingConverter
    enum UuidToString implements Converter<UUID, String> {
        INSTANCE;

        @Override
        public String convert(UUID source) {
            return source.toString();
        }
    }

    @ReadingConverter
    enum StringToUuid implements Converter<String, UUID> {
        INSTANCE;

        @Override
        public UUID convert(String source) {
            return UUID.fromString(source);
        }
    }
}
