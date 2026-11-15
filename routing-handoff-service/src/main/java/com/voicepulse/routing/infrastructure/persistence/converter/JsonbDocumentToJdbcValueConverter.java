package com.voicepulse.routing.infrastructure.persistence.converter;

import com.voicepulse.routing.infrastructure.persistence.JsonbColumnCodec;
import com.voicepulse.routing.infrastructure.persistence.TicketPersistenceException;
import com.voicepulse.routing.infrastructure.persistence.model.JsonbDocument;
import java.sql.JDBCType;
import java.sql.SQLException;
import java.util.Map;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.postgresql.util.PGobject;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.WritingConverter;
import org.springframework.data.jdbc.core.mapping.JdbcValue;

/**
 * Spring Data JDBC writing converter serializing {@link JsonbDocument} values to PostgreSQL {@code
 * jsonb} {@link PGobject} parameters bound as {@link JDBCType#OTHER}.
 */
@WritingConverter
@RequiredArgsConstructor
public class JsonbDocumentToJdbcValueConverter implements Converter<JsonbDocument, JdbcValue> {

    private static final String JSONB_TYPE = "jsonb";

    private final @NonNull JsonbColumnCodec jsonbCodec;

    @Override
    public JdbcValue convert(JsonbDocument source) {
        Map<String, Object> entries = (source == null) ? Map.of() : source.entries();
        String json = jsonbCodec.toJson(entries);
        PGobject pgObject = new PGobject();
        pgObject.setType(JSONB_TYPE);
        try {
            pgObject.setValue(json);
        } catch (SQLException ex) {
            throw new TicketPersistenceException("Failed to bind JSONB PGobject parameter", ex);
        }
        return JdbcValue.of(pgObject, JDBCType.OTHER);
    }
}
