package com.voicepulse.routing.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.instancio.Select.field;

import com.voicepulse.routing.domain.model.SkillGroup;
import com.voicepulse.routing.domain.model.SkillMatchPlan;
import com.voicepulse.shared.event.TicketFormFilledEvent;
import com.voicepulse.shared.model.DisruptionReason;
import com.voicepulse.shared.model.FlightNumber;
import com.voicepulse.shared.model.Pnr;
import java.util.List;
import java.util.Map;
import org.instancio.Instancio;
import org.instancio.Model;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("SkillGroupMatchingPolicy Domain Unit Tests")
class SkillGroupMatchingPolicyTest {

    private static final Model<TicketFormFilledEvent> BASE_TICKET_MODEL =
            Instancio.of(TicketFormFilledEvent.class)
                    .set(field(TicketFormFilledEvent::pnr), new Pnr("A8F9X2"))
                    .set(
                            field(TicketFormFilledEvent::originalFlightNumber),
                            new FlightNumber("SW-104"))
                    .set(field(TicketFormFilledEvent::hotelRequired), false)
                    .set(field(TicketFormFilledEvent::specialAssistance), false)
                    .set(field(TicketFormFilledEvent::targetSkillGroup), "DISRUPTION_DESK_STANDARD")
                    .set(
                            field(TicketFormFilledEvent::disruptionReason),
                            DisruptionReason.DELAYED_MAINTENANCE)
                    .set(field(TicketFormFilledEvent::payload), Map.of())
                    .toModel();

    private final SkillGroupMatchingPolicy policy = new SkillGroupMatchingPolicy();

    private static TicketFormFilledEvent ticketWith(
            String targetSkillGroup,
            boolean hotelRequired,
            boolean specialAssistance,
            DisruptionReason reason) {
        return Instancio.of(BASE_TICKET_MODEL)
                .set(field(TicketFormFilledEvent::targetSkillGroup), targetSkillGroup)
                .set(field(TicketFormFilledEvent::hotelRequired), hotelRequired)
                .set(field(TicketFormFilledEvent::specialAssistance), specialAssistance)
                .set(field(TicketFormFilledEvent::disruptionReason), reason)
                .create();
    }

    @Nested
    @DisplayName("Primary Skill Group Resolution & Elevation")
    class PrimaryResolutionTests {

        @ParameterizedTest
        @CsvSource({
            "DISRUPTION_DESK_GOLD, DISRUPTION_DESK_GOLD",
            "disruption_desk_accessibility, DISRUPTION_DESK_ACCESSIBILITY",
            "DISRUPTION_DESK_PRIORITY, DISRUPTION_DESK_PRIORITY",
            "DISRUPTION_DESK_STANDARD, DISRUPTION_DESK_STANDARD"
        })
        @DisplayName("Should resolve exact known SkillGroup codes case-insensitively")
        void shouldResolveKnownSkillGroups(String rawCode, SkillGroup expected) {
            TicketFormFilledEvent event =
                    ticketWith(rawCode, false, false, DisruptionReason.DELAYED_MAINTENANCE);

            assertThat(policy.resolveTargetSkillGroup(event)).isEqualTo(expected);
        }

        @Test
        @DisplayName("Should elevate STANDARD and PRIORITY tickets when specialAssistance is true")
        void shouldElevateToAccessibilityForSpecialAssistance() {
            TicketFormFilledEvent standardTicket =
                    ticketWith(
                            "DISRUPTION_DESK_STANDARD",
                            true,
                            true,
                            DisruptionReason.DELAYED_MAINTENANCE);
            TicketFormFilledEvent priorityTicket =
                    ticketWith(
                            "DISRUPTION_DESK_PRIORITY",
                            false,
                            true,
                            DisruptionReason.CANCELED_WEATHER);

            assertThat(policy.resolveTargetSkillGroup(standardTicket))
                    .isEqualTo(SkillGroup.DISRUPTION_DESK_ACCESSIBILITY);
            assertThat(policy.resolveTargetSkillGroup(priorityTicket))
                    .isEqualTo(SkillGroup.DISRUPTION_DESK_ACCESSIBILITY);
        }

