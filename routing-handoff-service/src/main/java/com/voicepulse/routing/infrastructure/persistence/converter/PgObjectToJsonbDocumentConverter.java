package com.voicepulse.routing.infrastructure.persistence.converter;

import com.voicepulse.routing.infrastructure.persistence.JsonbColumnCodec;
import com.voicepulse.routing.infrastructure.persistence.model.JsonbDocument;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.postgresql.util.PGobject;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;

/**
 * Spring Data JDBC reading converter deserializing PostgreSQL {@code jsonb} {@link PGobject} column
 * values into immutable {@link JsonbDocument} instances.
 */
@ReadingConverter
@RequiredArgsConstructor
public class PgObjectToJsonbDocumentConverter implements Converter<PGobject, JsonbDocument> {

    private final @NonNull JsonbColumnCodec jsonbCodec;

    @Override
    public JsonbDocument convert(PGobject source) {
        if (source == null) {
            return JsonbDocument.empty();
        }
        return JsonbDocument.of(jsonbCodec.fromJson(source.getValue()));
    }
}
