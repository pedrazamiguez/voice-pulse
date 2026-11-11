package com.voicepulse.conversation.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.instancio.Select.field;

import com.voicepulse.shared.model.FlightNumber;
import com.voicepulse.shared.model.Pnr;
import org.instancio.Instancio;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("Pure Domain MCP Tool Models Tests")
class McpDomainModelsTest {

    private static final Pnr VALID_PNR = new Pnr("A8F9X2");
    private static final FlightNumber VALID_FLIGHT = new FlightNumber("SW-104");

    @Nested
    @DisplayName("ActiveItinerary & CustomerProfile Tests")
    class CustomerProfileTests {

        @Test
        @DisplayName("Should construct valid ActiveItinerary and CustomerProfile via Instancio")
        void shouldConstructValidCustomerProfile() {
            ActiveItinerary itinerary =
                    Instancio.of(ActiveItinerary.class)
                            .set(field(ActiveItinerary::flightNumber), VALID_FLIGHT)
                            .set(field(ActiveItinerary::originAirport), "CTU")
                            .set(field(ActiveItinerary::destinationAirport), "MAD")
                            .create();

            CustomerProfile profile =
                    Instancio.of(CustomerProfile.class)
                            .set(field(CustomerProfile::pnr), VALID_PNR)
                            .set(field(CustomerProfile::frequentFlyerTier), FrequentFlyerTier.GOLD)
                            .set(field(CustomerProfile::activeItinerary), itinerary)
                            .create();

            assertThat(profile.pnr()).isEqualTo(VALID_PNR);
            assertThat(profile.frequentFlyerTier()).isEqualTo(FrequentFlyerTier.GOLD);
            assertThat(profile.activeItinerary().flightNumber()).isEqualTo(VALID_FLIGHT);
            assertThat(profile.activeItinerary().originAirport()).isEqualTo("CTU");
            assertThat(profile.activeItinerary().destinationAirport()).isEqualTo("MAD");
        }

