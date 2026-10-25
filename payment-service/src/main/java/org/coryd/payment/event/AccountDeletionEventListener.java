package org.coryd.payment.event;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.common.kafka.KafkaTopics;
import org.coryd.common.kafka.events.UserDeletedEvent;
import org.coryd.payment.config.KafkaConfig;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * An account was deleted: its saved cards and payout bank details are erased. Payments, refunds, earnings
 * and payouts stay for the accounting trail, under a user ID that no longer leads to anyone.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AccountDeletionEventListener {

    private final JdbcTemplate jdbc;

    @KafkaListener(topics = KafkaTopics.USER_DELETED, groupId = KafkaConfig.GROUP_ID)
    @Transactional
    public void onDeleted(UserDeletedEvent event) {
        int cards = jdbc.update("DELETE FROM payment.saved_cards WHERE rider_id = ?", event.getUserId());
        int banks = jdbc.update("DELETE FROM payment.payout_recipients WHERE driver_id = ?", event.getUserId());
        log.info("Account {} deleted - {} saved card(s) and {} payout account(s) erased", event.getUserId(), cards, banks);
    }
}