        @Test
        @DisplayName(
                "Should preserve GOLD tier even when specialAssistance or hotelRequired is true")
        void shouldPreserveGoldTierOverElevation() {
            TicketFormFilledEvent goldTicket =
                    ticketWith(
                            "DISRUPTION_DESK_GOLD", true, true, DisruptionReason.CANCELED_WEATHER);

            assertThat(policy.resolveTargetSkillGroup(goldTicket))
                    .isEqualTo(SkillGroup.DISRUPTION_DESK_GOLD);
        }

        @Test
        @DisplayName("Should elevate STANDARD ticket to PRIORITY when hotelRequired is true")
        void shouldElevateStandardToPriorityWhenHotelRequired() {
            TicketFormFilledEvent hotelTicket =
                    ticketWith(
                            "DISRUPTION_DESK_STANDARD",
                            true,
                            false,
                            DisruptionReason.DELAYED_MAINTENANCE);

            assertThat(policy.resolveTargetSkillGroup(hotelTicket))
                    .isEqualTo(SkillGroup.DISRUPTION_DESK_PRIORITY);
        }

        @Test
        @DisplayName("Should derive keyword match or STANDARD fallback for unknown skill codes")
        void shouldHandleUnknownSkillGroupCodesSafely() {
            TicketFormFilledEvent vipUnknown =
                    ticketWith("VIP_GOLD_DESK", false, false, DisruptionReason.DELAYED_MAINTENANCE);
            TicketFormFilledEvent accessUnknown =
                    ticketWith(
                            "wheelchair_access_queue",
                            false,
                            false,
                            DisruptionReason.DELAYED_MAINTENANCE);
            TicketFormFilledEvent unknownNoFlags =
                    ticketWith(
                            "UNRECOGNIZED_DESK",
                            false,
                            false,
                            DisruptionReason.DELAYED_MAINTENANCE);
            TicketFormFilledEvent unknownWithHotel =
                    ticketWith(
                            "UNRECOGNIZED_DESK", true, false, DisruptionReason.DELAYED_MAINTENANCE);

            assertThat(policy.resolveTargetSkillGroup(vipUnknown))
                    .isEqualTo(SkillGroup.DISRUPTION_DESK_GOLD);
            assertThat(policy.resolveTargetSkillGroup(accessUnknown))
                    .isEqualTo(SkillGroup.DISRUPTION_DESK_ACCESSIBILITY);
            assertThat(policy.resolveTargetSkillGroup(unknownNoFlags))
                    .isEqualTo(SkillGroup.DISRUPTION_DESK_STANDARD);
            assertThat(policy.resolveTargetSkillGroup(unknownWithHotel))
                    .isEqualTo(SkillGroup.DISRUPTION_DESK_PRIORITY);
        }
    }

    @Nested
    @DisplayName("Ordered Candidate Chains & Priority Scoring")
    class CandidateAndPriorityTests {

        @Test
        @DisplayName("Should build ordered fallback candidate chains starting with primary desk")
        void shouldBuildOrderedCandidateChains() {
            TicketFormFilledEvent goldTicket =
                    ticketWith(
                            "DISRUPTION_DESK_GOLD",
                            false,
                            false,
                            DisruptionReason.CANCELED_WEATHER);
            TicketFormFilledEvent goldSpecialTicket =
                    ticketWith(
                            "DISRUPTION_DESK_GOLD", true, true, DisruptionReason.CANCELED_WEATHER);
            TicketFormFilledEvent accessTicket =
                    ticketWith(
                            "DISRUPTION_DESK_STANDARD",
                            false,
                            true,
                            DisruptionReason.DELAYED_MAINTENANCE);
            TicketFormFilledEvent priorityTicket =
                    ticketWith(
                            "DISRUPTION_DESK_PRIORITY",
                            false,
                            false,
                            DisruptionReason.CANCELED_CREW_REST);
            TicketFormFilledEvent standardTicket =
                    ticketWith(
                            "DISRUPTION_DESK_STANDARD",
                            false,
                            false,
                            DisruptionReason.DELAYED_MAINTENANCE);

            assertThat(policy.resolveCandidateOrder(goldTicket))
                    .containsExactly(
                            SkillGroup.DISRUPTION_DESK_GOLD,
                            SkillGroup.DISRUPTION_DESK_PRIORITY,
                            SkillGroup.DISRUPTION_DESK_STANDARD);
            assertThat(policy.resolveCandidateOrder(goldSpecialTicket))
                    .containsExactly(
                            SkillGroup.DISRUPTION_DESK_GOLD,
                            SkillGroup.DISRUPTION_DESK_ACCESSIBILITY,
                            SkillGroup.DISRUPTION_DESK_PRIORITY,
                            SkillGroup.DISRUPTION_DESK_STANDARD);
            assertThat(policy.resolveCandidateOrder(accessTicket))
                    .containsExactly(
                            SkillGroup.DISRUPTION_DESK_ACCESSIBILITY,
                            SkillGroup.DISRUPTION_DESK_PRIORITY,
                            SkillGroup.DISRUPTION_DESK_STANDARD);
            assertThat(policy.resolveCandidateOrder(priorityTicket))
                    .containsExactly(
                            SkillGroup.DISRUPTION_DESK_PRIORITY,
                            SkillGroup.DISRUPTION_DESK_STANDARD);
            assertThat(policy.resolveCandidateOrder(standardTicket))
                    .containsExactly(SkillGroup.DISRUPTION_DESK_STANDARD);
        }