        @Test
        @DisplayName("Should reject blank airport codes or customerName")
        void shouldRejectBlankFields() {
            ActiveItinerary baseItinerary =
                    Instancio.of(ActiveItinerary.class)
                            .set(field(ActiveItinerary::flightNumber), VALID_FLIGHT)
                            .create();

            assertThatThrownBy(
                            () ->
                                    new ActiveItinerary(
                                            baseItinerary.flightNumber(),
                                            "  ",
                                            baseItinerary.destinationAirport(),
                                            baseItinerary.disruptionReason(),
                                            baseItinerary.originalDeparture()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("originAirport must not be blank");

            assertThatThrownBy(
                            () ->
                                    new ActiveItinerary(
                                            baseItinerary.flightNumber(),
                                            baseItinerary.originAirport(),
                                            "",
                                            baseItinerary.disruptionReason(),
                                            baseItinerary.originalDeparture()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("destinationAirport must not be blank");

            assertThatThrownBy(
                            () ->
                                    new CustomerProfile(
                                            VALID_PNR,
                                            "   ",
                                            FrequentFlyerTier.STANDARD,
                                            baseItinerary))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("customerName must not be blank");
        }
    }

    @Nested
    @DisplayName("TicketSubmissionReceipt Tests")
    class TicketSubmissionReceiptTests {

        @Test
        @DisplayName("Should construct valid TicketSubmissionReceipt via Instancio")
        void shouldConstructValidReceipt() {
            TicketSubmissionReceipt receipt =
                    Instancio.of(TicketSubmissionReceipt.class)
                            .set(field(TicketSubmissionReceipt::pnr), VALID_PNR)
                            .set(field(TicketSubmissionReceipt::ticketId), "TKT-9921-X")
                            .create();

            assertThat(receipt.ticketId()).isEqualTo("TKT-9921-X");
            assertThat(receipt.pnr()).isEqualTo(VALID_PNR);
            assertThat(receipt.category()).isNotBlank();
            assertThat(receipt.priority()).isNotBlank();
            assertThat(receipt.status()).isNotBlank();
            assertThat(receipt.submittedAt()).isNotNull();
        }

        @Test
        @DisplayName("Should reject blank fields on TicketSubmissionReceipt")
        void shouldRejectBlankFields() {
            TicketSubmissionReceipt b =
                    Instancio.of(TicketSubmissionReceipt.class)
                            .set(field(TicketSubmissionReceipt::pnr), VALID_PNR)
                            .create();

            assertThatThrownBy(
                            () ->
                                    new TicketSubmissionReceipt(
                                            " ",
                                            b.pnr(),
                                            b.category(),
                                            b.priority(),
                                            b.status(),
                                            b.submittedAt()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("ticketId must not be blank");

            assertThatThrownBy(
                            () ->
                                    new TicketSubmissionReceipt(
                                            b.ticketId(),
                                            b.pnr(),
                                            " ",
                                            b.priority(),
                                            b.status(),
                                            b.submittedAt()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("category must not be blank");

            assertThatThrownBy(
                            () ->
                                    new TicketSubmissionReceipt(
                                            b.ticketId(),
                                            b.pnr(),
                                            b.category(),
                                            "",
                                            b.status(),
                                            b.submittedAt()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("priority must not be blank");

            assertThatThrownBy(
                            () ->
                                    new TicketSubmissionReceipt(
                                            b.ticketId(),
                                            b.pnr(),
                                            b.category(),
                                            b.priority(),
                                            "  ",
                                            b.submittedAt()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("status must not be blank");
        }
    }

    @Nested
    @DisplayName("OperatorQueueStatus & OperatorHandoffReceipt Tests")
    class OperatorRoutingModelTests {

        @Test
        @DisplayName("Should construct valid OperatorQueueStatus and OperatorHandoffReceipt")
        void shouldConstructValidOperatorRoutingModels() {
            OperatorQueueStatus queueStatus =
                    Instancio.of(OperatorQueueStatus.class)
                            .generate(
                                    field(OperatorQueueStatus::availableOperators),
                                    gen -> gen.ints().range(0, 20))
                            .generate(
                                    field(OperatorQueueStatus::estimatedWaitSeconds),
                                    gen -> gen.ints().range(0, 600))
                            .generate(
                                    field(OperatorQueueStatus::queuePosition),
                                    gen -> gen.ints().range(0, 50))
                            .create();

            OperatorHandoffReceipt handoffReceipt =
                    Instancio.of(OperatorHandoffReceipt.class)
                            .generate(
                                    field(OperatorHandoffReceipt::estimatedWaitSeconds),
                                    gen -> gen.ints().range(0, 600))
                            .generate(
                                    field(OperatorHandoffReceipt::queuePosition),
                                    gen -> gen.ints().range(0, 50))
                            .create();

            assertThat(queueStatus.department()).isNotBlank();
            assertThat(queueStatus.availableOperators()).isGreaterThanOrEqualTo(0);
            assertThat(handoffReceipt.callId()).isNotNull();
            assertThat(handoffReceipt.ticketId()).isNotNull();
            assertThat(handoffReceipt.status()).isNotBlank();
        }

        @Test
        @DisplayName("Should reject negative queue metrics or blank department")
        void shouldRejectInvalidOperatorRoutingMetrics() {
            OperatorHandoffReceipt b =
                    Instancio.of(OperatorHandoffReceipt.class)
                            .set(field(OperatorHandoffReceipt::estimatedWaitSeconds), 10)
                            .set(field(OperatorHandoffReceipt::queuePosition), 1)
                            .create();

            assertThatThrownBy(() -> new OperatorQueueStatus("DISRUPTION_DESK_GOLD", -1, 10, 1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("availableOperators must be >= 0: -1");

            assertThatThrownBy(() -> new OperatorQueueStatus("DISRUPTION_DESK_GOLD", 1, -5, 1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("estimatedWaitSeconds must be >= 0: -5");

            assertThatThrownBy(() -> new OperatorQueueStatus("DISRUPTION_DESK_GOLD", 1, 10, -2))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("queuePosition must be >= 0: -2");

            assertThatThrownBy(
                            () ->
                                    new OperatorHandoffReceipt(
                                            b.callId(),
                                            b.ticketId(),
                                            "  ",
                                            b.status(),
                                            b.estimatedWaitSeconds(),
                                            b.queuePosition(),
                                            b.dispatchedAt()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("department must not be blank");

            assertThatThrownBy(
                            () ->
                                    new OperatorHandoffReceipt(
                                            b.callId(),
                                            b.ticketId(),
                                            b.department(),
                                            b.status(),
                                            -1,
                                            b.queuePosition(),
                                            b.dispatchedAt()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("estimatedWaitSeconds must be >= 0: -1");
        }
    }
}
