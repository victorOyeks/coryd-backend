package org.coryd.payment.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.common.exception.ResourceNotFoundException;
import org.coryd.payment.dto.CardAuthorization;
import org.coryd.payment.dto.SavedCardResponse;
import org.coryd.payment.entity.SavedCard;
import org.coryd.payment.gateway.PaymentGatewayClient;
import org.coryd.payment.repository.SavedCardRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;

/** A rider's cards from earlier checkouts, so later bookings can be paid in one tap. */
@Slf4j
@Service
@RequiredArgsConstructor
public class SavedCardService {

    private final SavedCardRepository savedCardRepository;
    private final PaymentGatewayClient gatewayClient;

    @Transactional(readOnly = true)
    public List<SavedCardResponse> list(String riderId) {
        return savedCardRepository.findByRiderIdOrderByCreatedAtDesc(riderId).stream().map(SavedCardService::toResponse).toList();
    }

    /** Only the rider's own card - anyone else's id reads as not found rather than confirming it exists. */
    @Transactional(readOnly = true)
    public SavedCard owned(String riderId, String cardId) {
        return savedCardRepository.findByIdAndRiderId(cardId, riderId)
                .orElseThrow(() -> new ResourceNotFoundException("Saved card not found"));
    }

    @Transactional
    public void remove(String riderId, String cardId) {
        SavedCard card = owned(riderId, cardId);
        savedCardRepository.delete(card);
        gatewayClient.deactivateAuthorization(card.getAuthorizationCode());
    }

    /** Paying with the same card again refreshes its row rather than adding a second one. Only reusable
     * card authorizations are kept - a bank transfer or USSD payment has nothing to charge again. */
    @Transactional
    public void remember(String riderId, CardAuthorization card) {
        if (card == null || !card.reusable() || !"card".equalsIgnoreCase(card.channel())
                || card.authorizationCode() == null || card.email() == null) {
            return;
        }
        String fingerprint = card.signature() != null ? card.signature() : card.authorizationCode();
        SavedCard saved = savedCardRepository.findByRiderIdAndFingerprint(riderId, fingerprint)
                .orElseGet(() -> SavedCard.builder().riderId(riderId).fingerprint(fingerprint).build());
        saved.setAuthorizationCode(card.authorizationCode());
        saved.setEmail(card.email());
        saved.setCardType(card.cardType() == null ? null : card.cardType().trim());
        saved.setLast4(card.last4());
        saved.setExpMonth(card.expMonth());
        saved.setExpYear(card.expYear());
        saved.setBank(card.bank());
        savedCardRepository.save(saved);
    }

    private static SavedCardResponse toResponse(SavedCard card) {
        return new SavedCardResponse(card.getId(), card.getCardType(), card.getLast4(), card.getExpMonth(), card.getExpYear(),
                card.getBank(), expired(card));
    }

    private static boolean expired(SavedCard card) {
        try {
            YearMonth expiry = YearMonth.of(Integer.parseInt(card.getExpYear()), Integer.parseInt(card.getExpMonth()));
            return expiry.isBefore(YearMonth.now(ZoneOffset.UTC));
        } catch (Exception e) {
            return false;
        }
    }
}
