package com.voicepulse.routing.infrastructure.persistence.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.instancio.Instancio;
import org.instancio.Select;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Spring Data Persistence Entity Models & JsonbDocument Unit Tests")
class PersistenceEntityModelsTest {

    @Test
    @DisplayName("JsonbDocument should enforce immutability and PostgreSQL key-merge semantics")
    void jsonbDocumentShouldBeImmutableAndMergeCleanly() {
        Map<String, Object> source = new HashMap<>();
        source.put("targetSkillGroup", "DISRUPTION_DESK_STANDARD");
        source.put("seat", "14A");
        JsonbDocument base = JsonbDocument.of(source);
        source.put("mutated", "shouldNotAppear");

        assertThat(base.entries())
                .containsExactlyInAnyOrderEntriesOf(
                        Map.of(
                                "targetSkillGroup", "DISRUPTION_DESK_STANDARD",
                                "seat", "14A"));
        assertThatThrownBy(() -> base.entries().put("illegal", "write"))
                .isInstanceOf(UnsupportedOperationException.class);

        JsonbDocument incoming =
                JsonbDocument.of(
                        Map.of("targetSkillGroup", "DISRUPTION_DESK_GOLD", "hotelVouchers", true));
        JsonbDocument merged = base.mergeWith(incoming);

        assertThat(merged.entries())
                .containsEntry("targetSkillGroup", "DISRUPTION_DESK_GOLD")
                .containsEntry("seat", "14A")
                .containsEntry("hotelVouchers", true);
        assertThat(base.mergeWith(null)).isSameAs(base);
        assertThat(base.mergeWith(JsonbDocument.empty())).isSameAs(base);
        assertThat(JsonbDocument.empty().mergeWith(base)).isSameAs(base);
        assertThat(JsonbDocument.of(null)).isEqualTo(JsonbDocument.empty());
        assertThat(JsonbDocument.of(Map.of())).isEqualTo(JsonbDocument.empty());
    }

