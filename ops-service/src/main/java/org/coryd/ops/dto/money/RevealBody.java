package org.coryd.ops.dto.money;

/** Bank account reveal - reason is required (checked in MoneyController, same words as the ID reveal). */
public record RevealBody(String recipientId, String reason) {
}
