package com.voicepulse.routing.infrastructure.persistence.converter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.voicepulse.routing.infrastructure.persistence.JsonbColumnCodec;
import com.voicepulse.routing.infrastructure.persistence.TicketPersistenceException;
import com.voicepulse.routing.infrastructure.persistence.model.JsonbDocument;
import com.voicepulse.shared.infrastructure.kafka.serializer.KafkaJsonCodecProvider;
import java.sql.JDBCType;
import java.sql.SQLException;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PGobject;
import org.springframework.data.jdbc.core.mapping.JdbcValue;

@DisplayName("JSONB Spring Data JDBC Converters Unit Tests")
class JsonbConvertersTest {

    private JsonbDocumentToJdbcValueConverter writingConverter;
    private PgObjectToJsonbDocumentConverter pgObjectReadingConverter;
    private StringToJsonbDocumentConverter stringReadingConverter;

    @BeforeEach
    void setUp() {
        JsonbColumnCodec codec = new JsonbColumnCodec(KafkaJsonCodecProvider.objectMapper());
        writingConverter = new JsonbDocumentToJdbcValueConverter(codec);
        pgObjectReadingConverter = new PgObjectToJsonbDocumentConverter(codec);
        stringReadingConverter = new StringToJsonbDocumentConverter(codec);
    }

    @Test
    @DisplayName("Should serialize JsonbDocument into JdbcValue with jsonb PGobject and OTHER type")
    void shouldWriteJsonbDocumentAsPgObject() {
        JsonbDocument doc =
                JsonbDocument.of(
                        Map.of(
                                "targetSkillGroup",
                                "DISRUPTION_DESK_GOLD",
                                "attempt",
                                2,
                                "priority",
                                true));

        JdbcValue jdbcValue = writingConverter.convert(doc);

        assertThat(jdbcValue).isNotNull();
        assertThat(jdbcValue.getJdbcType()).isEqualTo(JDBCType.OTHER);
        assertThat(jdbcValue.getValue()).isInstanceOf(PGobject.class);
        PGobject pgObject = (PGobject) jdbcValue.getValue();
        assertThat(pgObject.getType()).isEqualTo("jsonb");

        JsonbDocument roundTripped = pgObjectReadingConverter.convert(pgObject);
        assertThat(roundTripped.entries())
                .containsEntry("targetSkillGroup", "DISRUPTION_DESK_GOLD")
                .containsEntry("attempt", 2)
                .containsEntry("priority", true);
    }

    @Test
    @DisplayName("Should serialize null or empty JsonbDocument to empty JSON object")
    void shouldWriteNullOrEmptyJsonbDocumentAsEmptyJson() {
        JdbcValue fromNull = writingConverter.convert(null);
        JdbcValue fromEmpty = writingConverter.convert(JsonbDocument.empty());

        PGobject nullPg = (PGobject) fromNull.getValue();
        PGobject emptyPg = (PGobject) fromEmpty.getValue();
        assertThat(nullPg.getType()).isEqualTo("jsonb");
        assertThat(nullPg.getValue()).isEqualTo("{}");
        assertThat(emptyPg.getType()).isEqualTo("jsonb");
        assertThat(emptyPg.getValue()).isEqualTo("{}");
    }

    @Test
    @DisplayName("Should convert null, blank, or empty PGobject to empty JsonbDocument")
    void shouldReadNullOrEmptyPgObjectAsEmptyDocument() throws SQLException {
        assertThat(pgObjectReadingConverter.convert(null)).isEqualTo(JsonbDocument.empty());

        PGobject emptyObj = new PGobject();
        emptyObj.setType("jsonb");
        emptyObj.setValue("{}");
        assertThat(pgObjectReadingConverter.convert(emptyObj)).isEqualTo(JsonbDocument.empty());

        PGobject nullValueObj = new PGobject();
        nullValueObj.setType("jsonb");
        assertThat(pgObjectReadingConverter.convert(nullValueObj)).isEqualTo(JsonbDocument.empty());
    }

    @Test
    @DisplayName("Should read JSON string into JsonbDocument and handle null or blank")
    void shouldReadJsonStringIntoJsonbDocument() {
        JsonbDocument parsed =
                stringReadingConverter.convert(
                        "{\"ticketId\":\"T-100\",\"bridgeEndpoint\":\"/ws/bridge/1\"}");

        assertThat(parsed.entries())
                .containsEntry("ticketId", "T-100")
                .containsEntry("bridgeEndpoint", "/ws/bridge/1");
        assertThat(stringReadingConverter.convert(null)).isEqualTo(JsonbDocument.empty());
        assertThat(stringReadingConverter.convert("   ")).isEqualTo(JsonbDocument.empty());
        assertThat(stringReadingConverter.convert("{}")).isEqualTo(JsonbDocument.empty());
    }

    @Test
    @DisplayName("Should wrap malformed JSON into TicketPersistenceException")
    void shouldWrapMalformedJsonIntoPersistenceException() throws SQLException {
        PGobject malformedPg = new PGobject();
        malformedPg.setType("jsonb");
        malformedPg.setValue("{not-valid-json");

        assertThatThrownBy(() -> pgObjectReadingConverter.convert(malformedPg))
                .isInstanceOf(TicketPersistenceException.class);
        assertThatThrownBy(() -> stringReadingConverter.convert("{not-valid-json"))
                .isInstanceOf(TicketPersistenceException.class);
    }
}
