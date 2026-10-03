package org.coryd.payment.service;

import org.coryd.common.enums.BookingStatus;
import org.coryd.common.enums.PaymentMethod;
import org.coryd.common.exception.BadRequestException;
import org.coryd.common.kafka.events.TripCompletedEvent;
import org.coryd.payment.client.ProfileServiceClient;
import org.coryd.payment.client.TripServiceClient;
import org.coryd.payment.config.CommissionProperties;
import org.coryd.payment.dto.InitiatePaymentRequest;
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
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The money paths with split payments on, where the driver's share goes straight to their own
 * Paystack subaccount and never lands in ours. PaymentFlowIntegrationTest covers the same service
 * with it off, so the pair together prove the switch does not change anything it should not.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
@TestPropertySource(properties = "payment.split.enabled=true")
@Import({PaymentService.class, WalletService.class, EarningsService.class, PayoutService.class,
        CommissionProperties.class, DriverSplitResolver.class, SubaccountBackfill.class})
class SplitPaymentIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String RIDER = "rider-1";
    private static final String DRIVER = "driver-1";
    private static final String SUBACCOUNT = "ACCT_test123";
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
    @Autowired SubaccountBackfill subaccountBackfill;

    @MockBean KafkaTemplate<String, Object> kafkaTemplate;
    @MockBean TripServiceClient tripServiceClient;
    @MockBean PaystackGatewayClient gatewayClient;
    @MockBean CheckoutEmailResolver checkoutEmailResolver;
    @MockBean ProfileServiceClient profileServiceClient;
    @MockBean WalletFeedRepository walletFeedRepository;

    @BeforeEach
    void cleanSlate() {
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
        when(gatewayClient.initiateSplit(anyString(), any(), anyString(), any(), anyMap(), any(), any()))
                .thenAnswer(inv -> new PaymentGatewayClient.GatewayInitResult("https://checkout/" + inv.getArgument(0), inv.getArgument(0)));
        when(gatewayClient.refund(anyString(), any(), any())).thenReturn(true);
    }

    // ---- the driver's share never reaches our balance --------------------------------------------

    @Test
    void theFareIsSplitToTheDriversOwnSubaccount() {
        givenDriverHasASubaccount();
        String booking = heldBooking();

        paymentService.initiate(paystackPay(booking), RIDER);

        ArgumentCaptor<String> subaccount = ArgumentCaptor.forClass(String.class);
        verify(gatewayClient).initiateSplit(anyString(), eq(FARE), anyString(), any(), anyMap(),
                subaccount.capture(), any());
        assertThat(subaccount.getValue()).isEqualTo(SUBACCOUNT);
    }

    @Test
    void aDriverWithoutASubaccountCannotBeBooked() {
        String booking = heldBooking();

        assertThatThrownBy(() -> paymentService.initiate(paystackPay(booking), RIDER))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("not finished setting up payouts");

        verify(gatewayClient, never()).initiateSplit(anyString(), any(), anyString(), any(), anyMap(), any(), any());
    }

    @Test
    void completingATripRecordsEarningsWithoutHoldingAnyMoney() {
        givenDriverHasASubaccount();
        String booking = heldBooking();
        String reference = paymentService.initiate(paystackPay(booking), RIDER).gatewayReference();
        paymentService.handleWebhookSuccess(reference, booking, 120000L);

        earningsService.onTripCompleted(tripCompleted(booking));

        var account = earningsAccountRepository.findByDriverId(DRIVER).orElseThrow();
        assertThat(account.getAvailableBalance())
                .as("the driver was paid by Paystack, so there is nothing for us to hold")
                .isEqualByComparingTo("0.00");
        assertThat(account.getLifetimeGross()).isEqualByComparingTo(FARE);
        assertThat(account.getLifetimeCommission()).isEqualByComparingTo("60.00");
        assertThat(earningsTransactionRepository.findByBookingIdIn(java.util.List.of(booking)))
                .as("the statement row is still written")
                .hasSize(1);
    }

    @Test
    void aWalletFundedTripStillOwesTheDriverBecauseItWasNeverSplit() {
        givenDriverHasASubaccount();
        String booking = heldBooking();
        fundWallet(RIDER, "1200.00");
        paymentService.initiate(walletPay(booking), RIDER);

        earningsService.onTripCompleted(tripCompleted(booking));

        assertThat(earningsAccountRepository.findByDriverId(DRIVER).orElseThrow().getAvailableBalance())
                .as("the gateway never saw this fare, so we are still holding the driver's share")
                .isEqualByComparingTo("1140.00");
    }

    @Test
    void walletFundedEarningsCanStillBeWithdrawn() {
        givenDriverHasASubaccount();
        String booking = heldBooking();
        fundWallet(RIDER, "1200.00");
        paymentService.initiate(walletPay(booking), RIDER);
        earningsService.onTripCompleted(tripCompleted(booking));
        when(gatewayClient.initiateTransfer(anyString(), any(), anyString(), anyString()))
                .thenReturn(new PaystackGatewayClient.TransferResult("TRF_1", "pending"));

        payoutService.requestWithdrawal(DRIVER, DRIVER, new BigDecimal("1000.00"));

        assertThat(earningsAccountRepository.findByDriverId(DRIVER).orElseThrow().getAvailableBalance())
                .isEqualByComparingTo("140.00");
    }

    // ---- refunds ---------------------------------------------------------------------------------

    @Test
    void refundingACardBookingGoesBackToTheCardNotTheRidersWallet() {
        givenDriverHasASubaccount();
        String booking = heldBooking();
        String reference = paymentService.initiate(paystackPay(booking), RIDER).gatewayReference();
        paymentService.handleWebhookSuccess(reference, booking, 120000L);

        earningsService.refund(booking, "Booking cancelled by DRIVER");

        verify(gatewayClient).refund(eq(reference), eq(FARE), anyString());
        assertThat(walletService.getBalance(RIDER))
                .as("a card refund must not quietly become wallet credit")
                .isEqualByComparingTo("0.00");
    }

    @Test
    void refundingAWalletBookingStillGoesBackToTheWallet() {
        givenDriverHasASubaccount();
        String booking = heldBooking();
        fundWallet(RIDER, "1200.00");
        paymentService.initiate(walletPay(booking), RIDER);

        earningsService.refund(booking, "Booking cancelled by RIDER");

        assertThat(walletService.getBalance(RIDER)).isEqualByComparingTo(FARE);
        verify(gatewayClient, never()).refund(anyString(), any(), any());
    }

    // ---- backfill ---------------------------------------------------------------------------------

    @Test
    void driversWhoAddedABankAccountBeforeSplitsExistedGetASubaccount() {
        payoutRecipientRepository.save(PayoutRecipient.builder()
                .driverId(DRIVER).bankName("Test Bank").bankCode("058").accountNumber("0123456789")
                .accountName("Test Driver").gatewayRecipientCode("RCP_test")
                .gatewaySubaccountCode(null).isDefault(true).build());
        when(gatewayClient.createSubaccount(anyString(), anyString(), anyString(), any())).thenReturn(SUBACCOUNT);

        subaccountBackfill.run(null);

        assertThat(payoutRecipientRepository.findFirstByDriverIdAndIsDefaultTrueAndGatewaySubaccountCodeIsNotNull(DRIVER))
                .as("without this the driver cannot be booked at all")
                .isPresent();
    }

    @Test
    void aDriverPaystackRejectsDoesNotStopTheRest() {
        payoutRecipientRepository.save(PayoutRecipient.builder()
                .driverId("bad-details").bankName("Test Bank").bankCode("058").accountNumber("0000000000")
                .accountName("Bad Details").gatewayRecipientCode("RCP_bad").isDefault(true).build());
        payoutRecipientRepository.save(PayoutRecipient.builder()
                .driverId(DRIVER).bankName("Test Bank").bankCode("058").accountNumber("0123456789")
                .accountName("Test Driver").gatewayRecipientCode("RCP_test").isDefault(true).build());
        when(gatewayClient.createSubaccount(anyString(), eq("0000000000"), anyString(), any()))
                .thenThrow(new BadRequestException("Could not register that bank account"));
        when(gatewayClient.createSubaccount(anyString(), eq("0123456789"), anyString(), any())).thenReturn(SUBACCOUNT);

        subaccountBackfill.run(null);

        assertThat(payoutRecipientRepository.findFirstByDriverIdAndIsDefaultTrueAndGatewaySubaccountCodeIsNotNull(DRIVER))
                .isPresent();
        assertThat(payoutRecipientRepository.findFirstByDriverIdAndIsDefaultTrueAndGatewaySubaccountCodeIsNotNull("bad-details"))
                .isEmpty();
    }

    // ---- settlement debt -------------------------------------------------------------------------

    @Test
    void aDriverInDebtHasTheWholeFareTakenUntilItClears() {
        givenDriverHasASubaccount();
        earningsService.recordSettlementDebt(DRIVER, new BigDecimal("900.00"), "refund after settlement");
        String booking = heldBooking();

        paymentService.initiate(paystackPay(booking), RIDER);

        ArgumentCaptor<Long> flatCharge = ArgumentCaptor.forClass(Long.class);
        verify(gatewayClient).initiateSplit(anyString(), any(), anyString(), any(), anyMap(), any(),
                flatCharge.capture());
        assertThat(flatCharge.getValue())
                .as("the whole fare in kobo goes to us while a debt is outstanding")
                .isEqualTo(120000L);
    }

    @Test
    void completingATripPaysTheDebtDown() {
        givenDriverHasASubaccount();
        earningsService.recordSettlementDebt(DRIVER, new BigDecimal("900.00"), "refund after settlement");
        String booking = heldBooking();
        String reference = paymentService.initiate(paystackPay(booking), RIDER).gatewayReference();
        paymentService.handleWebhookSuccess(reference, booking, 120000L);

        earningsService.onTripCompleted(tripCompleted(booking));

        // Net on a 1200 fare at 5% is 1140, which more than covers the 900 owed.
        assertThat(earningsAccountRepository.findByDriverId(DRIVER).orElseThrow().getSettlementDebt())
                .isEqualByComparingTo("0.00");
    }

    @Test
    void aDebtIsNeverRecordedWithoutAnAmount() {
        assertThatThrownBy(() -> earningsService.recordSettlementDebt(DRIVER, BigDecimal.ZERO, "nothing"))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void theEarningsSummaryReportsWhatIsOwed() {
        earningsService.recordSettlementDebt(DRIVER, new BigDecimal("250.00"), "refund after settlement");

        assertThat(earningsService.getSummary(DRIVER).settlementDebt()).isEqualByComparingTo("250.00");
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private void givenDriverHasASubaccount() {
        payoutRecipientRepository.save(PayoutRecipient.builder()
                .driverId(DRIVER)
                .bankName("Test Bank")
                .bankCode("058")
                .accountNumber("0123456789")
                .accountName("Test Driver")
                .gatewayRecipientCode("RCP_test")
                .gatewaySubaccountCode(SUBACCOUNT)
                .isDefault(true)
                .build());
    }

    private String heldBooking() {
        String booking = UUID.randomUUID().toString();
        when(tripServiceClient.fetchPaymentView(booking)).thenReturn(new TripServiceClient.BookingPaymentView(
                booking, RIDER, DRIVER, BookingStatus.HELD, FARE, Instant.now().plusSeconds(240)));
        return booking;
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
}
