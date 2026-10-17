package org.coryd.profile.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.profile.client.SmileIdClient;
import org.coryd.profile.dto.SmileIdResult;
import org.coryd.profile.service.SmileIdCheckService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Where Smile ID delivers identity check results - public (no CoRyd token), so nothing is read until
 * the signature checks out. Anything but a 2xx makes Smile ID retry (4 deliveries in all), which is what
 * a failure on our side should do; a duplicate delivery is answered 200 and ignored.
 */
@Slf4j
@RestController
@RequestMapping("/webhooks/smile-id")
@RequiredArgsConstructor
public class SmileIdWebhookController {

    private final SmileIdClient smileIdClient;
    private final SmileIdCheckService smileIdCheckService;

    @PostMapping
    public ResponseEntity<Void> receive(@RequestHeader(value = "Response-Timestamp", required = false) String timestamp,
                                        @RequestHeader(value = "Response-Signature", required = false) String signature,
                                        @RequestBody SmileIdResult result) {
        if (!smileIdClient.signatureValid(timestamp, signature)) {
            log.warn("Smile ID webhook with a missing or wrong signature - rejected");
            return ResponseEntity.status(401).build();
        }
        smileIdCheckService.handle(result);
        return ResponseEntity.ok().build();
    }
}
