package org.coryd.notification.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Placeholder for Firebase Cloud Messaging push dispatch. Wiring in the real
 * Firebase Admin SDK is a follow-up once service-account credentials exist for
 * this project - kept as a single seam so that wiring doesn't touch callers.
 */
@Slf4j
@Service
public class FcmService {

    public boolean sendPush(String fcmToken, String title, String body) {
        log.info("Push to {}: {} - {}", fcmToken, title, body);
        return true;
    }
}
