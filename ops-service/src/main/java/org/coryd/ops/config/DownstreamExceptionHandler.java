package org.coryd.ops.config;

import org.coryd.common.response.ApiResponse;
import org.coryd.ops.client.DownstreamException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Ahead of common's GlobalExceptionHandler, whose catch-all would otherwise turn every relayed
 * refusal - a 400 "rejection reason required", a 404 unknown driver - into a generic 500. */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class DownstreamExceptionHandler {

    @ExceptionHandler(DownstreamException.class)
    public ResponseEntity<ApiResponse<Void>> handle(DownstreamException e) {
        return ResponseEntity.status(e.getStatus()).body(ApiResponse.error(e.getMessage()));
    }
}
