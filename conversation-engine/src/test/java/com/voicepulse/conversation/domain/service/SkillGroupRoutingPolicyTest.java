package com.voicepulse.conversation.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.instancio.Select.field;

import com.voicepulse.conversation.domain.model.ActiveItinerary;
import com.voicepulse.conversation.domain.model.AirlineDisruptionTicket;
import com.voicepulse.conversation.domain.model.CustomerProfile;
import com.voicepulse.conversation.domain.model.FrequentFlyerTier;
import com.voicepulse.shared.model.DisruptionReason;
import com.voicepulse.shared.model.FlightNumber;
import com.voicepulse.shared.model.Pnr;
import java.util.Map;
import java.util.stream.Stream;
import org.instancio.Instancio;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@DisplayName("SkillGroupRoutingPolicy Pure Domain Tests")
class SkillGroupRoutingPolicyTest {

    private final SkillGroupRoutingPolicy policy = new SkillGroupRoutingPolicy();

    @Nested
    @DisplayName("Priority Matrix & Precedence Rules")
    class PriorityMatrixTests {

        static Stream<Arguments> routingMatrix() {
            return Stream.of(
                    // GOLD & PLATINUM always take top precedence -> DISRUPTION_DESK_GOLD
                    Arguments.of(
                            FrequentFlyerTier.GOLD,
                            false,
                            DisruptionReason.DELAYED_MAINTENANCE,
                            SkillGroupRoutingPolicy.DISRUPTION_DESK_GOLD),
                    Arguments.of(
                            FrequentFlyerTier.GOLD,
                            true,
                            DisruptionReason.CANCELED_WEATHER,
                            SkillGroupRoutingPolicy.DISRUPTION_DESK_GOLD),
                    Arguments.of(
                            FrequentFlyerTier.PLATINUM,
                            false,
                            DisruptionReason.CANCELED_CREW_REST,
                            SkillGroupRoutingPolicy.DISRUPTION_DESK_GOLD),
                    Arguments.of(
                            FrequentFlyerTier.PLATINUM,
                            true,
                            DisruptionReason.CANCELED_WEATHER,
                            SkillGroupRoutingPolicy.DISRUPTION_DESK_GOLD),
                    // Accessibility takes second precedence for SILVER / STANDARD
                    Arguments.of(
                            FrequentFlyerTier.SILVER,
                            true,
                            DisruptionReason.CANCELED_WEATHER,
                            SkillGroupRoutingPolicy.DISRUPTION_DESK_ACCESSIBILITY),
                    Arguments.of(
                            FrequentFlyerTier.STANDARD,
                            true,
                            DisruptionReason.DIVERTED_TECHNICAL,
                            SkillGroupRoutingPolicy.DISRUPTION_DESK_ACCESSIBILITY),
                    // Weather cancellation takes third precedence
                    Arguments.of(
                            FrequentFlyerTier.SILVER,
                            false,
                            DisruptionReason.CANCELED_WEATHER,
                            SkillGroupRoutingPolicy.DISRUPTION_DESK_PRIORITY),
                    Arguments.of(
                            FrequentFlyerTier.STANDARD,
                            false,
                            DisruptionReason.CANCELED_WEATHER,
                            SkillGroupRoutingPolicy.DISRUPTION_DESK_PRIORITY),
                    // Standard fallback for all other non-priority combinations
                    Arguments.of(
                            FrequentFlyerTier.SILVER,
                            false,
                            DisruptionReason.DELAYED_MAINTENANCE,
                            SkillGroupRoutingPolicy.DISRUPTION_DESK_STANDARD),
                    Arguments.of(
                            FrequentFlyerTier.STANDARD,
                            false,
                            DisruptionReason.CANCELED_CREW_REST,
                            SkillGroupRoutingPolicy.DISRUPTION_DESK_STANDARD),
                    Arguments.of(
                            FrequentFlyerTier.STANDARD,
                            false,
                            DisruptionReason.DIVERTED_TECHNICAL,
                            SkillGroupRoutingPolicy.DISRUPTION_DESK_STANDARD));
        }

        @ParameterizedTest(name = "tier={0}, specialAssistance={1}, reason={2} -> {3}")
        @MethodSource("routingMatrix")
        @DisplayName("Should resolve skill group strictly by approved precedence")
        void shouldResolveSkillGroupByApprovedPrecedence(
                FrequentFlyerTier tier,
                boolean specialAssistance,
                DisruptionReason reason,
                String expectedSkillGroup) {
            String resolved = policy.resolveSkillGroup(tier, specialAssistance, reason);

            assertThat(resolved).isEqualTo(expectedSkillGroup);
        }
    }

