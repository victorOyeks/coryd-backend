package org.coryd.notification.broadcast;

import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.coryd.common.response.ApiResponse;
import org.coryd.common.response.PagedResponse;
import org.coryd.common.security.CoRydUserDetails;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Broadcasts for the Ops Console - ops-service only, with the admin's own token; OPS_ADMIN only. */
@Hidden
@RestController
@RequestMapping("/internal/ops/broadcasts")
@RequiredArgsConstructor
public class InternalOpsBroadcastController {

    private final BroadcastService service;

    @PostMapping("/preview")
    public ApiResponse<BroadcastDtos.Preview> preview(@AuthenticationPrincipal CoRydUserDetails principal,
                                                      @RequestBody(required = false) BroadcastDtos.ComposeRequest body) {
        OpsAdmin.of(principal);
        return ApiResponse.ok(service.preview(body));
    }

    @PostMapping
    public ApiResponse<BroadcastDtos.View> create(@AuthenticationPrincipal CoRydUserDetails principal,
                                                  @RequestBody(required = false) BroadcastDtos.ComposeRequest body) {
        OpsAdmin admin = OpsAdmin.of(principal);
        BroadcastDtos.View view = service.create(body, admin);
        return ApiResponse.ok(view.deferredForQuietHours() ? "Scheduled for after quiet hours" : "Broadcast scheduled", view);
    }

    @GetMapping
    public ApiResponse<PagedResponse<BroadcastDtos.View>> list(@AuthenticationPrincipal CoRydUserDetails principal,
                                                               @RequestParam(required = false) List<Broadcast.Status> status,
                                                               @RequestParam(defaultValue = "0") int page,
                                                               @RequestParam(defaultValue = "20") int size) {
        OpsAdmin.of(principal);
        return ApiResponse.ok(service.list(status, page, size));
    }

    @GetMapping("/{id}")
    public ApiResponse<BroadcastDtos.View> detail(@AuthenticationPrincipal CoRydUserDetails principal, @PathVariable String id) {
        OpsAdmin.of(principal);
        return ApiResponse.ok(service.detail(id));
    }

    @GetMapping("/{id}/recipients")
    public ApiResponse<PagedResponse<BroadcastDtos.RecipientView>> recipients(@AuthenticationPrincipal CoRydUserDetails principal,
                                                                             @PathVariable String id,
                                                                             @RequestParam(required = false) BroadcastRecipient.Status status,
                                                                             @RequestParam(defaultValue = "0") int page,
                                                                             @RequestParam(defaultValue = "50") int size) {
        OpsAdmin.of(principal);
        return ApiResponse.ok(service.recipients(id, status, page, size));
    }

    @PostMapping("/{id}/cancel")
    public ApiResponse<BroadcastDtos.View> cancel(@AuthenticationPrincipal CoRydUserDetails principal, @PathVariable String id,
                                                  @RequestBody(required = false) BroadcastDtos.CancelRequest body) {
        return ApiResponse.ok("Broadcast cancelled", service.cancel(id, body, OpsAdmin.of(principal)));
    }
}
