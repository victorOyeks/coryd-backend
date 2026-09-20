package org.coryd.common.response;

import java.time.Instant;
import java.util.UUID;

public record ApiResponse<T>(String responseId, Instant timestamp, boolean success, String message, T data) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(UUID.randomUUID().toString(), Instant.now(), true, null, data);
    }

    public static <T> ApiResponse<T> ok(String message, T data) {
        return new ApiResponse<>(UUID.randomUUID().toString(), Instant.now(), true, message, data);
    }

    public static <T> ApiResponse<T> error(String message) {
        return new ApiResponse<>(UUID.randomUUID().toString(), Instant.now(), false, message, null);
    }

    public static <T> ApiResponse<T> error(String message, T data) {
        return new ApiResponse<>(UUID.randomUUID().toString(), Instant.now(), false, message, data);
    }
}