        @Test
        @DisplayName(
                "Should compute deterministic monotonic priority scores across tiers and flags")
        void shouldComputeDeterministicPriorityScores() {
            TicketFormFilledEvent baselineStandard =
                    ticketWith(
                            "DISRUPTION_DESK_STANDARD",
                            false,
                            false,
                            DisruptionReason.DELAYED_MAINTENANCE);
            TicketFormFilledEvent hotelWeatherPriority =
                    ticketWith(
                            "DISRUPTION_DESK_STANDARD",
                            true,
                            false,
                            DisruptionReason.CANCELED_WEATHER);
            TicketFormFilledEvent accessDiverted =
                    ticketWith(
                            "DISRUPTION_DESK_ACCESSIBILITY",
                            false,
                            true,
                            DisruptionReason.DIVERTED_TECHNICAL);
            TicketFormFilledEvent maxGold =
                    ticketWith(
                            "DISRUPTION_DESK_GOLD", true, true, DisruptionReason.CANCELED_WEATHER);

            SkillMatchPlan standardPlan = policy.evaluate(baselineStandard);
            SkillMatchPlan priorityPlan = policy.evaluate(hotelWeatherPriority);
            SkillMatchPlan accessPlan = policy.evaluate(accessDiverted);
            SkillMatchPlan goldPlan = policy.evaluate(maxGold);

            assertThat(standardPlan.priorityScore()).isEqualTo(50);
            assertThat(priorityPlan.priorityScore()).isEqualTo(115);
            assertThat(accessPlan.priorityScore()).isEqualTo(125);
            assertThat(goldPlan.priorityScore()).isEqualTo(160);
            assertThat(goldPlan.primarySkillGroup()).isEqualTo(SkillGroup.DISRUPTION_DESK_GOLD);
            assertThat(goldPlan.candidateOrder()).startsWith(goldPlan.primarySkillGroup());
        }

        @Test
        @DisplayName("Should enforce SkillMatchPlan record invariants")
        void shouldEnforceSkillMatchPlanInvariants() {
            assertThatThrownBy(
                            () ->
                                    new SkillMatchPlan(
                                            null, List.of(SkillGroup.DISRUPTION_DESK_GOLD), 100))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new SkillMatchPlan(SkillGroup.DISRUPTION_DESK_GOLD, null, 100))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(
                            () ->
                                    new SkillMatchPlan(
                                            SkillGroup.DISRUPTION_DESK_GOLD, List.of(), 100))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("empty");
            assertThatThrownBy(
                            () ->
                                    new SkillMatchPlan(
                                            SkillGroup.DISRUPTION_DESK_GOLD,
                                            List.of(SkillGroup.DISRUPTION_DESK_STANDARD),
                                            100))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("start with primarySkillGroup");
            assertThatThrownBy(
                            () ->
                                    new SkillMatchPlan(
                                            SkillGroup.DISRUPTION_DESK_GOLD,
                                            List.of(SkillGroup.DISRUPTION_DESK_GOLD),
                                            -1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("negative");
        }
    }
}
