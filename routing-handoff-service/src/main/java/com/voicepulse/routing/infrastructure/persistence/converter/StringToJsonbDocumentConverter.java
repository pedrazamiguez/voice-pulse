package com.voicepulse.routing.infrastructure.persistence.converter;

import com.voicepulse.routing.infrastructure.persistence.JsonbColumnCodec;
import com.voicepulse.routing.infrastructure.persistence.model.JsonbDocument;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;

/**
 * Spring Data JDBC reading converter deserializing raw JSON string column values into immutable
 * {@link JsonbDocument} instances.
 */
@ReadingConverter
@RequiredArgsConstructor
public class StringToJsonbDocumentConverter implements Converter<String, JsonbDocument> {

    private final @NonNull JsonbColumnCodec jsonbCodec;

    @Override
    public JsonbDocument convert(String source) {
        return JsonbDocument.of(jsonbCodec.fromJson(source));
    }
}
