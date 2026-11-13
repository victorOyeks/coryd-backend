package org.coryd.trip.settings;

import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.coryd.common.kafka.events.PlatformSettingChangedEvent;
import org.coryd.common.response.ApiResponse;
import org.coryd.common.response.PagedResponse;
import org.coryd.common.security.CoRydUserDetails;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Platform settings - service-to-service only (/internal/** is key-gated). /internal/settings is every service's snapshot
 * (system token); /internal/ops/settings is the console's, through ops-service with the admin's own token, OPS_ADMIN only.
 */
@Hidden
@RestController
@RequiredArgsConstructor
public class InternalSettingsController {

    private final PlatformSettingsService service;

    @GetMapping("/internal/settings")
    public ApiResponse<List<PlatformSettingChangedEvent>> snapshot() {
        return ApiResponse.ok(service.fetchAll());
    }

    @GetMapping("/internal/ops/settings")
    public ApiResponse<SettingsDtos.SettingsView> list(@AuthenticationPrincipal CoRydUserDetails principal) {
        OpsAdmin.of(principal);
        return ApiResponse.ok(service.list());
    }

    @GetMapping("/internal/ops/settings/history")
    public ApiResponse<PagedResponse<SettingsDtos.Change>> allHistory(@AuthenticationPrincipal CoRydUserDetails principal,
                                                                     @RequestParam(defaultValue = "0") int page,
                                                                     @RequestParam(defaultValue = "20") int size) {
        OpsAdmin.of(principal);
        return ApiResponse.ok(service.history(null, page, size));
    }

    @GetMapping("/internal/ops/settings/{key}")
    public ApiResponse<SettingsDtos.Setting> one(@AuthenticationPrincipal CoRydUserDetails principal, @PathVariable String key) {
        OpsAdmin.of(principal);
        return ApiResponse.ok(service.get(key));
    }

    @GetMapping("/internal/ops/settings/{key}/history")
    public ApiResponse<PagedResponse<SettingsDtos.Change>> history(@AuthenticationPrincipal CoRydUserDetails principal, @PathVariable String key,
                                                                  @RequestParam(defaultValue = "0") int page,
                                                                  @RequestParam(defaultValue = "20") int size) {
        OpsAdmin.of(principal);
        return ApiResponse.ok(service.history(key, page, size));
    }

    @PutMapping("/internal/ops/settings/{key}")
    public ApiResponse<SettingsDtos.UpdateResult> update(@AuthenticationPrincipal CoRydUserDetails principal, @PathVariable String key,
                                                         @RequestBody SettingsDtos.UpdateRequest body) {
        SettingsDtos.UpdateResult result = service.update(key, body, OpsAdmin.of(principal));
        return ApiResponse.ok(result.setting().scheduled() != null ? "Change scheduled" : "Setting changed", result);
    }

    @PostMapping("/internal/ops/settings/{key}/cancel-scheduled")
    public ApiResponse<SettingsDtos.UpdateResult> cancel(@AuthenticationPrincipal CoRydUserDetails principal, @PathVariable String key,
                                                         @RequestBody(required = false) SettingsDtos.ReasonRequest body) {
        return ApiResponse.ok("Scheduled change cancelled", service.cancelScheduled(key, body, OpsAdmin.of(principal)));
    }

    @PostMapping("/internal/ops/settings/{key}/reset")
    public ApiResponse<SettingsDtos.UpdateResult> reset(@AuthenticationPrincipal CoRydUserDetails principal, @PathVariable String key,
                                                        @RequestBody(required = false) SettingsDtos.ReasonRequest body) {
        return ApiResponse.ok("Back to the default", service.reset(key, body, OpsAdmin.of(principal)));
    }
}
