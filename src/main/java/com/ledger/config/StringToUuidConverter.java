package com.ledger.config;

import java.util.UUID;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;

/** Reads a {@code CHAR(36)} column into a {@link UUID} id. */
@ReadingConverter
enum StringToUuidConverter implements Converter<String, UUID> {
    INSTANCE;

    @Override
    public UUID convert(String source) {
        return UUID.fromString(source);
    }
}
