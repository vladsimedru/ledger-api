package com.ledger.config;

import java.util.UUID;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.WritingConverter;

/** Writes a {@link UUID} id into its {@code CHAR(36)} column. */
@WritingConverter
enum UuidToStringConverter implements Converter<UUID, String> {
    INSTANCE;

    @Override
    public String convert(UUID source) {
        return source.toString();
    }
}
