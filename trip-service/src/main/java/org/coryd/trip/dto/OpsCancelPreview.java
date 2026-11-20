package org.coryd.trip.dto;

import org.coryd.trip.entity.CancellationFault;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * What an ops cancellation would refund if sent now - the console's confirm dialog shows it before ops confirms.
 * refunds are the same rows OpsActionResult.refunds returns after the cancel (payment-service's split: the fare left
 * after any earlier ops refund, less the fee, plus the card fee when it goes back). refundTotal adds them up;
 * cardFeeRefundTotal is the card-fee part. refundEstimated: payment-service couldn't say for at least one seat, so that
 * seat shows the fare less the fee. bookingId and fault are null for a whole-trip preview.
 */
public record OpsCancelPreview(
        String routePostId,
        String bookingId,
        CancellationFault fault,
        boolean feeWaived,
        List<OpsActionResult.Refund> refunds,
        BigDecimal refundTotal,
        BigDecimal cardFeeRefundTotal,
        boolean refundEstimated
) {

    public static OpsCancelPreview of(String routePostId, String bookingId, CancellationFault fault, boolean feeWaived,
                                      List<OpsActionResult.Refund> refunds) {
        BigDecimal total = refunds.stream().map(OpsActionResult.Refund::refund).filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal cardFees = refunds.stream().map(OpsActionResult.Refund::cardFeeRefund).filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new OpsCancelPreview(routePostId, bookingId, fault, feeWaived, refunds, total, cardFees,
                refunds.stream().anyMatch(OpsActionResult.Refund::refundEstimated));
    }
}
