package org.coryd.notification.event;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.common.kafka.KafkaTopics;
import org.coryd.common.kafka.events.UserDeletedEvent;
import org.coryd.notification.config.KafkaConfig;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * An account was deleted: its push tokens, in-app notifications and preferences are erased, and the send
 * log keeps its rows (delivery history) but loses the person's phone number and email.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AccountDeletionEventListener {

    private final JdbcTemplate jdbc;

    @KafkaListener(topics = KafkaTopics.USER_DELETED, groupId = KafkaConfig.GROUP_ID_ACCOUNT)
    @Transactional
    public void onDeleted(UserDeletedEvent event) {
        String userId = event.getUserId();
        int tokens = jdbc.update("DELETE FROM notification.device_tokens WHERE user_id = ?", userId);
        int inbox = jdbc.update("DELETE FROM notification.user_notifications WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM notification.notification_preferences WHERE user_id = ?", userId);
        int logs = jdbc.update("UPDATE notification.notification_log SET recipient_phone = NULL, recipient_email = NULL "
                + "WHERE user_id = ? OR (CAST(? AS VARCHAR) IS NOT NULL AND recipient_phone = ?)", userId, event.getPhone(), event.getPhone());
        log.info("Account {} deleted - {} device token(s) and {} notification(s) erased, {} log row(s) anonymised",
                userId, tokens, inbox, logs);
    }
}
