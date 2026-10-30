package org.coryd.payment.controller;

import io.swagger.v3.oas.annotations.Hidden;
import lombok.extern.slf4j.Slf4j;
import org.coryd.common.response.ApiResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Where Clane, the second payment gateway, sends its payment notifications. Public, like Paystack's webhook -
 * see SecurityConfig. Until Clane's signature check is in, a call is only acknowledged and noted: nothing in
 * it is trusted or acted on, and its body is never logged (it may carry customer and card details).
 */
@Slf4j
@Hidden
@RestController
@RequestMapping("/payments/clane")
public class ClaneWebhookController {

    @PostMapping("/webhook")
    public ApiResponse<Void> webhook(@RequestBody(required = false) String payload, @RequestHeader HttpHeaders headers) {
        log.info("Clane webhook received - {} bytes, headers {} - not acted on until Clane's signature check is in",
                payload == null ? 0 : payload.length(), headers.keySet());
        return ApiResponse.ok("Webhook received", null);
    }
}
