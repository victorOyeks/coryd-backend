package org.coryd.payment.service;

import org.coryd.common.enums.BookingStatus;
import org.coryd.common.enums.PaymentMethod;
import org.coryd.common.enums.PayoutStatus;
import org.coryd.common.enums.UserRole;
import org.coryd.common.exception.BadRequestException;
import org.coryd.common.exception.ResourceNotFoundException;
import org.coryd.common.exception.UnauthorizedException;
import org.coryd.common.kafka.KafkaTopics;
import org.coryd.common.kafka.events.TripCompletedEvent;
import org.coryd.payment.client.ProfileServiceClient;
import org.coryd.payment.client.TripServiceClient;
import org.coryd.payment.config.CommissionProperties;
import org.coryd.payment.dto.CardAuthorization;
import org.coryd.payment.dto.EarningsPeriod;
import org.coryd.payment.dto.InitiatePaymentRequest;
import org.coryd.payment.dto.InitiatePaymentResponse;
import org.coryd.payment.dto.SettlementStatus;
import org.coryd.payment.entity.PaymentTransaction;
import org.coryd.payment.entity.PayoutRecipient;
import org.coryd.payment.gateway.PaymentGatewayClient;
import org.coryd.payment.gateway.PaystackGatewayClient;
import org.coryd.payment.repository.DriverEarningsAccountRepository;
import org.coryd.payment.repository.DriverSettlementRepository;
import org.coryd.payment.repository.EarningsTransactionRepository;
import org.coryd.payment.repository.FareHoldRepository;
import org.coryd.payment.repository.PaymentTransactionRepository;
import org.coryd.payment.repository.PayoutRecipientRepository;
import org.coryd.payment.repository.SavedCardRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Card-only money paths against a throwaway Postgres container - never a shared or real database.
 * Every fare is split to the driver's own Paystack subaccount at charge time, so nothing here is
 * ever money we hold.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
@Import({PaymentService.class, EarningsService.class, SavedCardService.class, SettlementSyncService.class,
        CommissionProperties.class, DriverSplitResolver.class, SubaccountBackfill.class})
class PaymentFlowIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String RIDER = "rider-1";
    private static final String DRIVER = "driver-1";
    private static final String SUBACCOUNT = "ACCT_test123";
    private static final BigDecimal FARE = new BigDecimal("1200.00");
    private static final long FARE_KOBO = 120000L;

    @Autowired PaymentService paymentService;
    @Autowired EarningsService earningsService;
    @Autowired SavedCardService savedCardService;
    @Autowired SettlementSyncService settlementSyncService;
    @Autowired SubaccountBackfill subaccountBackfill;
    @Autowired PaymentTransactionRepository paymentTransactionRepository;
    @Autowired FareHoldRepository fareHoldRepository;
    @Autowired DriverEarningsAccountRepository earningsAccountRepository;
    @Autowired EarningsTransactionRepository earningsTransactionRepository;
    @Autowired PayoutRecipientRepository payoutRecipientRepository;
    @Autowired SavedCardRepository savedCardRepository;
    @Autowired DriverSettlementRepository driverSettlementRepository;
    @Autowired DataSource dataSource;

    @MockBean KafkaTemplate<String, Object> kafkaTemplate;
    @MockBean TripServiceClient tripServiceClient;
    @MockBean PaystackGatewayClient gatewayClient;
    @MockBean CheckoutEmailResolver checkoutEmailResolver;
    @MockBean ProfileServiceClient profileServiceClient;

    @BeforeEach
    void cleanSlate() throws Exception {
        try (Connection c = dataSource.getConnection()) {
            assertThat(c.getMetaData().getURL())
                    .as("tests must only ever run against the Testcontainers database")
                    .isEqualTo(postgres.getJdbcUrl());
        }
        driverSettlementRepository.deleteAll();
        savedCardRepository.deleteAll();
        payoutRecipientRepository.deleteAll();
        earningsTransactionRepository.deleteAll();
        earningsAccountRepository.deleteAll();
        fareHoldRepository.deleteAll();
        paymentTransactionRepository.deleteAll();
        reset(gatewayClient, tripServiceClient, profileServiceClient);
        clearInvocations(kafkaTemplate);
        when(checkoutEmailResolver.resolve(any(), any())).thenReturn("rider@example.com");
        when(gatewayClient.initiateSplit(anyString(), any(), anyString(), any(), anyMap(), any(), any()))
                .thenAnswer(inv -> new PaymentGatewayClient.GatewayInitResult("https://checkout/" + inv.getArgument(0), inv.getArgument(0)));
        when(gatewayClient.refund(anyString(), any(), any())).thenReturn(true);
        when(profileServiceClient.fetchRiderSummaries(anyList())).thenReturn(Map.of());
        givenDriverHasASubaccount();
    }

    // ---- paying: the client can't set its own price, payer or payee -------------------------------

    @Test
    void walletPaymentsAreNoLongerAccepted() {
        String booking = heldBooking();

        assertThatThrownBy(() -> paymentService.initiate(pay(booking, PaymentMethod.WALLET, null), RIDER))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("pay by card");
        verify(gatewayClient, never()).initiateSplit(anyString(), any(), anyString(), any(), anyMap(), any(), any());
    }

    @Test
    void onlyTheBookingsRiderCanPayForIt() {
        String booking = heldBooking();

        assertThatThrownBy(() -> paymentService.initiate(checkout(booking), "attacker")).isInstanceOf(UnauthorizedException.class);
        assertThat(paymentTransactionRepository.findAll()).isEmpty();
    }

    @Test
    void aBookingNoLongerHeldCannotBePaid() {
        String booking = UUID.randomUUID().toString();
        stubBooking(booking, BookingStatus.CANCELLED_RIDER);

        assertThatThrownBy(() -> paymentService.initiate(checkout(booking), RIDER)).isInstanceOf(BadRequestException.class);
    }

    @Test
    void theFareIsSplitToTheDriversOwnSubaccountAtItsRealPrice() {
        String booking = heldBooking();

        paymentService.initiate(checkout(booking), RIDER);

        verify(gatewayClient).initiateSplit(anyString(), eq(FARE), anyString(), any(), anyMap(), eq(SUBACCOUNT), isNull());
    }

    @Test
    void aDriverWithoutASubaccountCannotBeBooked() {
        payoutRecipientRepository.deleteAll();
        String booking = heldBooking();

        assertThatThrownBy(() -> paymentService.initiate(checkout(booking), RIDER))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("not finished setting up payouts");
    }

    @Test
    void retriedCheckoutReusesTheOpenOne() {
        String booking = heldBooking();

        InitiatePaymentResponse first = paymentService.initiate(checkout(booking), RIDER);
        InitiatePaymentResponse retry = paymentService.initiate(checkout(booking), RIDER);

        assertThat(retry.gatewayReference()).isEqualTo(first.gatewayReference());
        verify(gatewayClient, times(1)).initiateSplit(anyString(), any(), anyString(), any(), anyMap(), any(), any());
    }

    @Test
    void replayedChargeSuccessConfirmsOnce() {
        String booking = heldBooking();
        String reference = paymentService.initiate(checkout(booking), RIDER).gatewayReference();

        paymentService.handleWebhookSuccess(reference, booking, FARE_KOBO, null);
        paymentService.handleWebhookSuccess(reference, booking, FARE_KOBO, null);

        verify(kafkaTemplate, times(1)).send(eq(KafkaTopics.PAYMENT_SUCCEEDED), any());
    }

    @Test
    void lateChargeFailedAfterSuccessDoesNotReleaseTheBooking() {
        String booking = heldBooking();
        String reference = paidBooking(booking);

        paymentService.handleWebhookFailure(reference, "Declined");

        verify(kafkaTemplate, never()).send(eq(KafkaTopics.PAYMENT_FAILED), any());
        assertThat(paymentTransactionRepository.findByGatewayReference(reference).orElseThrow().getStatus()).isEqualTo(PayoutStatus.PAID);
    }

    @Test
    void chargeOnASupersededReferencePaysAStillUnpaidBooking() {
        String booking = heldBooking();
        paymentService.initiate(checkout(booking), RIDER);
        String transactionId = paymentTransactionRepository.findFirstByBookingIdOrderByCreatedAtDesc(booking).orElseThrow().getId();
        String superseded = "older-checkout-" + UUID.randomUUID();

        paymentService.handleWebhookSuccess(superseded, booking, FARE_KOBO, null);

        PaymentTransaction current = paymentTransactionRepository.findById(transactionId).orElseThrow();
        assertThat(current.getStatus()).isEqualTo(PayoutStatus.PAID);
        assertThat(current.getGatewayReference()).isEqualTo(superseded);
    }

    @Test
    void aSecondChargeForAnAlreadyPaidBookingGoesBackToTheCard() {
        String booking = heldBooking();
        paidBooking(booking);
        String duplicate = "older-checkout-" + UUID.randomUUID();

        paymentService.handleWebhookSuccess(duplicate, booking, FARE_KOBO, null);

        verify(gatewayClient).refund(eq(duplicate), isNull(), anyString());
        verify(kafkaTemplate, times(1)).send(eq(KafkaTopics.PAYMENT_SUCCEEDED), any());
    }

    // ---- saved cards -----------------------------------------------------------------------------

    @Test
    void aReusableCardIsSavedOnceAndListedWithoutItsCode() {
        String first = heldBooking();
        String second = heldBooking();
        String ref1 = paymentService.initiate(checkout(first), RIDER).gatewayReference();
        String ref2 = paymentService.initiate(checkout(second), RIDER).gatewayReference();

        paymentService.handleWebhookSuccess(ref1, first, FARE_KOBO, card("AUTH_1", "SIG_1", true));
        paymentService.handleWebhookSuccess(ref2, second, FARE_KOBO, card("AUTH_2", "SIG_1", true));

        var cards = savedCardService.list(RIDER);
        assertThat(cards).as("the same card paid with twice is one entry").hasSize(1);
        assertThat(cards.get(0).last4()).isEqualTo("4081");
        assertThat(cards.get(0).cardType()).isEqualTo("visa");
        assertThat(savedCardRepository.findAll().get(0).getAuthorizationCode()).as("refreshed to the newest").isEqualTo("AUTH_2");
    }

    @Test
    void aCardPaystackWontLetUsChargeAgainIsNotSaved() {
        String booking = heldBooking();
        String reference = paymentService.initiate(checkout(booking), RIDER).gatewayReference();

        paymentService.handleWebhookSuccess(reference, booking, FARE_KOBO, card("AUTH_1", "SIG_1", false));

        assertThat(savedCardService.list(RIDER)).isEmpty();
    }

    @Test
    void aSavedCardPaysOnTheSpotWithTheSameSplit() {
        String cardId = savedCard();
        String booking = heldBooking();
        when(gatewayClient.chargeAuthorization(anyString(), any(), anyString(), anyString(), anyMap(), any(), any()))
                .thenReturn(new PaymentGatewayClient.ChargeResult(true, null, "Approved"));

        InitiatePaymentResponse response = paymentService.initiate(pay(booking, null, cardId), RIDER);

        assertThat(response.status()).isEqualTo(PayoutStatus.PAID);
        assertThat(response.authorizationUrl()).isNull();
        verify(gatewayClient).chargeAuthorization(eq(response.gatewayReference()), eq(FARE), eq("card@example.com"),
                eq("AUTH_SAVED"), anyMap(), eq(SUBACCOUNT), isNull());
        verify(kafkaTemplate, times(1)).send(eq(KafkaTopics.PAYMENT_SUCCEEDED), any());

        // Paystack's own charge.success for the same charge arrives later.
        paymentService.handleWebhookSuccess(response.gatewayReference(), booking, FARE_KOBO, null);
        verify(kafkaTemplate, times(1)).send(eq(KafkaTopics.PAYMENT_SUCCEEDED), any());
    }

    @Test
    void aSavedCardTheBankDeclinesFallsBackToCheckout() {
        String cardId = savedCard();
        String booking = heldBooking();
        when(gatewayClient.chargeAuthorization(anyString(), any(), anyString(), anyString(), anyMap(), any(), any()))
                .thenReturn(new PaymentGatewayClient.ChargeResult(false, null, "Insufficient funds"));

        InitiatePaymentResponse response = paymentService.initiate(pay(booking, null, cardId), RIDER);

        assertThat(response.status()).isEqualTo(PayoutStatus.PENDING);
        assertThat(response.authorizationUrl()).startsWith("https://checkout/");
        verify(kafkaTemplate, never()).send(eq(KafkaTopics.PAYMENT_FAILED), any());
        assertThat(fareHoldRepository.findAll()).hasSize(1);
    }

    @Test
    void aSavedCardNeedingAnOtpSendsTheRiderToFinishIt() {
        String cardId = savedCard();
        String booking = heldBooking();
        when(gatewayClient.chargeAuthorization(anyString(), any(), anyString(), anyString(), anyMap(), any(), any()))
                .thenReturn(new PaymentGatewayClient.ChargeResult(false, "https://paystack/otp", "Pending"));

        InitiatePaymentResponse response = paymentService.initiate(pay(booking, null, cardId), RIDER);

        assertThat(response.status()).isEqualTo(PayoutStatus.PENDING);
        assertThat(response.authorizationUrl()).isEqualTo("https://paystack/otp");
        paymentService.handleWebhookSuccess(response.gatewayReference(), booking, FARE_KOBO, null);
        assertThat(paymentTransactionRepository.findByGatewayReference(response.gatewayReference()).orElseThrow().getStatus())
                .isEqualTo(PayoutStatus.PAID);
    }

    @Test
    void nobodyCanPayWithSomeoneElsesCard() {
        String cardId = savedCard();
        String booking = UUID.randomUUID().toString();
        when(tripServiceClient.fetchPaymentView(booking)).thenReturn(new TripServiceClient.BookingPaymentView(
                booking, "rider-2", DRIVER, BookingStatus.HELD, FARE, Instant.now().plusSeconds(240)));

        assertThatThrownBy(() -> paymentService.initiate(pay(booking, null, cardId), "rider-2"))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(gatewayClient, never()).chargeAuthorization(anyString(), any(), anyString(), anyString(), anyMap(), any(), any());
    }

    @Test
    void removingACardForgetsItAndDeactivatesItWithPaystack() {
        String cardId = savedCard();

        assertThatThrownBy(() -> savedCardService.remove("rider-2", cardId)).isInstanceOf(ResourceNotFoundException.class);
        savedCardService.remove(RIDER, cardId);

        assertThat(savedCardService.list(RIDER)).isEmpty();
        verify(gatewayClient).deactivateAuthorization("AUTH_SAVED");
    }

    // ---- refunds ---------------------------------------------------------------------------------

    @Test
    void aRefundGoesBackToTheCard() {
        String booking = heldBooking();
        String reference = paidBooking(booking);

        earningsService.refund(booking, "Booking cancelled by DRIVER");

        verify(gatewayClient).refund(eq(reference), eq(FARE), anyString());
        verify(kafkaTemplate).send(eq(KafkaTopics.REFUND_ISSUED), any());
    }

    @Test
    void aRefundPaystackRefusesIsRetriedNotLost() {
        String booking = heldBooking();
        paidBooking(booking);
        when(gatewayClient.refund(anyString(), any(), any())).thenReturn(false);

        assertThatThrownBy(() -> earningsService.refund(booking, "Booking cancelled by DRIVER")).isInstanceOf(IllegalStateException.class);
        assertThat(fareHoldRepository.findFirstByBookingIdOrderByCreatedAtDesc(booking).orElseThrow().isRefunded())
                .as("rolled back, so the redelivered cancellation tries again").isFalse();

        when(gatewayClient.refund(anyString(), any(), any())).thenReturn(true);
        earningsService.refund(booking, "Booking cancelled by DRIVER");
        assertThat(fareHoldRepository.findFirstByBookingIdOrderByCreatedAtDesc(booking).orElseThrow().isRefunded()).isTrue();
    }

    @Test
    void aCancellationBeforeTheChargeLandsIsRefundedOnceItDoes() {
        String booking = heldBooking();
        String reference = paymentService.initiate(checkout(booking), RIDER).gatewayReference();

        earningsService.refund(booking, "Booking cancelled by RIDER");
        verify(gatewayClient, never()).refund(anyString(), any(), any());

        paymentService.handleWebhookSuccess(reference, booking, FARE_KOBO, null);
        // trip-service sees a payment for a booking already gone and republishes BOOKING_CANCELLED:
        earningsService.refund(booking, "Booking cancelled by SYSTEM");

        verify(gatewayClient, times(1)).refund(eq(reference), eq(FARE), anyString());
    }

    @Test
    void concurrentCancellationEventsRefundOnce() throws Exception {
        String booking = heldBooking();
        paidBooking(booking);

        runConcurrently(5, () -> {
            earningsService.refund(booking, "Booking cancelled by DRIVER");
            return null;
        }).forEach(this::await);

        verify(gatewayClient, times(1)).refund(anyString(), any(), any());
    }

    @Test
    void aRefundedBookingNeverAlsoCountsAsDriverEarnings() {
        String booking = heldBooking();
        paidBooking(booking);

        earningsService.refund(booking, "Booking cancelled by RIDER");
        earningsService.onTripCompleted(tripCompleted(booking));

        assertThat(earningsTransactionRepository.findAll()).isEmpty();
    }

    @Test
    void aLateCancellationRefundsSeventyFivePercentAndRecordsTheFeeForTheDriver() {
        String booking = heldBooking();
        String reference = paidBooking(booking);

        earningsService.refund(booking, "Booking cancelled by RIDER", new BigDecimal("300.00"), "route-1");
        earningsService.refund(booking, "Booking cancelled by RIDER", new BigDecimal("300.00"), "route-1");

        verify(gatewayClient, times(1)).refund(eq(reference), eq(new BigDecimal("900.00")), anyString());
        assertThat(earningsTransactionRepository.findAll()).singleElement()
                .satisfies(t -> {
                    assertThat(t.getGrossFare()).isEqualByComparingTo("300.00");
                    assertThat(t.getNetCredit()).isEqualByComparingTo("285.00");
                });
    }

    // ---- driver earnings -------------------------------------------------------------------------

    @Test
    void aCompletedTripIsRecordedOnceWithItsCommission() {
        String booking = heldBooking();
        paidBooking(booking);

        earningsService.onTripCompleted(tripCompleted(booking));
        earningsService.onTripCompleted(tripCompleted(booking));

        var account = earningsAccountRepository.findByDriverId(DRIVER).orElseThrow();
        assertThat(account.getLifetimeGross()).isEqualByComparingTo(FARE);
        assertThat(account.getLifetimeCommission()).isEqualByComparingTo("60.00");
        assertThat(earningsTransactionRepository.findAll()).hasSize(1);
    }

    @Test
    void anUnpaidBookingNeverCountsAsEarnings() {
        String booking = heldBooking();
        paymentService.initiate(checkout(booking), RIDER);

        earningsService.onTripCompleted(tripCompleted(booking));

        assertThat(earningsAccountRepository.findByDriverId(DRIVER)).isEmpty();
    }

    @Test
    void theTripListNamesTheRiderAndPagesByPeriod() {
        String booking = heldBooking();
        paidBooking(booking);
        earningsService.onTripCompleted(tripCompleted(booking));
        when(profileServiceClient.fetchRiderSummaries(anyList()))
                .thenReturn(Map.of(RIDER, new ProfileServiceClient.RiderSummaryItem(RIDER, "Ada", "Obi", "https://img/ada.jpg")));

        var page = earningsService.getTrips(DRIVER, EarningsPeriod.TODAY, null, PageRequest.of(0, 20), DRIVER, UserRole.DRIVER);

        assertThat(page.content()).singleElement().satisfies(item -> {
            assertThat(item.netCredit()).isEqualByComparingTo("1140.00");
            assertThat(item.rider().firstName()).isEqualTo("Ada");
            assertThat(item.routePostId()).isEqualTo("route-1");
        });
    }

    @Test
    void earningsAreOnlyVisibleToTheDriverAndOps() {
        assertThatThrownBy(() -> earningsService.getSummary(DRIVER, "driver-2", UserRole.DRIVER)).isInstanceOf(UnauthorizedException.class);
        assertThatThrownBy(() -> earningsService.getPayouts(DRIVER, PageRequest.of(0, 20), RIDER, UserRole.RIDER))
                .isInstanceOf(UnauthorizedException.class);
        assertThat(earningsService.getSummary(DRIVER, "ops-1", UserRole.OPS_ADMIN)).isNotNull();
    }

    // ---- payouts to the driver's bank (Paystack settlements) -------------------------------------

    @Test
    void settlementsAreMirroredAsPaidAndInProgress() {
        Instant monday = Instant.now().minus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        when(gatewayClient.fetchSubaccountId(SUBACCOUNT)).thenReturn("777");
        when(gatewayClient.listSettlements(eq("777"), any(), anyInt(), anyInt())).thenReturn(List.of(
                new PaystackGatewayClient.Settlement("S1", "success", 100000L, 98000L, monday.toString()),
                new PaystackGatewayClient.Settlement("S2", "pending", 50000L, null, null)));

        settlementSyncService.syncAll();
        settlementSyncService.syncAll();

        var summary = earningsService.getSummary(DRIVER, DRIVER, UserRole.DRIVER);
        assertThat(summary.paidToBank()).as("what actually reached the bank").isEqualByComparingTo("980.00");
        assertThat(summary.payoutInProgress()).isEqualByComparingTo("500.00");
        assertThat(summary.lastPayoutAt()).isEqualTo(monday);
        var payouts = earningsService.getPayouts(DRIVER, PageRequest.of(0, 20), DRIVER, UserRole.DRIVER);
        assertThat(payouts.content()).as("a resync updates rather than duplicates").hasSize(2);
        assertThat(payouts.content()).extracting(p -> p.status())
                .as("one still on its way (no date yet, listed first), then the one paid")
                .containsExactly(SettlementStatus.PENDING, SettlementStatus.SUCCESS);
        assertThat(payoutRecipientRepository.findAll().get(0).getGatewaySubaccountId()).isEqualTo("777");
    }

    @Test
    void aSettlementThatCompletesIsUpdatedInPlace() {
        when(gatewayClient.fetchSubaccountId(SUBACCOUNT)).thenReturn("777");
        when(gatewayClient.listSettlements(eq("777"), any(), anyInt(), anyInt())).thenReturn(List.of(
                new PaystackGatewayClient.Settlement("S2", "pending", 50000L, null, null)));
        settlementSyncService.syncAll();
        when(gatewayClient.listSettlements(eq("777"), any(), anyInt(), anyInt())).thenReturn(List.of(
                new PaystackGatewayClient.Settlement("S2", "success", 50000L, 50000L, Instant.now().toString())));

        settlementSyncService.syncAll();

        var summary = earningsService.getSummary(DRIVER, DRIVER, UserRole.DRIVER);
        assertThat(summary.payoutInProgress()).isEqualByComparingTo("0.00");
        assertThat(summary.paidToBank()).isEqualByComparingTo("500.00");
    }

    @Test
    void oneDriverPaystackCannotListDoesNotStopTheRest() {
        payoutRecipientRepository.save(recipient("driver-2", "ACCT_other", "888"));
        when(gatewayClient.fetchSubaccountId(SUBACCOUNT)).thenThrow(new RuntimeException("Paystack down"));
        when(gatewayClient.listSettlements(eq("888"), any(), anyInt(), anyInt())).thenReturn(List.of(
                new PaystackGatewayClient.Settlement("S9", "success", 10000L, 10000L, Instant.now().toString())));

        settlementSyncService.syncAll();

        assertThat(earningsService.getSummary("driver-2", "driver-2", UserRole.DRIVER).paidToBank()).isEqualByComparingTo("100.00");
    }

    @Test
    void earnedSinceLastPayoutCountsOnlyTripsAfterIt() {
        String booking = heldBooking();
        paidBooking(booking);
        earningsService.onTripCompleted(tripCompleted(booking));
        when(gatewayClient.fetchSubaccountId(SUBACCOUNT)).thenReturn("777");
        when(gatewayClient.listSettlements(eq("777"), any(), anyInt(), anyInt())).thenReturn(List.of(
                new PaystackGatewayClient.Settlement("S1", "success", 114000L, 114000L, Instant.now().plusSeconds(60).toString())));

        assertThat(earningsService.getSummary(DRIVER, DRIVER, UserRole.DRIVER).earnedSinceLastPayout()).isEqualByComparingTo("1140.00");
        settlementSyncService.syncAll();
        assertThat(earningsService.getSummary(DRIVER, DRIVER, UserRole.DRIVER).earnedSinceLastPayout()).isEqualByComparingTo("0.00");
    }

    // ---- money a driver owes back ----------------------------------------------------------------

    @Test
    void aDriverInDebtHasOnlyWhatTheyOweTakenFromAFare() {
        earningsService.recordSettlementDebt(DRIVER, new BigDecimal("300.00"), "refund after settlement", UserRole.OPS_ADMIN);
        String booking = heldBooking();

        paymentService.initiate(checkout(booking), RIDER);

        ArgumentCaptor<Long> flatCharge = ArgumentCaptor.forClass(Long.class);
        verify(gatewayClient).initiateSplit(anyString(), any(), anyString(), any(), anyMap(), eq(SUBACCOUNT), flatCharge.capture());
        assertThat(flatCharge.getValue()).as("our 60 commission plus the 300 owed, in kobo").isEqualTo(36000L);
        assertThat(fareHoldRepository.findFirstByBookingIdOrderByCreatedAtDesc(booking).orElseThrow().getDebtRecovery())
                .isEqualByComparingTo("300.00");
    }

    @Test
    void aDebtBiggerThanTheFareTakesAtMostTheDriversShare() {
        earningsService.recordSettlementDebt(DRIVER, new BigDecimal("5000.00"), "refund after settlement", UserRole.OPS_ADMIN);
        String booking = heldBooking();

        paymentService.initiate(checkout(booking), RIDER);

        verify(gatewayClient).initiateSplit(anyString(), any(), anyString(), any(), anyMap(), eq(SUBACCOUNT), eq(FARE_KOBO));
        assertThat(fareHoldRepository.findFirstByBookingIdOrderByCreatedAtDesc(booking).orElseThrow().getDebtRecovery())
                .isEqualByComparingTo("1140.00");
    }

    @Test
    void twoFaresNeverRecoverTheSameDebt() {
        earningsService.recordSettlementDebt(DRIVER, new BigDecimal("300.00"), "refund after settlement", UserRole.OPS_ADMIN);
        String first = heldBooking();
        paidBooking(first);
        clearInvocations(gatewayClient);
        String second = heldBooking();

        paymentService.initiate(checkout(second), RIDER);

        verify(gatewayClient).initiateSplit(anyString(), any(), anyString(), any(), anyMap(), eq(SUBACCOUNT), isNull());
    }

    @Test
    void aCompletedTripPaysTheDebtDownByWhatItTook() {
        earningsService.recordSettlementDebt(DRIVER, new BigDecimal("300.00"), "refund after settlement", UserRole.OPS_ADMIN);
        String booking = heldBooking();
        paidBooking(booking);

        earningsService.onTripCompleted(tripCompleted(booking));

        assertThat(earningsAccountRepository.findByDriverId(DRIVER).orElseThrow().getSettlementDebt()).isEqualByComparingTo("0.00");
        assertThat(earningsService.getSummary(DRIVER, DRIVER, UserRole.DRIVER).settlementDebt()).isEqualByComparingTo("0.00");
    }

    @Test
    void onlyOpsRecordADebtAndAlwaysWithAnAmount() {
        assertThatThrownBy(() -> earningsService.recordSettlementDebt(DRIVER, BigDecimal.TEN, "x", UserRole.DRIVER))
                .isInstanceOf(UnauthorizedException.class);
        assertThatThrownBy(() -> earningsService.recordSettlementDebt(DRIVER, BigDecimal.ZERO, "x", UserRole.OPS_ADMIN))
                .isInstanceOf(BadRequestException.class);
    }

    // ---- subaccount backfill ---------------------------------------------------------------------

    @Test
    void driversWithoutASubaccountGetOneOnStartupAndOneFailureDoesNotStopTheRest() {
        payoutRecipientRepository.deleteAll();
        payoutRecipientRepository.save(recipient("bad-details", null, null));
        payoutRecipientRepository.save(recipient(DRIVER, null, null));
        payoutRecipientRepository.findAll().stream().filter(r -> r.getDriverId().equals("bad-details"))
                .forEach(r -> {
                    r.setAccountNumber("0000000000");
                    payoutRecipientRepository.save(r);
                });
        when(gatewayClient.createSubaccount(anyString(), eq("0000000000"), anyString(), any()))
                .thenThrow(new BadRequestException("Could not register that bank account"));
        when(gatewayClient.createSubaccount(anyString(), eq("0123456789"), anyString(), any()))
                .thenReturn(new PaystackGatewayClient.SubaccountResult(SUBACCOUNT, "777"));

        subaccountBackfill.run(null);

        assertThat(payoutRecipientRepository.findFirstByDriverIdAndIsDefaultTrueAndGatewaySubaccountCodeIsNotNull(DRIVER))
                .get().extracting(PayoutRecipient::getGatewaySubaccountId).isEqualTo("777");
        assertThat(payoutRecipientRepository.findFirstByDriverIdAndIsDefaultTrueAndGatewaySubaccountCodeIsNotNull("bad-details")).isEmpty();
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private void givenDriverHasASubaccount() {
        payoutRecipientRepository.save(recipient(DRIVER, SUBACCOUNT, null));
    }

    private static PayoutRecipient recipient(String driverId, String subaccountCode, String subaccountId) {
        return PayoutRecipient.builder()
                .driverId(driverId).bankName("Test Bank").bankCode("058").accountNumber("0123456789")
                .accountName("Test Driver").gatewayRecipientCode("RCP_test")
                .gatewaySubaccountCode(subaccountCode).gatewaySubaccountId(subaccountId).isDefault(true)
                .build();
    }

    private String heldBooking() {
        String booking = UUID.randomUUID().toString();
        stubBooking(booking, BookingStatus.HELD);
        return booking;
    }

    private void stubBooking(String bookingId, BookingStatus status) {
        when(tripServiceClient.fetchPaymentView(bookingId)).thenReturn(new TripServiceClient.BookingPaymentView(
                bookingId, RIDER, DRIVER, status, FARE, Instant.now().plusSeconds(240)));
    }

    private String paidBooking(String bookingId) {
        String reference = paymentService.initiate(checkout(bookingId), RIDER).gatewayReference();
        paymentService.handleWebhookSuccess(reference, bookingId, FARE_KOBO, null);
        return reference;
    }

    private String savedCard() {
        savedCardService.remember(RIDER, new CardAuthorization("AUTH_SAVED", "SIG_SAVED", "card", true,
                "visa ", "4081", "12", "2030", "Test Bank", "card@example.com"));
        return savedCardRepository.findByRiderIdOrderByCreatedAtDesc(RIDER).get(0).getId();
    }

    private static CardAuthorization card(String code, String signature, boolean reusable) {
        return new CardAuthorization(code, signature, "card", reusable, "visa ", "4081", "12", "2030", "Test Bank", "rider@example.com");
    }

    private static InitiatePaymentRequest checkout(String bookingId) {
        return pay(bookingId, null, null);
    }

    private static InitiatePaymentRequest pay(String bookingId, PaymentMethod method, String savedCardId) {
        return new InitiatePaymentRequest(bookingId, null, null, null, null, method, null, savedCardId);
    }

    private static TripCompletedEvent tripCompleted(String bookingId) {
        return TripCompletedEvent.builder().bookingId(bookingId).routePostId("route-1").riderId(RIDER)
                .driverId(DRIVER).fareTotal(FARE).completedAt(LocalDateTime.now()).build();
    }

    private <T> List<Future<T>> runConcurrently(int threads, Callable<T> task) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                go.await();
                return task.call();
            }));
        }
        go.countDown();
        pool.shutdown();
        return futures;
    }

    private void await(Future<?> future) {
        try {
            future.get();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
