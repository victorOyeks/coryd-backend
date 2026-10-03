package org.coryd.payment.service;

import org.coryd.common.enums.BookingStatus;
import org.coryd.common.enums.PaymentMethod;
import org.coryd.common.enums.PayoutStatus;
import org.coryd.common.exception.BadRequestException;
import org.coryd.common.exception.UnauthorizedException;
import org.coryd.common.kafka.KafkaTopics;
import org.coryd.common.kafka.events.TripCompletedEvent;
import org.coryd.payment.client.ProfileServiceClient;
import org.coryd.payment.client.TripServiceClient;
import org.coryd.payment.config.CommissionProperties;
import org.coryd.payment.dto.InitiatePaymentRequest;
import org.coryd.payment.dto.InitiatePaymentResponse;
import org.coryd.payment.entity.DriverEarningsAccount;
import org.coryd.payment.entity.PaymentTransaction;
import org.coryd.payment.entity.PayoutRecipient;
import org.coryd.payment.gateway.PaymentGatewayClient;
import org.coryd.payment.gateway.PaystackGatewayClient;
import org.coryd.payment.repository.DriverEarningsAccountRepository;
import org.coryd.payment.repository.EarningsTransactionRepository;
import org.coryd.payment.repository.FareHoldRepository;
import org.coryd.payment.repository.PaymentTransactionRepository;
import org.coryd.payment.repository.PayoutRecipientRepository;
import org.coryd.payment.repository.PayoutRepository;
import org.coryd.payment.repository.WalletFeedRepository;
import org.coryd.payment.repository.WalletRepository;
import org.coryd.payment.repository.WalletTransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Payment, refund and payout money paths against a throwaway Postgres container built from
 * payment-service's own Liquibase migrations - never a shared or real database. Not transactional
 * per test, so the concurrency cases commit for real across threads.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
@Import({PaymentService.class, WalletService.class, EarningsService.class, PayoutService.class,
        CommissionProperties.class, DriverSplitResolver.class})
class PaymentFlowIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String RIDER = "rider-1";
    private static final String DRIVER = "driver-1";
    private static final BigDecimal FARE = new BigDecimal("1200.00");

    @Autowired PaymentService paymentService;
    @Autowired WalletService walletService;
    @Autowired EarningsService earningsService;
    @Autowired PayoutService payoutService;
    @Autowired PaymentTransactionRepository paymentTransactionRepository;
    @Autowired FareHoldRepository fareHoldRepository;
    @Autowired WalletRepository walletRepository;
    @Autowired WalletTransactionRepository walletTransactionRepository;
    @Autowired DriverEarningsAccountRepository earningsAccountRepository;
    @Autowired EarningsTransactionRepository earningsTransactionRepository;
    @Autowired PayoutRepository payoutRepository;
    @Autowired PayoutRecipientRepository payoutRecipientRepository;
    @Autowired DataSource dataSource;

    @MockBean KafkaTemplate<String, Object> kafkaTemplate;
    @MockBean TripServiceClient tripServiceClient;
    @MockBean PaystackGatewayClient gatewayClient;
    @MockBean CheckoutEmailResolver checkoutEmailResolver;
    @MockBean ProfileServiceClient profileServiceClient;
    @MockBean WalletFeedRepository walletFeedRepository;

    @BeforeEach
    void cleanSlate() throws Exception {
        try (Connection c = dataSource.getConnection()) {
            assertThat(c.getMetaData().getURL())
                    .as("tests must only ever run against the Testcontainers database")
                    .isEqualTo(postgres.getJdbcUrl());
        }
        payoutRepository.deleteAll();
        payoutRecipientRepository.deleteAll();
        earningsTransactionRepository.deleteAll();
        earningsAccountRepository.deleteAll();
        walletTransactionRepository.deleteAll();
        walletRepository.deleteAll();
        fareHoldRepository.deleteAll();
        paymentTransactionRepository.deleteAll();
        reset(gatewayClient, tripServiceClient);
        clearInvocations(kafkaTemplate);
        when(checkoutEmailResolver.resolve(any(), any())).thenReturn("rider@example.com");
        when(gatewayClient.initiate(anyString(), any(), anyString(), any(), anyMap()))
                .thenAnswer(inv -> new PaymentGatewayClient.GatewayInitResult("https://checkout/" + inv.getArgument(0), inv.getArgument(0)));
        when(gatewayClient.initiateSplit(anyString(), any(), anyString(), any(), anyMap(), any(), any()))
                .thenAnswer(inv -> new PaymentGatewayClient.GatewayInitResult("https://checkout/" + inv.getArgument(0), inv.getArgument(0)));
        when(gatewayClient.refund(anyString(), any(), any())).thenReturn(true);
    }

    // ---- the client can't set its own price, payer or payee --------------------------------------

    @Test
    void walletPaymentChargesTheBookingFareNotWhatTheClientSent() {
        String booking = heldBooking();
        fundWallet(RIDER, "5000.00");

        paymentService.initiate(new InitiatePaymentRequest(booking, "someone-else", "other-driver",
                new BigDecimal("1.00"), null, PaymentMethod.WALLET, null), RIDER);

        assertThat(walletService.getBalance(RIDER)).isEqualByComparingTo("3800.00");
        var hold = fareHoldRepository.findFirstByBookingIdOrderByCreatedAtDesc(booking).orElseThrow();
        assertThat(hold.getAmount()).isEqualByComparingTo(FARE);
        assertThat(hold.getDriverId()).isEqualTo(DRIVER);
    }

    @Test
    void onlyTheBookingsRiderCanPayForIt() {
        String booking = heldBooking();
        fundWallet("attacker", "5000.00");
        fundWallet(RIDER, "5000.00");

        assertThatThrownBy(() -> paymentService.initiate(walletPay(booking), "attacker")).isInstanceOf(UnauthorizedException.class);
        assertThat(walletService.getBalance(RIDER)).isEqualByComparingTo("5000.00");
    }

    @Test
    void aBookingNoLongerHeldCannotBePaid() {
        String booking = UUID.randomUUID().toString();
        stubBooking(booking, BookingStatus.CANCELLED_RIDER);
        fundWallet(RIDER, "5000.00");

        assertThatThrownBy(() -> paymentService.initiate(walletPay(booking), RIDER)).isInstanceOf(BadRequestException.class);
        assertThat(walletService.getBalance(RIDER)).isEqualByComparingTo("5000.00");
    }

    @Test
    void walletDoubleTapChargesOnce() throws Exception {
        String booking = heldBooking();
        fundWallet(RIDER, "5000.00");

        int succeeded = 0;
        for (Future<InitiatePaymentResponse> f : runConcurrently(5, () -> paymentService.initiate(walletPay(booking), RIDER))) {
            try {
                f.get();
                succeeded++;
            } catch (Exception ignored) {
                // "already paid" for the rest
            }
        }

        assertThat(succeeded).isEqualTo(1);
        assertThat(walletService.getBalance(RIDER)).isEqualByComparingTo("3800.00");
    }

    // ---- Paystack retries and webhook replays ----------------------------------------------------

    @Test
    void retriedPaystackCheckoutReusesTheOpenOne() {
        String booking = heldBooking();

        InitiatePaymentResponse first = paymentService.initiate(paystackPay(booking), RIDER);
        InitiatePaymentResponse retry = paymentService.initiate(paystackPay(booking), RIDER);

        assertThat(retry.gatewayReference()).isEqualTo(first.gatewayReference());
        assertThat(retry.authorizationUrl()).isEqualTo(first.authorizationUrl());
        verify(gatewayClient, times(1)).initiateSplit(anyString(), any(), anyString(), any(), anyMap(), any(), any());
    }

    @Test
    void replayedChargeSuccessConfirmsOnce() {
        String booking = heldBooking();
        String reference = paymentService.initiate(paystackPay(booking), RIDER).gatewayReference();

        paymentService.handleWebhookSuccess(reference, booking, 120000L);
        paymentService.handleWebhookSuccess(reference, booking, 120000L);

        verify(kafkaTemplate, times(1)).send(eq(KafkaTopics.PAYMENT_SUCCEEDED), any());
    }

    @Test
    void replayedTopUpWebhookCreditsTheWalletOnce() {
        String reference = walletService.initiateTopUp(RIDER, new BigDecimal("2000.00"), null, null).gatewayReference();

        paymentService.handleWebhookSuccess(reference, null, 200000L);
        paymentService.handleWebhookSuccess(reference, null, 200000L);
        paymentService.handleWebhookSuccess(reference, null, 200000L);

        assertThat(walletService.getBalance(RIDER)).isEqualByComparingTo("2000.00");
    }

    @Test
    void lateChargeFailedAfterSuccessDoesNotReleaseTheBooking() {
        String booking = heldBooking();
        String reference = paymentService.initiate(paystackPay(booking), RIDER).gatewayReference();
        paymentService.handleWebhookSuccess(reference, booking, 120000L);

        paymentService.handleWebhookFailure(reference, "Declined");

        verify(kafkaTemplate, never()).send(eq(KafkaTopics.PAYMENT_FAILED), any());
        assertThat(paymentTransactionRepository.findByGatewayReference(reference).orElseThrow().getStatus()).isEqualTo(PayoutStatus.PAID);
    }

    @Test
    void oldCheckoutPaidAfterWalletPaymentIsCreditedBackOnce() {
        String booking = heldBooking();
        String paystackReference = paymentService.initiate(paystackPay(booking), RIDER).gatewayReference();
        fundWallet(RIDER, "1200.00");
        paymentService.initiate(walletPay(booking), RIDER);

        // Rider also finishes the Paystack checkout they opened first - Paystack then resends the webhook.
        paymentService.handleWebhookSuccess(paystackReference, booking, 120000L);
        paymentService.handleWebhookSuccess(paystackReference, booking, 120000L);

        assertThat(walletService.getBalance(RIDER)).isEqualByComparingTo("1200.00");
    }

    @Test
    void chargeOnASupersededReferencePaysAStillUnpaidBooking() {
        String booking = heldBooking();
        paymentService.initiate(paystackPay(booking), RIDER);
        String transactionId = currentTxnId(booking);
        String superseded = "older-checkout-" + UUID.randomUUID();

        paymentService.handleWebhookSuccess(superseded, booking, 120000L);

        PaymentTransaction current = paymentTransactionRepository.findById(transactionId).orElseThrow();
        assertThat(current.getStatus()).isEqualTo(PayoutStatus.PAID);
        assertThat(current.getGatewayReference()).isEqualTo(superseded);
        verify(kafkaTemplate, times(1)).send(eq(KafkaTopics.PAYMENT_SUCCEEDED), any());
    }

    // ---- refunds ---------------------------------------------------------------------------------

    @Test
    void cancellationBeforeThePaystackChargeLandsStillRefundsLater() {
        String booking = heldBooking();
        String reference = paymentService.initiate(paystackPay(booking), RIDER).gatewayReference();

        earningsService.refund(booking, "Booking cancelled by RIDER");
        paymentService.handleWebhookSuccess(reference, booking, 120000L);
        // trip-service sees a payment for a booking already gone and republishes BOOKING_CANCELLED:
        earningsService.refund(booking, "Booking cancelled by SYSTEM");

        assertThat(walletService.getBalance(RIDER)).isEqualByComparingTo(FARE);
    }

    @Test
    void concurrentCancellationEventsRefundOnce() throws Exception {
        String booking = heldBooking();
        fundWallet(RIDER, "1200.00");
        paymentService.initiate(walletPay(booking), RIDER);

        runConcurrently(5, () -> {
            earningsService.refund(booking, "Booking cancelled by DRIVER");
            return null;
        }).forEach(this::await);

        assertThat(walletService.getBalance(RIDER)).isEqualByComparingTo(FARE);
    }

    @Test
    void refundedBookingNeverAlsoPaysTheDriver() {
        String booking = heldBooking();
        fundWallet(RIDER, "1200.00");
        paymentService.initiate(walletPay(booking), RIDER);

        earningsService.refund(booking, "Booking cancelled by RIDER");
        earningsService.onTripCompleted(tripCompleted(booking));

        assertThat(earningsAccountRepository.findByDriverId(DRIVER)).isEmpty();
    }

    @Test
    void lateCancellationRefundsSeventyFivePercentAndPaysTheFeeToTheDriver() {
        String booking = heldBooking();
        fundWallet(RIDER, "1200.00");
        paymentService.initiate(walletPay(booking), RIDER);

        earningsService.refund(booking, "Booking cancelled by RIDER", new BigDecimal("300.00"), "route-1");
        earningsService.refund(booking, "Booking cancelled by RIDER", new BigDecimal("300.00"), "route-1");
        earningsService.onTripCompleted(tripCompleted(booking));

        assertThat(walletService.getBalance(RIDER)).isEqualByComparingTo("900.00");
        DriverEarningsAccount account = earningsAccountRepository.findByDriverId(DRIVER).orElseThrow();
        assertThat(account.getAvailableBalance()).isEqualByComparingTo("285.00");
        assertThat(account.getLifetimeCommission()).isEqualByComparingTo("15.00");
    }

    // ---- driver earnings -------------------------------------------------------------------------

    @Test
    void replayedTripCompletedCreditsTheDriverOnce() {
        String booking = heldBooking();
        fundWallet(RIDER, "1200.00");
        paymentService.initiate(walletPay(booking), RIDER);

        earningsService.onTripCompleted(tripCompleted(booking));
        earningsService.onTripCompleted(tripCompleted(booking));

        assertThat(earningsAccountRepository.findByDriverId(DRIVER).orElseThrow().getAvailableBalance())
                .isEqualByComparingTo("1140.00");
    }

    @Test
    void unpaidPaystackBookingNeverCreditsTheDriver() {
        String booking = heldBooking();
        paymentService.initiate(paystackPay(booking), RIDER);

        earningsService.onTripCompleted(tripCompleted(booking));

        assertThat(earningsAccountRepository.findByDriverId(DRIVER)).isEmpty();
    }

    @Test
    void tripCreditRacingAWithdrawalLosesNeitherSide() throws Exception {
        earningsAccountRepository.save(DriverEarningsAccount.builder().driverId(DRIVER).availableBalance(new BigDecimal("1000.00")).build());
        payoutRecipientRepository.save(PayoutRecipient.builder().driverId(DRIVER).bankName("Bank").bankCode("001")
                .accountNumber("0123456789").accountName("Driver One").gatewayRecipientCode("RCP_1").isDefault(true).build());
        when(gatewayClient.initiateTransfer(anyString(), any(), anyString(), anyString()))
                .thenReturn(new PaystackGatewayClient.TransferResult("TRF_1", "pending"));

        for (int i = 0; i < 5; i++) {
            String booking = heldBooking();
            fundWallet(RIDER, "1200.00");
            paymentService.initiate(walletPay(booking), RIDER);

            List<Callable<Object>> both = List.of(
                    () -> payoutService.requestWithdrawal(DRIVER, DRIVER, new BigDecimal("100.00")),
                    () -> {
                        earningsService.onTripCompleted(tripCompleted(booking));
                        return null;
                    });
            runAll(both).forEach(this::await);
        }

        // 1000 + 5 x 1140 net - 5 x 100 withdrawn
        assertThat(earningsAccountRepository.findByDriverId(DRIVER).orElseThrow().getAvailableBalance())
                .isEqualByComparingTo("6200.00");
    }

    @Test
    void topUpAndDebitRacingKeepTheWalletExact() throws Exception {
        fundWallet(RIDER, "5000.00");
        for (int i = 0; i < 5; i++) {
            String booking = heldBooking();
            String topUpRef = walletService.initiateTopUp(RIDER, new BigDecimal("300.00"), null, null).gatewayReference();

            runAll(List.<Callable<Object>>of(
                    () -> paymentService.initiate(walletPay(booking), RIDER),
                    () -> {
                        paymentService.handleWebhookSuccess(topUpRef, null, 30000L);
                        return null;
                    })).forEach(this::await);
        }

        // 5000 + 5 x 300 - 5 x 1200
        assertThat(walletService.getBalance(RIDER)).isEqualByComparingTo("500.00");
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private String heldBooking() {
        String booking = UUID.randomUUID().toString();
        stubBooking(booking, BookingStatus.HELD);
        return booking;
    }

    private void stubBooking(String bookingId, BookingStatus status) {
        when(tripServiceClient.fetchPaymentView(bookingId)).thenReturn(new TripServiceClient.BookingPaymentView(
                bookingId, RIDER, DRIVER, status, FARE, Instant.now().plusSeconds(240)));
    }

    private String currentTxnId(String bookingId) {
        return paymentTransactionRepository.findFirstByBookingIdOrderByCreatedAtDesc(bookingId).orElseThrow().getId();
    }

    private void fundWallet(String userId, String amount) {
        walletService.creditRefund(userId, null, new BigDecimal(amount), "test funding " + UUID.randomUUID());
    }

    private static InitiatePaymentRequest walletPay(String bookingId) {
        return new InitiatePaymentRequest(bookingId, null, null, null, null, PaymentMethod.WALLET, null);
    }

    private static InitiatePaymentRequest paystackPay(String bookingId) {
        return new InitiatePaymentRequest(bookingId, null, null, null, null, PaymentMethod.PAYSTACK, null);
    }

    private static TripCompletedEvent tripCompleted(String bookingId) {
        return TripCompletedEvent.builder().bookingId(bookingId).routePostId("route-1").riderId(RIDER)
                .driverId(DRIVER).fareTotal(FARE).completedAt(LocalDateTime.now()).build();
    }

    private <T> List<Future<T>> runConcurrently(int threads, Callable<T> task) throws InterruptedException {
        List<Callable<T>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(task);
        }
        return runAll(tasks);
    }

    private <T> List<Future<T>> runAll(List<Callable<T>> tasks) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch go = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        for (Callable<T> task : tasks) {
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
