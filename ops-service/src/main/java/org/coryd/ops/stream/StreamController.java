package org.coryd.ops.stream;

import lombok.RequiredArgsConstructor;
import org.coryd.common.exception.UnauthorizedException;
import org.coryd.common.response.ApiResponse;
import org.coryd.common.security.CoRydUserDetails;
import org.coryd.common.security.revocation.TokenRevocationRegistry;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

import jakarta.servlet.http.HttpServletResponse;

/**
 * The console's live feed: SOS raised and updated, unanswered safety checks, badge counts. See
 * docs/ops-console/safety-support.md, "Live updates".
 */
@RestController
@RequestMapping("/stream")
@RequiredArgsConstructor
public class StreamController {

    private final StreamTokenService tokens;
    private final OpsEventStream stream;
    private final TokenRevocationRegistry revocations;

    /**
     * OPS_ADMIN only (the normal token). The result opens GET /stream?token=… within its lifetime. An admin whose sessions
     * were ended gets none (JwtFilter already refuses their token; checked again here).
     */
    @PostMapping("/token")
    public ApiResponse<StreamTokenService.Issued> token(@AuthenticationPrincipal CoRydUserDetails admin) {
        String refusal = revocations.refusal(admin.getUserId(), admin.getIssuedAtMs());
        if (refusal != null) {
            throw new UnauthorizedException(refusal);
        }
        String name = admin.getFullName();
        return ApiResponse.ok(tokens.issue(admin.getUserId(), name == null || name.isBlank() ? admin.getUserId() : name,
                admin.getIssuedAtMs()));
    }

    /**
     * Open to anyone at the HTTP layer - EventSource can't send a header - and checked here: a valid stream token, or (for a
     * non-browser client) an OPS_ADMIN bearer token. Last-Event-ID is sent by EventSource itself on reconnect.
     */
    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter open(@RequestParam(required = false) String token,
                           @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId,
                           @RequestParam(value = "lastEventId", required = false) String lastEventIdParam,
                           @AuthenticationPrincipal CoRydUserDetails principal,
                           HttpServletResponse response) {
        String adminId;
        long sessionIssuedAtMs;
        try {
            if (token != null && !token.isBlank()) {
                StreamTokenService.Claims claims = tokens.verify(token);
                adminId = claims.adminId();
                sessionIssuedAtMs = claims.sessionIssuedAtMs();
            } else if (principal != null && principal.getAuthorities().stream().anyMatch(a -> "ROLE_OPS_ADMIN".equals(a.getAuthority()))) {
                adminId = principal.getUserId();
                sessionIssuedAtMs = principal.getIssuedAtMs();
            } else {
                throw new UnauthorizedException("A stream token is required - get one from POST /ops/stream/token");
            }
            // The session the stream token came from was ended since (suspended, deactivated, signed out, role changed).
            String refusal = revocations.refusal(adminId, sessionIssuedAtMs);
            if (refusal != null) {
                throw new UnauthorizedException(refusal);
            }
        } catch (UnauthorizedException e) {
            // A plain 401 with the reason in a header: the caller asked for an event stream, not JSON. EventSource stops
            // retrying on a non-200 - the console gets a new token and opens a new EventSource.
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setHeader("X-Stream-Error", e.getMessage());
            return null;
        }
        // nginx would otherwise buffer the stream; no caching anywhere on the way.
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Cache-Control", "no-cache, no-transform");
        return stream.connect(adminId, sessionIssuedAtMs, lastEventId != null ? lastEventId : lastEventIdParam);
    }

    /** A console that closed its tab mid-send: nothing to answer, and not an error worth a JSON body (it couldn't be written anyway). */
    @ExceptionHandler({AsyncRequestNotUsableException.class, IOException.class})
    public void consoleWentAway() {
        // the connection is gone
    }
}
