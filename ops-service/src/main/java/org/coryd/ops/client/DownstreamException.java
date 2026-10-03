package org.coryd.ops.client;

import lombok.Getter;
import org.springframework.http.HttpStatusCode;

/** A refusal from the service that owns the data - relayed to the console with its own status and message. */
@Getter
public class DownstreamException extends RuntimeException {

    private final HttpStatusCode status;

    public DownstreamException(HttpStatusCode status, String message) {
        super(message);
        this.status = status;
    }
}
