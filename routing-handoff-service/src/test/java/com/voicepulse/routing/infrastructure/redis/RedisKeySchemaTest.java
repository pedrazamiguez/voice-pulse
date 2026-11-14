package com.voicepulse.routing.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.voicepulse.routing.domain.model.OperatorId;
import com.voicepulse.routing.domain.model.SkillGroup;
import com.voicepulse.routing.infrastructure.redis.config.RedisRoutingProperties;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("RedisKeySchema Tests")
class RedisKeySchemaTest {

    private RedisKeySchema keySchema;

    @BeforeEach
    void setUp() {
        RedisRoutingProperties properties =
                new RedisRoutingProperties(true, "voicepulse:routing:", Duration.ofSeconds(5));
        keySchema = new RedisKeySchema(properties);
    }

    @Test
    @DisplayName("Should format canonical operator presence and heartbeat keys")
    void shouldFormatOperatorKeys() {
        OperatorId operatorId = OperatorId.fromString("op-gold-1");

        assertThat(keySchema.keyPrefix()).isEqualTo("voicepulse:routing");
        assertThat(keySchema.operatorPresenceKey(operatorId))
                .isEqualTo("voicepulse:routing:operators:op-gold-1:presence");
        assertThat(keySchema.operatorHeartbeatKey(operatorId))
                .isEqualTo("voicepulse:routing:operators:op-gold-1:heartbeat");
    }

    @Test
    @DisplayName("Should format canonical skill-group availability, operator, and queue keys")
    void shouldFormatSkillAndQueueKeys() {
        assertThat(keySchema.skillAvailableKey(SkillGroup.DISRUPTION_DESK_GOLD))
                .isEqualTo("voicepulse:routing:skills:DISRUPTION_DESK_GOLD:available");
        assertThat(keySchema.skillOperatorsKey(SkillGroup.DISRUPTION_DESK_GOLD))
                .isEqualTo("voicepulse:routing:skills:DISRUPTION_DESK_GOLD:operators");
        assertThat(keySchema.queueKey(SkillGroup.DISRUPTION_DESK_ACCESSIBILITY))
                .isEqualTo("voicepulse:routing:queues:DISRUPTION_DESK_ACCESSIBILITY");
        assertThat(keySchema.queueSequenceKey()).isEqualTo("voicepulse:routing:queues:sequence");
        assertThat(keySchema.queueCallIndexKey()).isEqualTo("voicepulse:routing:queues:call-index");
        assertThat(keySchema.queueItemsKey()).isEqualTo("voicepulse:routing:queues:items");
    }

    @Test
    @DisplayName("Should return all skill-group key collections in deterministic SkillGroup order")
    void shouldReturnDeterministicSkillGroupCollections() {
        assertThat(keySchema.allSkillGroups())
                .containsExactly(
                        SkillGroup.DISRUPTION_DESK_GOLD,
                        SkillGroup.DISRUPTION_DESK_ACCESSIBILITY,
                        SkillGroup.DISRUPTION_DESK_PRIORITY,
                        SkillGroup.DISRUPTION_DESK_STANDARD);

        assertThat(keySchema.allSkillAvailableKeys())
                .containsExactly(
                        "voicepulse:routing:skills:DISRUPTION_DESK_GOLD:available",
                        "voicepulse:routing:skills:DISRUPTION_DESK_ACCESSIBILITY:available",
                        "voicepulse:routing:skills:DISRUPTION_DESK_PRIORITY:available",
                        "voicepulse:routing:skills:DISRUPTION_DESK_STANDARD:available");

        assertThat(keySchema.allSkillOperatorsKeys())
                .containsExactly(
                        "voicepulse:routing:skills:DISRUPTION_DESK_GOLD:operators",
                        "voicepulse:routing:skills:DISRUPTION_DESK_ACCESSIBILITY:operators",
                        "voicepulse:routing:skills:DISRUPTION_DESK_PRIORITY:operators",
                        "voicepulse:routing:skills:DISRUPTION_DESK_STANDARD:operators");

        assertThat(keySchema.allQueueKeys())
                .containsExactly(
                        "voicepulse:routing:queues:DISRUPTION_DESK_GOLD",
                        "voicepulse:routing:queues:DISRUPTION_DESK_ACCESSIBILITY",
                        "voicepulse:routing:queues:DISRUPTION_DESK_PRIORITY",
                        "voicepulse:routing:queues:DISRUPTION_DESK_STANDARD");
    }

    @Test
    @DisplayName("Should reject null operatorId or skillGroup arguments")
    void shouldRejectNullArguments() {
        assertThatThrownBy(() -> keySchema.operatorPresenceKey(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> keySchema.operatorHeartbeatKey(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> keySchema.skillAvailableKey(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> keySchema.skillOperatorsKey(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> keySchema.queueKey(null)).isInstanceOf(NullPointerException.class);
    }
}
