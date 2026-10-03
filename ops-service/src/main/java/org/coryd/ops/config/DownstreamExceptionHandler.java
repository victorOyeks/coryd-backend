package org.coryd.ops.config;

import org.coryd.common.response.ApiResponse;
import org.coryd.ops.client.DownstreamException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Arrays;

/** Ahead of common's GlobalExceptionHandler, whose catch-all would otherwise turn every relayed
 * refusal - a 400 "rejection reason required", a 404 unknown driver - and every mistyped enum
 * parameter into a generic 500. */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class DownstreamExceptionHandler {

    @ExceptionHandler(DownstreamException.class)
    public ResponseEntity<ApiResponse<Void>> handle(DownstreamException e) {
        return ResponseEntity.status(e.getStatus()).body(ApiResponse.error(e.getMessage()));
    }

    /** e.g. ?status=OPEN - names the allowed values instead of failing as a 500. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> badParameter(MethodArgumentTypeMismatchException e) {
        Class<?> type = e.getRequiredType();
        String allowed = type != null && type.isEnum()
                ? " - must be one of " + Arrays.toString(type.getEnumConstants())
                : "";
        return ResponseEntity.badRequest().body(ApiResponse.error("Invalid " + e.getName() + " '" + e.getValue() + "'" + allowed));
    }
}
