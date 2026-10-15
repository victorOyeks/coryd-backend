package org.coryd.common.exception;

import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import lombok.extern.slf4j.Slf4j;
import org.coryd.common.response.ApiResponse;
import org.coryd.common.response.RoleNotHeldError;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Without this, every custom exception below falls through to Spring Boot's
 * default handling and surfaces as an opaque 500 instead of the intended
 * status code - picked up automatically by every service since they all
 * component-scan org.coryd.common already.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<ApiResponse<Void>> handleBadRequest(BadRequestException e) {
        return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotFound(ResourceNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error(e.getMessage()));
    }

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnauthorized(UnauthorizedException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.error(e.getMessage()));
    }

    @ExceptionHandler(RoleNotHeldException.class)
    public ResponseEntity<ApiResponse<RoleNotHeldError>> handleRoleNotHeld(RoleNotHeldException e) {
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(e.getMessage(), new RoleNotHeldError(RoleNotHeldException.CODE, e.getHeldRoles())));
    }

    @ExceptionHandler(PaymentException.class)
    public ResponseEntity<ApiResponse<Void>> handlePayment(PaymentException e) {
        return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED).body(ApiResponse.error(e.getMessage()));
    }

    @ExceptionHandler(ExternalServiceException.class)
    public ResponseEntity<ApiResponse<Void>> handleExternalService(ExternalServiceException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(ApiResponse.error(e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .collect(Collectors.joining(", "));
        return ResponseEntity.badRequest().body(ApiResponse.error(message));
    }

    /**
     * A body that can't be read - broken JSON, or a value that doesn't fit its field, like an enum
     * the API doesn't have. It's the caller's mistake, so a 400 that names the field and, for an
     * enum, the values it takes - never the catch-all's 500.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadable(HttpMessageNotReadableException e) {
        String message = "The request body couldn't be read";
        if (e.getCause() instanceof InvalidFormatException invalid) {
            String field = invalid.getPath().stream()
                    .map(ref -> ref.getFieldName() != null ? ref.getFieldName() : "[" + ref.getIndex() + "]")
                    .collect(Collectors.joining("."));
            Class<?> type = invalid.getTargetType();
            message = type != null && type.isEnum()
                    ? field + " must be one of: " + Arrays.stream(type.getEnumConstants()).map(Object::toString).collect(Collectors.joining(", "))
                    : field + " has an invalid value";
        }
        log.warn("Rejected unreadable request body: {}", e.getMostSpecificCause().getMessage());
        return ResponseEntity.badRequest().body(ApiResponse.error(message));
    }

    /**
     * Spring Security raises these, and only ExceptionTranslationFilter knows how to answer them
     * with the configured entry point / access-denied handler. They must not be swallowed by the
     * catch-all below, so rethrow and let them travel back out to the filter chain.
     */
    @ExceptionHandler({AuthenticationException.class, AccessDeniedException.class})
    public void rethrowSecurityException(RuntimeException e) {
        throw e;
    }

    /**
     * Anything unmatched above would otherwise escape the DispatcherServlet, and the servlet
     * container answers that by re-dispatching to /error - a second trip through the security
     * filter chain with no authentication, which replaces the real 500 with a bogus 401.
     * Handling it here keeps the response inside MVC. The client gets a generic message; the
     * stack trace goes to the log only.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception e) {
        // Spring's own request-binding and dispatch failures - missing or unconvertible query
        // params, unreadable body, wrong HTTP method, unmatched path - all implement
        // ErrorResponse and already carry the correct 4xx. Declaring an Exception catch-all
        // shadows DefaultHandlerExceptionResolver, which used to assign those statuses, so map
        // them here instead of reporting a caller's mistake as a server fault.
        if (e instanceof ErrorResponse errorResponse) {
            HttpStatusCode status = errorResponse.getStatusCode();
            String detail = errorResponse.getBody().getDetail();
            String message = detail != null ? detail : e.getMessage();
            if (status.is5xxServerError()) {
                log.error("Request failed with {}", status, e);
            } else {
                log.warn("Rejected request with {}: {}", status, message);
            }
            return ResponseEntity.status(status).body(ApiResponse.error(message));
        }

        log.error("Unhandled exception", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error("An unexpected error occurred"));
    }
}
