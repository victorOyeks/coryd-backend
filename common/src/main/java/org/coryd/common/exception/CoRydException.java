package org.coryd.common.exception;

public class CoRydException extends RuntimeException {

    public CoRydException(String message) {
        super(message);
    }

    public CoRydException(String message, Throwable cause) {
        super(message, cause);
    }
}