    @Test
    @DisplayName(
            "CallSessionEntity should merge ticket save and audit updates without losing history")
    void callSessionShouldMergeTicketAndAuditUpdates() {
        UUID callId = UUID.randomUUID();
        Instant initialCreatedAt = Instant.parse("2026-11-10T08:00:00Z");
        Instant incomingTime = Instant.parse("2026-11-10T08:05:00Z");
        Instant auditTime = Instant.parse("2026-11-10T08:10:00Z");

        CallSessionEntity existing =
                Instancio.of(CallSessionEntity.class)
                        .set(Select.field(CallSessionEntity::callId), callId)
                        .set(Select.field(CallSessionEntity::pnr), "AB7DF9")
                        .set(Select.field(CallSessionEntity::callerNumber), "+15551230001")
                        .set(Select.field(CallSessionEntity::status), "QUEUED")
                        .set(Select.field(CallSessionEntity::startTime), initialCreatedAt)
                        .set(Select.field(CallSessionEntity::endTime), null)
                        .set(Select.field(CallSessionEntity::durationSeconds), 42)
                        .set(Select.field(CallSessionEntity::assignedOperatorId), "op-initial")
                        .set(
                                Select.field(CallSessionEntity::metadata),
                                JsonbDocument.of(
                                        Map.of(
                                                "targetSkillGroup", "DISRUPTION_DESK_GOLD",
                                                "origin", "voice")))
                        .set(Select.field(CallSessionEntity::createdAt), initialCreatedAt)
                        .set(Select.field(CallSessionEntity::updatedAt), initialCreatedAt)
                        .create();

        CallSessionEntity incoming =
                new CallSessionEntity(
                        callId,
                        null,
                        null,
                        "ASSIGNED",
                        incomingTime,
                        null,
                        null,
                        "op-updated",
                        JsonbDocument.of(Map.of("origin", "kafka", "note", "vip")),
                        incomingTime,
                        incomingTime);

        CallSessionEntity merged = CallSessionEntity.mergeForTicketSave(existing, incoming);

        assertThat(merged.pnr()).isEqualTo("AB7DF9");
        assertThat(merged.callerNumber()).isEqualTo("+15551230001");
        assertThat(merged.durationSeconds()).isEqualTo(42);
        assertThat(merged.assignedOperatorId()).isEqualTo("op-updated");
        assertThat(merged.status()).isEqualTo("ASSIGNED");
        assertThat(merged.startTime()).isEqualTo(initialCreatedAt);
        assertThat(merged.createdAt()).isEqualTo(initialCreatedAt);
        assertThat(merged.updatedAt()).isEqualTo(incomingTime);
        assertThat(merged.metadata().entries())
                .containsEntry("targetSkillGroup", "DISRUPTION_DESK_GOLD")
                .containsEntry("origin", "kafka")
                .containsEntry("note", "vip");

        CallSessionEntity auditedWithoutNewOperator =
                merged.mergeForAudit("COMPLETED", null, auditTime);
        assertThat(auditedWithoutNewOperator.status()).isEqualTo("COMPLETED");
        assertThat(auditedWithoutNewOperator.assignedOperatorId()).isEqualTo("op-updated");
        assertThat(auditedWithoutNewOperator.updatedAt()).isEqualTo(auditTime);
        assertThat(auditedWithoutNewOperator.metadata()).isEqualTo(merged.metadata());

        CallSessionEntity initialAudit =
                CallSessionEntity.initialForAudit(callId, "QUEUED", null, auditTime);
        assertThat(initialAudit.callId()).isEqualTo(callId);
        assertThat(initialAudit.metadata()).isEqualTo(JsonbDocument.empty());
        assertThat(initialAudit.createdAt()).isEqualTo(auditTime);

        assertThatThrownBy(() -> existing.mergeForAudit("  ", "op-1", auditTime))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CallSessionEntity.initialForAudit(callId, " ", null, auditTime))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("DisruptionTicketEntity and OperatorHandoffLogEntity should enforce invariants")
    void ticketAndAuditEntitiesShouldEnforceInvariants() {
        Instant originalCreatedAt = Instant.parse("2026-11-10T09:00:00Z");
        Instant updatedAt = Instant.parse("2026-11-10T09:15:00Z");
        UUID callId = UUID.randomUUID();

        DisruptionTicketEntity incoming =
                Instancio.of(DisruptionTicketEntity.class)
                        .set(Select.field(DisruptionTicketEntity::callId), callId)
                        .set(
                                Select.field(DisruptionTicketEntity::ticketId),
                                UUID.randomUUID().toString())
                        .set(Select.field(DisruptionTicketEntity::status), "QUEUED")
                        .set(Select.field(DisruptionTicketEntity::createdAt), updatedAt)
                        .set(Select.field(DisruptionTicketEntity::updatedAt), updatedAt)
                        .set(
                                Select.field(DisruptionTicketEntity::payload),
                                JsonbDocument.of(Map.of("tier", "GOLD")))
                        .create();

        DisruptionTicketEntity preserved =
                DisruptionTicketEntity.mergePreservingCreatedAt(incoming, originalCreatedAt);
        assertThat(preserved.createdAt()).isEqualTo(originalCreatedAt);
        assertThat(preserved.updatedAt()).isEqualTo(updatedAt);

        DisruptionTicketEntity statusUpdated = preserved.withStatus("COMPLETED", updatedAt);
        assertThat(statusUpdated.status()).isEqualTo("COMPLETED");
        assertThat(statusUpdated.createdAt()).isEqualTo(originalCreatedAt);
        assertThat(statusUpdated.updatedAt()).isEqualTo(updatedAt);

        assertThatThrownBy(() -> preserved.withStatus(" ", updatedAt))
                .isInstanceOf(IllegalArgumentException.class);

        OperatorHandoffLogEntity auditLog =
                new OperatorHandoffLogEntity(
                        null, callId, "TICKET_SAVED", null, "QUEUED", null, null, updatedAt);
        assertThat(auditLog.id()).isNull();
        assertThat(auditLog.details()).isEqualTo(JsonbDocument.empty());

        assertThatThrownBy(
                        () ->
                                new OperatorHandoffLogEntity(
                                        null, callId, "  ", null, "QUEUED", null, null, updatedAt))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
