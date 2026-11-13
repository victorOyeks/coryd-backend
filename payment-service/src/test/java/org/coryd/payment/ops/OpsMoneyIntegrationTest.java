package org.coryd.payment.ops;

import org.coryd.common.enums.PayoutStatus;
import org.coryd.common.exception.BadRequestException;
import org.coryd.common.kafka.KafkaTopics;
import org.coryd.payment.client.ProfileServiceClient;
import org.coryd.payment.config.CommissionProperties;
import org.coryd.payment.dto.DebtEntryType;
import org.coryd.payment.dto.RefundKind;
import org.coryd.payment.dto.RefundStatus;
import org.coryd.payment.entity.DriverEarningsAccount;
import org.coryd.payment.entity.DriverSettlement;
import org.coryd.payment.entity.FareHold;
import org.coryd.payment.entity.PaymentTransaction;
import org.coryd.payment.entity.PayoutRecipient;
import org.coryd.payment.entity.Refund;
import org.coryd.payment.gateway.PaystackGatewayClient;
import org.coryd.payment.ops.dto.DebtAdjustmentRequest;
import org.coryd.payment.ops.dto.DecisionRequest;
import org.coryd.payment.ops.dto.FinanceSummary;
import org.coryd.payment.ops.dto.ManualRefundRequest;
import org.coryd.payment.ops.dto.OpsMoneyRequestView;
import org.coryd.payment.ops.dto.PayoutAccountView;
import org.coryd.payment.ops.dto.RefundFailureItem;
import org.coryd.payment.ops.dto.RefundableView;
import org.coryd.payment.ops.dto.RetryRequest;
import org.coryd.payment.ops.dto.TransactionDetail;
import org.coryd.payment.ops.dto.TransactionFilter;
import org.coryd.payment.ops.dto.TransactionRow;
import org.coryd.payment.ops.dto.TransactionType;
import org.coryd.payment.ops.entity.OpsMoneyAudit;
import org.coryd.payment.ops.entity.OpsMoneyRequest;
import org.coryd.payment.ops.entity.OpsRequestStatus;
import org.coryd.payment.ops.repository.OpsMoneyAuditRepository;
import org.coryd.payment.ops.repository.OpsMoneyQueryRepository;
import org.coryd.payment.ops.repository.OpsMoneyRequestRepository;
import org.coryd.payment.ops.service.OpsActor;
import org.coryd.payment.ops.service.OpsApprovalService;
import org.coryd.payment.ops.service.OpsEarningsService;
import org.coryd.payment.ops.service.OpsFinanceService;
import org.coryd.payment.ops.service.OpsMoneyAuditService;
import org.coryd.payment.ops.service.OpsPayoutService;
import org.coryd.payment.ops.service.OpsRefundFailureService;
import org.coryd.payment.ops.service.OpsRefundService;
import org.coryd.payment.ops.service.OpsRequestSupport;
import org.coryd.payment.ops.service.OpsTransactionQueryService;
import org.coryd.payment.repository.DriverDebtEntryRepository;
import org.coryd.payment.repository.DriverEarningsAccountRepository;
import org.coryd.payment.repository.DriverSettlementRepository;
import org.coryd.payment.repository.EarningsTransactionRepository;
import org.coryd.payment.repository.FareHoldRepository;
import org.coryd.payment.repository.PaymentTransactionRepository;
import org.coryd.payment.repository.PayoutRecipientRepository;
import org.coryd.payment.repository.RefundRepository;
import org.coryd.payment.service.EarningsPublisher;
import org.coryd.payment.service.EarningsService;
import org.coryd.payment.service.OpsSettlementResync;
import org.coryd.payment.service.SettlementSyncService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 4 money actions against a throwaway Postgres: manual refunds and the two-person rule, refund failures and retries,
 * payouts, debt adjustments, the Transactions list and the finance dashboard. Paystack is a mock - nothing leaves the test.
 */
@DataJpaTest(properties = "ops.money.approval-threshold=50000")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
@ImportAutoConfiguration(JacksonAutoConfiguration.class)
@Import({EarningsService.class, EarningsPublisher.class, CommissionProperties.class, SettlementSyncService.class, OpsSettlementResync.class,
        OpsMoneyProperties.class, OpsRequestSupport.class, OpsMoneyAuditService.class, OpsRefundService.class, OpsApprovalService.class,
        OpsRefundFailureService.class, OpsPayoutService.class, OpsEarningsService.class, OpsFinanceService.class,
        OpsTransactionQueryService.class, OpsMoneyQueryRepository.class})
class OpsMoneyIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final OpsActor ADA = new OpsActor("ops-ada", "Ada Ops");
    private static final OpsActor BAYO = new OpsActor("ops-bayo", "Bayo Ops");
    private static final String RIDER = "a11ce000-2222-0000-0000-000000000001";
    private static final String DRIVER = "d0c0ffee-1111-0000-0000-000000000001";

    @Autowired OpsRefundService refundService;
    @Autowired OpsApprovalService approvalService;
    @Autowired OpsRefundFailureService refundFailureService;
    @Autowired OpsPayoutService payoutService;
    @Autowired OpsEarningsService opsEarningsService;
    @Autowired OpsFinanceService financeService;
    @Autowired OpsTransactionQueryService transactionQueryService;
    @Autowired PaymentTransactionRepository paymentTransactionRepository;
    @Autowired FareHoldRepository fareHoldRepository;
    @Autowired RefundRepository refundRepository;
    @Autowired DriverEarningsAccountRepository earningsAccountRepository;
    @Autowired DriverDebtEntryRepository debtEntryRepository;
    @Autowired EarningsTransactionRepository earningsTransactionRepository;
    @Autowired DriverSettlementRepository settlementRepository;
    @Autowired PayoutRecipientRepository payoutRecipientRepository;
    @Autowired OpsMoneyRequestRepository requestRepository;
    @Autowired OpsMoneyAuditRepository auditRepository;

    @MockBean KafkaTemplate<String, Object> kafkaTemplate;
    @MockBean PaystackGatewayClient gateway;
    @MockBean ProfileServiceClient profileServiceClient;

    @BeforeEach
    void cleanSlate() {
        auditRepository.deleteAll();
        requestRepository.deleteAll();
        settlementRepository.deleteAll();
        payoutRecipientRepository.deleteAll();
        debtEntryRepository.deleteAll();
        refundRepository.deleteAll();
        earningsTransactionRepository.deleteAll();
        earningsAccountRepository.deleteAll();
        fareHoldRepository.deleteAll();
        paymentTransactionRepository.deleteAll();
        reset(gateway);
        clearInvocations(kafkaTemplate);
        when(gateway.refund(anyString(), any(), any())).thenReturn(true);
    }

    // ---- manual refunds -------------------------------------------------------------------------------------------

    @Test
    void aPartialRefundAtOrBelowTheThresholdGoesThroughTheRefundPathAtOnce() {
        String booking = paidBooking("5000.00");

        OpsMoneyRequestView done = refundService.submit(booking, new ManualRefundRequest(new BigDecimal("2000"), "Rider overcharged", null), ADA);

        assertThat(done.status()).isEqualTo(OpsRequestStatus.COMPLETED);
        assertThat(done.needsApproval()).isFalse();
        verify(gateway).refund(eq("ref-" + booking), eq(new BigDecimal("2000.00")), eq("Rider overcharged"));
        Refund refund = refundRepository.findById(done.refundId()).orElseThrow();
        assertThat(refund.getStatus()).isEqualTo(RefundStatus.REFUNDED);
        assertThat(refund.getAmount()).isEqualByComparingTo("2000.00");
        // The rest of the fare is what a later cancellation refunds or a completion credits.
        FareHold hold = fareHoldRepository.findFirstByBookingIdOrderByCreatedAtDesc(booking).orElseThrow();
        assertThat(hold.getAmount()).isEqualByComparingTo("3000.00");
        assertThat(hold.isRefunded()).isFalse();
        // The driver's share (95%) of what went back is owed by them, like every other refund.
        assertThat(earningsService().amountOwed(DRIVER)).isEqualByComparingTo("1900.00");
        assertThat(refundService.refundable(booking).refundable()).isEqualByComparingTo("3000.00");
        verify(kafkaTemplate).send(eq(KafkaTopics.REFUND_ISSUED), any());
        assertThat(auditRepository.findByRequestIdOrderByCreatedAtAsc(done.id())).extracting(OpsMoneyAudit::getAction)
                .containsExactly(OpsMoneyAudit.Action.REFUND_REQUESTED, OpsMoneyAudit.Action.REFUND_EXECUTED);
        OpsMoneyAudit executed = auditRepository.findByRequestIdOrderByCreatedAtAsc(done.id()).get(1);
        assertThat(executed.getActorId()).isEqualTo(ADA.id());
        assertThat(executed.getAmount()).isEqualByComparingTo("2000.00");
        assertThat(executed.getReason()).isEqualTo("Rider overcharged");
        assertThat(executed.getBeforeState()).contains("\"refundable\":5000.00");
        assertThat(executed.getAfterState()).contains("\"refundable\":3000.00");
    }

    @Test
    void refundingEverythingLeftSettlesTheSeatSoACancellationCantRefundItAgain() {
        String booking = paidBooking("1200.00");

        OpsMoneyRequestView done = refundService.submit(booking, new ManualRefundRequest(null, "Trip never happened", null), ADA);

        assertThat(done.amount()).isEqualByComparingTo("1200.00");
        assertThat(fareHoldRepository.findFirstByBookingIdOrderByCreatedAtDesc(booking).orElseThrow().isRefunded()).isTrue();
        earningsService().refund(booking, "Booking cancelled");
        verify(gateway, times(1)).refund(anyString(), any(), any());
        assertThatThrownBy(() -> refundService.submit(booking, new ManualRefundRequest(null, "again", null), BAYO))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("Nothing is left to refund");
    }

    @Test
    void everyRefundNeedsAReasonAndCanNeverExceedWhatIsLeft() {
        String booking = paidBooking("1000.00");

        assertThatThrownBy(() -> refundService.submit(booking, new ManualRefundRequest(new BigDecimal("100"), "  ", null), ADA))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("reason");
        assertThatThrownBy(() -> refundService.submit(booking, new ManualRefundRequest(new BigDecimal("1000.01"), "too much", null), ADA))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("Only ₦1,000 can still be refunded");
        assertThatThrownBy(() -> refundService.submit(booking, new ManualRefundRequest(new BigDecimal("10.001"), "kobo", null), ADA))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("two decimal places");
        refundService.submit(booking, new ManualRefundRequest(new BigDecimal("600"), "part", null), ADA);
        assertThatThrownBy(() -> refundService.submit(booking, new ManualRefundRequest(new BigDecimal("400.01"), "rest+", null), ADA))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("Only ₦400");
        verify(gateway, times(1)).refund(anyString(), any(), any());
    }

    @Test
    void anUnpaidBookingHasNothingToRefund() {
        String booking = UUID.randomUUID().toString();
        paymentTransactionRepository.save(PaymentTransaction.builder().bookingId(booking).riderId(RIDER).amount(new BigDecimal("900"))
                .gatewayReference("ref-" + booking).status(PayoutStatus.PENDING).build());

        assertThatThrownBy(() -> refundService.submit(booking, new ManualRefundRequest(null, "x", null), ADA))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("hasn't succeeded");
        verify(gateway, never()).refund(anyString(), any(), any());
    }

    // ---- two-person rule ------------------------------------------------------------------------------------------

    @Test
    void aboveTheThresholdNothingIsSentUntilADifferentAdminApproves() {
        String booking = paidBooking("80000.00");

        OpsMoneyRequestView asked = refundService.submit(booking, new ManualRefundRequest(new BigDecimal("60000"), "Fraud reversal", null), ADA);

        assertThat(asked.status()).isEqualTo(OpsRequestStatus.PENDING_APPROVAL);
        assertThat(asked.needsApproval()).isTrue();
        assertThat(asked.actions().cancel()).isTrue();
        assertThat(asked.actions().approve()).isFalse();
        verify(gateway, never()).refund(anyString(), any(), any());
        // What's waiting is claimed - nobody can refund it twice meanwhile.
        RefundableView meanwhile = refundService.refundable(booking);
        assertThat(meanwhile.awaitingApproval()).isEqualByComparingTo("60000.00");
        assertThat(meanwhile.refundable()).isEqualByComparingTo("20000.00");
        assertThatThrownBy(() -> refundService.submit(booking, new ManualRefundRequest(new BigDecimal("20000.01"), "x", null), BAYO))
                .isInstanceOf(BadRequestException.class);

        assertThatThrownBy(() -> approvalService.approve(asked.id(), null, ADA))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("different admin");
        verify(gateway, never()).refund(anyString(), any(), any());

        OpsMoneyRequestView approved = approvalService.approve(asked.id(), new DecisionRequest("Checked with finance"), BAYO);

        assertThat(approved.status()).isEqualTo(OpsRequestStatus.COMPLETED);
        assertThat(approved.decidedBy().id()).isEqualTo(BAYO.id());
        verify(gateway, times(1)).refund(eq("ref-" + booking), eq(new BigDecimal("60000.00")), any());
        assertThatThrownBy(() -> approvalService.approve(asked.id(), null, BAYO))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("COMPLETED");
        verify(gateway, times(1)).refund(anyString(), any(), any());
        assertThat(auditRepository.findByRequestIdOrderByCreatedAtAsc(asked.id())).extracting(OpsMoneyAudit::getAction)
                .containsExactly(OpsMoneyAudit.Action.REFUND_REQUESTED, OpsMoneyAudit.Action.REQUEST_APPROVED,
                        OpsMoneyAudit.Action.REFUND_EXECUTED);
    }

    @Test
    void aRejectionNeedsAReasonAndOnlyTheRequesterCanCancel() {
        String booking = paidBooking("90000.00");
        OpsMoneyRequestView first = refundService.submit(booking, new ManualRefundRequest(new BigDecimal("70000"), "a", null), ADA);

        assertThatThrownBy(() -> approvalService.reject(first.id(), new DecisionRequest(" "), BAYO))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("reason");
        assertThatThrownBy(() -> approvalService.cancel(first.id(), null, BAYO))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("Only the admin who asked");
        assertThatThrownBy(() -> approvalService.reject(first.id(), new DecisionRequest("no"), ADA))
                .isInstanceOf(BadRequestException.class);

        OpsMoneyRequestView rejected = approvalService.reject(first.id(), new DecisionRequest("Not supported by the trip log"), BAYO);
        assertThat(rejected.status()).isEqualTo(OpsRequestStatus.REJECTED);
        assertThat(rejected.decisionReason()).isEqualTo("Not supported by the trip log");
        assertThatThrownBy(() -> approvalService.approve(first.id(), null, BAYO)).isInstanceOf(BadRequestException.class);

        OpsMoneyRequestView second = refundService.submit(booking, new ManualRefundRequest(new BigDecimal("70000"), "b", null), ADA);
        assertThat(approvalService.cancel(second.id(), new DecisionRequest("Wrong booking"), ADA).status()).isEqualTo(OpsRequestStatus.CANCELLED);
        assertThat(refundService.refundable(booking).refundable()).isEqualByComparingTo("90000.00");
        verify(gateway, never()).refund(anyString(), any(), any());
    }

    @Test
    void anApprovalRechecksWhatIsStillRefundable() {
        String booking = paidBooking("60000.00");
        OpsMoneyRequestView asked = refundService.submit(booking, new ManualRefundRequest(new BigDecimal("55000"), "a", null), ADA);
        // Meanwhile the seat was cancelled and refunded in full by the normal path.
        refundRepository.save(Refund.builder().userId(RIDER).bookingId(booking).amount(new BigDecimal("10000")).status(RefundStatus.REFUNDED)
                .kind(RefundKind.CANCELLATION).build());

        assertThatThrownBy(() -> approvalService.approve(asked.id(), null, BAYO))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("Only ₦50,000");
        assertThat(requestRepository.findById(asked.id()).orElseThrow().getStatus()).isEqualTo(OpsRequestStatus.PENDING_APPROVAL);
        verify(gateway, never()).refund(anyString(), any(), any());
    }

    // ---- idempotency and races -----------------------------------------------------------------------------------

    @Test
    void aResentSubmitReturnsTheFirstResultAndRefundsOnce() {
        String booking = paidBooking("3000.00");
        ManualRefundRequest body = new ManualRefundRequest(new BigDecimal("1000"), "Late pickup", "console-key-1");

        OpsMoneyRequestView first = refundService.submit(booking, body, ADA);
        OpsMoneyRequestView again = refundService.submit(booking, body, ADA);

        assertThat(again.id()).isEqualTo(first.id());
        verify(gateway, times(1)).refund(anyString(), any(), any());
        assertThat(refundService.refundable(booking).refundable()).isEqualByComparingTo("2000.00");
        String other = paidBooking("3000.00");
        assertThatThrownBy(() -> refundService.submit(other, body, ADA)).isInstanceOf(BadRequestException.class)
                .hasMessageContaining("idempotencyKey");
    }

    @Test
    void twoAdminsRefundingTheSameBookingAtOnceCanNeverPayOutMoreThanWasPaid() throws Exception {
        String booking = paidBooking("500.00");
        when(gateway.refund(anyString(), any(), any())).thenAnswer(inv -> {
            Thread.sleep(300);
            return true;
        });
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Callable<Object>> calls = List.of(
                () -> { start.await(); return refundService.submit(booking, new ManualRefundRequest(new BigDecimal("300"), "a", null), ADA); },
                () -> { start.await(); return refundService.submit(booking, new ManualRefundRequest(new BigDecimal("300"), "b", null), BAYO); });
        List<Future<Object>> futures = new ArrayList<>();
        for (Callable<Object> call : calls) {
            futures.add(pool.submit(call));
        }
        start.countDown();
        int ok = 0;
        int refused = 0;
        for (Future<Object> f : futures) {
            try {
                f.get();
                ok++;
            } catch (java.util.concurrent.ExecutionException e) {
                assertThat(e.getCause()).isInstanceOf(BadRequestException.class);
                refused++;
            }
        }
        pool.shutdown();

        assertThat(ok).isEqualTo(1);
        assertThat(refused).isEqualTo(1);
        verify(gateway, times(1)).refund(anyString(), any(), any());
        assertThat(refundRepository.findByBookingId(booking)).hasSize(1);
        assertThat(refundService.refundable(booking).refundable()).isEqualByComparingTo("200.00");
    }

    // ---- refund failures -----------------------------------------------------------------------------------------

    @Test
    void aRefundPaystackRefusesIsListedAndCanBeRetried() {
        String booking = paidBooking("2500.00");
        when(gateway.refund(anyString(), any(), any())).thenReturn(false);

        OpsMoneyRequestView failed = refundService.submit(booking, new ManualRefundRequest(new BigDecimal("2500"), "Goodwill", null), ADA);

        assertThat(failed.status()).isEqualTo(OpsRequestStatus.FAILED);
        assertThat(failed.actions().retry()).isTrue();
        assertThat(refundRepository.findByBookingId(booking)).isEmpty();
        assertThat(refundService.refundable(booking).refundable()).isEqualByComparingTo("2500.00");
        assertThat(refundFailureService.list(RefundFailureItem.Kind.MANUAL, 0, 20).content())
                .singleElement().satisfies(f -> assertThat(f.id()).isEqualTo(failed.id()));

        when(gateway.refund(anyString(), any(), any())).thenReturn(true);
        OpsMoneyRequestView retried = approvalService.retry(failed.id(), new RetryRequest("Paystack back up", null), BAYO);

        assertThat(retried.status()).isEqualTo(OpsRequestStatus.COMPLETED);
        assertThat(retried.attempts()).isEqualTo(2);
        assertThat(refundFailureService.list(null, 0, 20).content()).isEmpty();
        assertThatThrownBy(() -> approvalService.retry(failed.id(), new RetryRequest("again", null), BAYO))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("Only a failed request");
    }

    @Test
    void aCancellationRefundOnTheAutomaticRetryCanBeRetriedByHand() {
        String booking = paidBooking("1800.00");
        when(gateway.refund(anyString(), any(), any())).thenReturn(false);
        earningsService().refund(booking, "Driver cancelled");
        FareHold hold = fareHoldRepository.findFirstByBookingIdOrderByCreatedAtDesc(booking).orElseThrow();
        assertThat(hold.getRefundDue()).isEqualByComparingTo("1800.00");

        List<RefundFailureItem> failures = refundFailureService.list(RefundFailureItem.Kind.CANCELLATION, 0, 20).content();
        assertThat(failures).singleElement().satisfies(f -> {
            assertThat(f.id()).isEqualTo(hold.getId());
            assertThat(f.autoRetry()).isTrue();
            assertThat(f.retryable()).isTrue();
        });
        assertThatThrownBy(() -> refundFailureService.retryCancellation(hold.getId(), new RetryRequest(" ", null), ADA))
                .isInstanceOf(BadRequestException.class);

        OpsMoneyRequestView stillFailing = refundFailureService.retryCancellation(hold.getId(), new RetryRequest("Try now", null), ADA);
        assertThat(stillFailing.status()).isEqualTo(OpsRequestStatus.FAILED);

        when(gateway.refund(anyString(), any(), any())).thenReturn(true);
        OpsMoneyRequestView done = refundFailureService.retryCancellation(hold.getId(), new RetryRequest("Try again", null), ADA);
        assertThat(done.status()).isEqualTo(OpsRequestStatus.COMPLETED);
        assertThat(fareHoldRepository.findById(hold.getId()).orElseThrow().getRefundDue()).isEqualByComparingTo("0");
        assertThat(refundRepository.findByBookingId(booking)).singleElement()
                .satisfies(r -> assertThat(r.getStatus()).isEqualTo(RefundStatus.REFUNDED));
        assertThatThrownBy(() -> refundFailureService.retryCancellation(hold.getId(), new RetryRequest("third", null), ADA))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("already gone through");
    }

    // ---- payouts ---------------------------------------------------------------------------------------------------

    @Test
    void onlyAFailedPayoutCanBeRetriedAndABigOneNeedsASecondAdmin() {
        recipient();
        DriverSettlement paid = settlement("success", "4000.00");
        DriverSettlement failed = settlement("failed", "4000.00");
        DriverSettlement bigFailed = settlement("failed", "75000.00");
        when(gateway.resolveAccountNumber("0123456789", "058"))
                .thenReturn(new PaystackGatewayClient.ResolvedAccount("0123456789", "EMEKA OKAFOR"));

        assertThatThrownBy(() -> payoutService.retry(paid.getId(), new RetryRequest("x", null), ADA))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("Only a failed payout");

        OpsMoneyRequestView retried = payoutService.retry(failed.getId(), new RetryRequest("Driver fixed account", null), ADA);
        assertThat(retried.status()).isEqualTo(OpsRequestStatus.COMPLETED);
        verify(gateway).resolveAccountNumber("0123456789", "058");

        OpsMoneyRequestView big = payoutService.retry(bigFailed.getId(), new RetryRequest("Driver fixed account", null), ADA);
        assertThat(big.status()).isEqualTo(OpsRequestStatus.PENDING_APPROVAL);
        assertThatThrownBy(() -> payoutService.retry(bigFailed.getId(), new RetryRequest("dup", null), BAYO))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("already waiting");
        assertThat(approvalService.approve(big.id(), null, BAYO).status()).isEqualTo(OpsRequestStatus.COMPLETED);

        assertThat(payoutService.counts(new TransactionFilter(TransactionType.PAYOUT, null, null, null, null, null, null, null, null,
                null, null, null))).containsEntry("paid", 1L).containsEntry("failed", 2L).containsEntry("all", 3L);
    }

    @Test
    void aPayoutRetryFailsWithTheReasonWhenTheBankNoLongerKnowsTheAccount() {
        recipient();
        DriverSettlement failed = settlement("failed", "4000.00");
        when(gateway.resolveAccountNumber(anyString(), anyString())).thenThrow(new BadRequestException("Could not verify that account number"));

        OpsMoneyRequestView retried = payoutService.retry(failed.getId(), new RetryRequest("Try", null), ADA);

        assertThat(retried.status()).isEqualTo(OpsRequestStatus.FAILED);
        assertThat(retried.failureReason()).contains("could not confirm");
    }

    @Test
    void theAccountNumberIsMaskedAndRevealingItIsAudited() {
        recipient();

        PayoutAccountView view = payoutService.payoutAccount(DRIVER);
        assertThat(view.canReceivePayouts()).isTrue();
        assertThat(view.accounts()).singleElement().satisfies(a -> {
            assertThat(a.accountNumberMasked()).isEqualTo("\u2022\u2022\u2022\u2022\u2022\u20226789");
            assertThat(a.revealedAt()).isNull();
            assertThat(a.accountNumber()).isNull();
            assertThat(a.accountName()).isEqualTo("EMEKA OKAFOR");
        });
        assertThatThrownBy(() -> payoutService.reveal(DRIVER, null, "", ADA)).isInstanceOf(BadRequestException.class)
                .hasMessage("Say why you need to see it - it goes in the audit log");

        PayoutAccountView.Account full = payoutService.reveal(DRIVER, null, "Verifying before a payout retry", ADA);
        assertThat(full.accountNumber()).isEqualTo("0123456789");
        assertThat(full.revealedAt()).isNotNull();
        assertThat(full.expiresInSeconds()).isEqualTo(60);
        assertThat(auditRepository.findByTargetIdOrderByCreatedAtAsc(DRIVER)).as("the driver is the target").singleElement().satisfies(a -> {
            assertThat(a.getAction()).isEqualTo(OpsMoneyAudit.Action.PAYOUT_ACCOUNT_REVEALED);
            assertThat(a.getTargetType()).isEqualTo(OpsMoneyAudit.TargetType.PAYOUT_ACCOUNT);
            assertThat(a.getActorId()).isEqualTo(ADA.id());
            assertThat(a.getAfterState()).contains(full.id()).contains("BANK_ACCOUNT_NUMBER").doesNotContain("0123456789");
        });
    }

    // ---- earnings and settlement debt -----------------------------------------------------------------------------

    @Test
    void debtAdjustmentsGoIntoTheDriversOwnLedgerAndACreditCantGoBelowZero() {
        OpsMoneyRequestView debit = opsEarningsService.adjust(DRIVER,
                new DebtAdjustmentRequest(OpsMoneyRequest.Direction.DEBIT, new BigDecimal("3000"), "Refund made by hand on Paystack", null), ADA);
        assertThat(debit.status()).isEqualTo(OpsRequestStatus.COMPLETED);
        assertThat(earningsService().amountOwed(DRIVER)).isEqualByComparingTo("3000.00");

        assertThatThrownBy(() -> opsEarningsService.adjust(DRIVER,
                new DebtAdjustmentRequest(OpsMoneyRequest.Direction.CREDIT, new BigDecimal("3000.01"), "too much", null), ADA))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("only owes ₦3,000");

        opsEarningsService.adjust(DRIVER, new DebtAdjustmentRequest(OpsMoneyRequest.Direction.CREDIT, new BigDecimal("1000"), "Paid cash", null), ADA);
        assertThat(earningsService().amountOwed(DRIVER)).isEqualByComparingTo("2000.00");
        assertThat(debtEntryRepository.findByDriverIdOrderByCreatedAtDescIdDesc(DRIVER, PageRequest.of(0, 10)).getContent())
                .allSatisfy(e -> assertThat(e.getType()).isEqualTo(DebtEntryType.OPS_ADJUSTMENT))
                .extracting(e -> e.getAmount().toPlainString()).containsExactlyInAnyOrder("3000.00", "-1000.00");

        OpsMoneyRequestView big = opsEarningsService.adjust(DRIVER,
                new DebtAdjustmentRequest(OpsMoneyRequest.Direction.DEBIT, new BigDecimal("50000.01"), "Fraud", null), ADA);
        assertThat(big.status()).isEqualTo(OpsRequestStatus.PENDING_APPROVAL);
        assertThat(earningsService().amountOwed(DRIVER)).isEqualByComparingTo("2000.00");
        approvalService.approve(big.id(), null, BAYO);
        assertThat(earningsService().amountOwed(DRIVER)).isEqualByComparingTo("52000.01");

        var statement = opsEarningsService.statement(DRIVER, LocalDate.now().minusDays(1), LocalDate.now().plusDays(1),
                Instant.now().minusSeconds(86_400), Instant.now().plusSeconds(86_400), ADA);
        assertThat(statement.owed().amount()).isEqualByComparingTo("52000.01");
        assertThat(statement.owed().blockedFromPosting()).isTrue();
        assertThat(statement.period().opsAdjustments()).isEqualByComparingTo("52000.01");
    }

    // ---- transactions, B-524EA3, finance --------------------------------------------------------------------------

    @Test
    void aDuplicateChargeSentBackNeitherShowsAsRefundedNorReducesWhatIsRefundable() {
        String booking = paidBooking("500.00");
        refundRepository.save(Refund.builder().userId(RIDER).bookingId(booking)
                .paymentTransactionId(paymentTransactionRepository.findFirstByBookingIdOrderByCreatedAtDesc(booking).orElseThrow().getId())
                .amount(new BigDecimal("507.62")).status(RefundStatus.REFUNDED).kind(RefundKind.DUPLICATE_CHARGE)
                .reason("Duplicate payment for a booking already paid").refundedAt(Instant.now()).build());

        RefundableView view = refundService.refundable(booking);

        assertThat(view.paid()).isEqualByComparingTo("500.00");
        assertThat(view.refunded()).isEqualByComparingTo("0");
        assertThat(view.duplicateChargeRefunded()).isEqualByComparingTo("507.62");
        assertThat(view.refundable()).isEqualByComparingTo("500.00");
    }

    /**
     * B-524EA3 as it is on dev: ₦500 paid, the rider cancelled (CANCELLED_RIDER - the hold was settled as refunded), and the
     * only refund on record is the ₦507.62 duplicate charge. The figures alone would offer ₦500 again; it must be blocked.
     */
    @Test
    void aFareTheHoldSaysWentBackIsNeverOfferedAgainWhenNoRefundOfItIsRecorded() {
        String booking = paidBooking("500.00");
        FareHold hold = fareHoldRepository.findFirstByBookingIdOrderByCreatedAtDesc(booking).orElseThrow();
        hold.setRefunded(true);
        hold.setRefundDue(new BigDecimal("500.00"));
        hold.setRefundReason("CANCELLED_RIDER");
        fareHoldRepository.save(hold);
        refundRepository.save(Refund.builder().userId(RIDER).bookingId(booking)
                .paymentTransactionId(paymentTransactionRepository.findFirstByBookingIdOrderByCreatedAtDesc(booking).orElseThrow().getId())
                .amount(new BigDecimal("507.62")).status(RefundStatus.REFUNDED).kind(RefundKind.DUPLICATE_CHARGE)
                .reason("Duplicate payment for a booking already paid").refundedAt(Instant.now()).build());

        RefundableView view = refundService.refundable(booking);

        assertThat(view.paid()).isEqualByComparingTo("500.00");
        assertThat(view.refunded()).isEqualByComparingTo("0");
        assertThat(view.duplicateChargeRefunded()).isEqualByComparingTo("507.62");
        assertThat(view.refundable()).isEqualByComparingTo("0");
        assertThat(view.blockedReason()).isEqualTo("The fare was already returned on this booking - check Paystack before refunding");
        assertThatThrownBy(() -> refundService.submit(booking, new ManualRefundRequest(null, "Rider says not refunded", null), ADA))
                .isInstanceOf(BadRequestException.class).hasMessage(RefundableView.FARE_ALREADY_RETURNED);
        assertThatThrownBy(() -> refundService.submit(booking, new ManualRefundRequest(new BigDecimal("100"), "Partial", null), ADA))
                .isInstanceOf(BadRequestException.class).hasMessage(RefundableView.FARE_ALREADY_RETURNED);
    }

    @Test
    void aRefundedHoldWithItsCancellationRefundOnRecordIsNotBlocked() {
        String booking = paidBooking("500.00");
        FareHold hold = fareHoldRepository.findFirstByBookingIdOrderByCreatedAtDesc(booking).orElseThrow();
        hold.setRefunded(true);
        fareHoldRepository.save(hold);
        refundRepository.save(Refund.builder().userId(RIDER).bookingId(booking)
                .paymentTransactionId(paymentTransactionRepository.findFirstByBookingIdOrderByCreatedAtDesc(booking).orElseThrow().getId())
                .amount(new BigDecimal("250.00")).status(RefundStatus.REFUNDED).kind(RefundKind.CANCELLATION)
                .reason("Rider cancelled").refundedAt(Instant.now()).build());

        RefundableView view = refundService.refundable(booking);

        assertThat(view.blockedReason()).isNull();
        assertThat(view.refundable()).isEqualByComparingTo("250.00");
    }

    @Test
    void theTransactionsListSpansPaymentsRefundsAndPayoutsWithFiltersAndCounts() {
        String booking = paidBooking("4000.00");
        refundService.submit(booking, new ManualRefundRequest(new BigDecimal("1500"), "Partial", null), ADA);
        settlement("success", "3800.00");
        paidBooking("9000.00");

        TransactionFilter all = new TransactionFilter(null, null, null, null, null, null, null, null, null, null, null, null);
        assertThat(transactionQueryService.counts(all)).containsEntry("payment", 2L).containsEntry("refund", 1L)
                .containsEntry("payout", 1L).containsEntry("all", 4L);

        String ref = "B-" + booking.substring(0, 6).toUpperCase();
        List<TransactionRow> forBooking = transactionQueryService.list(new TransactionFilter(null, null, null, null, null, null, ref, null,
                null, null, null, TransactionFilter.Sort.OLDEST), 0, 20).content();
        assertThat(forBooking).extracting(TransactionRow::type).containsExactly(TransactionType.PAYMENT, TransactionType.REFUND);
        assertThat(forBooking.get(1).kind()).isEqualTo("MANUAL");
        assertThat(forBooking.get(1).driverId()).isEqualTo(DRIVER);

        assertThat(transactionQueryService.list(new TransactionFilter(TransactionType.PAYMENT, "PAID", null, null, null, null, null, null,
                new BigDecimal("5000"), null, null, TransactionFilter.Sort.AMOUNT_DESC), 0, 20).content())
                .singleElement().satisfies(r -> assertThat(r.amount()).isEqualByComparingTo("9000.00"));
        assertThat(transactionQueryService.list(new TransactionFilter(null, null, null, null, "R-" + RIDER.substring(0, 6).toUpperCase(),
                null, null, null, null, null, "ref-" + booking.substring(0, 8), null), 0, 20).totalElements()).isEqualTo(2);
        assertThat(transactionQueryService.list(new TransactionFilter(null, null, null, null, null, null, null, List.of(), null, null,
                null, null), 0, 20).totalElements()).isZero();

        String paymentId = forBooking.get(0).id();
        TransactionDetail detail = transactionQueryService.detail(TransactionType.PAYMENT, paymentId, BAYO);
        assertThat(detail.booking().refundable().refundable()).isEqualByComparingTo("2500.00");
        assertThat(detail.booking().requests()).singleElement().satisfies(r -> assertThat(r.requestedBy().id()).isEqualTo(ADA.id()));
        assertThat(detail.timeline()).extracting(e -> e.type()).contains("PAYMENT_STARTED", "PAYMENT_PAID", "REFUND_REFUNDED_MANUAL",
                "REFUND_REQUESTED", "REFUND_EXECUTED");
    }

    @Test
    void pagingTheTransactionsListGivesTheSameRowsAsOneBigPageForEverySort() {
        // Payments, refunds and payouts interleaved in time, with tied amounts, so every page mixes the three types.
        for (int i = 0; i < 6; i++) {
            String booking = paidBooking(i % 2 == 0 ? "2000.00" : "3500.00");
            if (i % 2 == 0) {
                refundService.submit(booking, new ManualRefundRequest(new BigDecimal("500"), "Partial " + i, null), ADA);
            }
            settlement(i % 3 == 0 ? "failed" : "success", i % 2 == 0 ? "2000.00" : "1200.00");
        }

        for (TransactionFilter.Sort sort : TransactionFilter.Sort.values()) {
            TransactionFilter filter = new TransactionFilter(null, null, null, null, null, null, null, null, null, null, null, sort);
            List<String> whole = transactionQueryService.list(filter, 0, 100).content().stream().map(TransactionRow::id).toList();
            List<String> paged = new java.util.ArrayList<>();
            for (int page = 0; page < 5; page++) {
                transactionQueryService.list(filter, page, 4).content().forEach(r -> paged.add(r.id()));
            }
            assertThat(whole).hasSize(15);
            assertThat(paged).as("sort %s", sort).containsExactlyElementsOf(whole);
        }

        TransactionFilter byRider = new TransactionFilter(null, null, null, null, RIDER, null, null, null, null, null, null, null);
        assertThat(transactionQueryService.list(byRider, 0, 100).content()).extracting(TransactionRow::type)
                .containsOnly(TransactionType.PAYMENT, TransactionType.REFUND).hasSize(9);
        assertThat(transactionQueryService.counts(byRider)).containsEntry("payment", 6L).containsEntry("refund", 3L)
                .containsEntry("payout", 0L);
    }

    @Test
    void theFinanceDashboardSumsLagosDays() {
        String booking = paidBooking("4000.00");
        paidBooking("6000.00");
        refundService.submit(booking, new ManualRefundRequest(new BigDecimal("1000"), "Partial", null), ADA);
        settlement("success", "3800.00");
        opsEarningsService.adjust(DRIVER, new DebtAdjustmentRequest(OpsMoneyRequest.Direction.DEBIT, new BigDecimal("500"), "x", null), ADA);

        LocalDate today = LocalDate.now(OpsFinanceService.LAGOS);
        FinanceSummary summary = financeService.summary(today.minusDays(6), today);

        assertThat(summary.grossBookings().count()).isEqualTo(2);
        assertThat(summary.grossBookings().amount()).isEqualByComparingTo("10000.00");
        assertThat(summary.refunds().amount()).isEqualByComparingTo("1000.00");
        assertThat(summary.netCollected()).isEqualByComparingTo("9000.00");
        assertThat(summary.payouts().paid().amount()).isEqualByComparingTo("3800.00");
        assertThat(summary.driverDebt().outstandingNow()).isEqualByComparingTo("1450.00");
        assertThat(summary.daily()).hasSize(7);
        FinanceSummary.Day last = summary.daily().get(6);
        assertThat(last.date()).isEqualTo(today);
        assertThat(last.grossBookings()).isEqualByComparingTo("10000.00");
        assertThat(last.refunds()).isEqualByComparingTo("1000.00");
        assertThatThrownBy(() -> financeService.summary(today, today.minusDays(1))).isInstanceOf(BadRequestException.class);
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    @Autowired EarningsService earningsService;

    private EarningsService earningsService() {
        return earningsService;
    }

    private String paidBooking(String fare) {
        String booking = UUID.randomUUID().toString();
        PaymentTransaction t = paymentTransactionRepository.save(PaymentTransaction.builder().bookingId(booking).riderId(RIDER)
                .amount(new BigDecimal(fare)).gatewayReference("ref-" + booking).cardType("visa ").cardLast4("4081")
                .status(PayoutStatus.PAID).build());
        fareHoldRepository.save(FareHold.builder().paymentTransactionId(t.getId()).bookingId(booking).driverId(DRIVER)
                .amount(new BigDecimal(fare)).build());
        return booking;
    }

    private void recipient() {
        payoutRecipientRepository.save(PayoutRecipient.builder().driverId(DRIVER).bankName("GTBank").bankCode("058")
                .accountNumber("0123456789").accountName("EMEKA OKAFOR").gatewaySubaccountCode("ACCT_x").isDefault(true).build());
    }

    private DriverSettlement settlement(String status, String amount) {
        return settlementRepository.save(DriverSettlement.builder().driverId(DRIVER).gatewaySettlementId(UUID.randomUUID().toString())
                .amount(new BigDecimal(amount)).status(status).settlementDate(Instant.now()).build());
    }

    @SuppressWarnings("unused")
    private DriverEarningsAccount account() {
        return earningsAccountRepository.findByDriverId(DRIVER).orElseThrow();
    }
}