    @Nested
    @DisplayName("Domain Aggregate Overload & Validation")
    class AggregateOverloadTests {

        @Test
        @DisplayName("Should resolve skill group from CustomerProfile and AirlineDisruptionTicket")
        void shouldResolveFromProfileAndTicket() {
            ActiveItinerary itinerary =
                    Instancio.of(ActiveItinerary.class)
                            .set(field(ActiveItinerary::flightNumber), new FlightNumber("SW-104"))
                            .create();
            CustomerProfile profile =
                    Instancio.of(CustomerProfile.class)
                            .set(field(CustomerProfile::pnr), new Pnr("A8F9X2"))
                            .set(field(CustomerProfile::frequentFlyerTier), FrequentFlyerTier.GOLD)
                            .set(field(CustomerProfile::activeItinerary), itinerary)
                            .create();
            AirlineDisruptionTicket ticket =
                    Instancio.of(AirlineDisruptionTicket.class)
                            .set(field(AirlineDisruptionTicket::pnr), "A8F9X2")
                            .set(field(AirlineDisruptionTicket::specialAssistance), false)
                            .set(
                                    field(AirlineDisruptionTicket::disruptionReason),
                                    DisruptionReason.CANCELED_WEATHER)
                            .set(field(AirlineDisruptionTicket::payload), Map.of())
                            .create();

            String resolved = policy.resolveSkillGroup(profile, ticket);

            assertThat(resolved).isEqualTo(SkillGroupRoutingPolicy.DISRUPTION_DESK_GOLD);
        }

        @Test
        @DisplayName("Should resolve accessibility skill group from profile and ticket")
        void shouldResolveAccessibilityFromProfileAndTicket() {
            ActiveItinerary itinerary =
                    Instancio.of(ActiveItinerary.class)
                            .set(field(ActiveItinerary::flightNumber), new FlightNumber("SW-412"))
                            .create();
            CustomerProfile profile =
                    Instancio.of(CustomerProfile.class)
                            .set(field(CustomerProfile::pnr), new Pnr("C3K9M1"))
                            .set(
                                    field(CustomerProfile::frequentFlyerTier),
                                    FrequentFlyerTier.SILVER)
                            .set(field(CustomerProfile::activeItinerary), itinerary)
                            .create();
            AirlineDisruptionTicket ticket =
                    Instancio.of(AirlineDisruptionTicket.class)
                            .set(field(AirlineDisruptionTicket::pnr), "C3K9M1")
                            .set(field(AirlineDisruptionTicket::specialAssistance), true)
                            .set(
                                    field(AirlineDisruptionTicket::disruptionReason),
                                    DisruptionReason.CANCELED_CREW_REST)
                            .set(field(AirlineDisruptionTicket::payload), Map.of())
                            .create();

            String resolved = policy.resolveSkillGroup(profile, ticket);

            assertThat(resolved).isEqualTo(SkillGroupRoutingPolicy.DISRUPTION_DESK_ACCESSIBILITY);
        }

        @Test
        @DisplayName("Should reject null domain inputs fail-fast")
        void shouldRejectNullInputs() {
            ActiveItinerary itinerary =
                    Instancio.of(ActiveItinerary.class)
                            .set(field(ActiveItinerary::flightNumber), new FlightNumber("SW-104"))
                            .create();
            CustomerProfile profile =
                    Instancio.of(CustomerProfile.class)
                            .set(field(CustomerProfile::pnr), new Pnr("A8F9X2"))
                            .set(field(CustomerProfile::activeItinerary), itinerary)
                            .create();
            AirlineDisruptionTicket ticket =
                    Instancio.of(AirlineDisruptionTicket.class)
                            .set(field(AirlineDisruptionTicket::pnr), "A8F9X2")
                            .set(field(AirlineDisruptionTicket::payload), Map.of())
                            .create();

            assertThatThrownBy(
                            () ->
                                    policy.resolveSkillGroup(
                                            null, false, DisruptionReason.CANCELED_WEATHER))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessage("frequentFlyerTier must not be null");

            assertThatThrownBy(
                            () -> policy.resolveSkillGroup(FrequentFlyerTier.STANDARD, true, null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessage("disruptionReason must not be null");

            assertThatThrownBy(() -> policy.resolveSkillGroup(null, ticket))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessage("customerProfile must not be null");

            assertThatThrownBy(() -> policy.resolveSkillGroup(profile, null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessage("ticket must not be null");
        }
    }
}
