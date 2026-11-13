package org.coryd.profile.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.coryd.common.response.ApiResponse;
import org.coryd.common.security.CoRydUserDetails;
import org.coryd.profile.dto.CreateDriverDocumentRequest;
import org.coryd.profile.entity.DriverDocument;
import org.coryd.profile.security.DriverDocumentAccess;
import org.coryd.profile.service.DriverDocumentService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/drivers")
@RequiredArgsConstructor
public class DriverDocumentController {

    private final DriverDocumentService driverDocumentService;
    private final DriverDocumentAccess driverDocumentAccess;

    @PostMapping("/me/documents")
    public ApiResponse<DriverDocument> create(@AuthenticationPrincipal CoRydUserDetails principal,
                                               @Valid @RequestBody CreateDriverDocumentRequest request) {
        return ApiResponse.ok(driverDocumentService.create(principal.getUserId(), request));
    }

    /** The driver themself, another service (internal key), or an ops admin with VERIFICATION_VIEW - else 403. */
    @GetMapping("/{driverId}/documents")
    public ApiResponse<List<DriverDocument>> list(@PathVariable String driverId, @AuthenticationPrincipal CoRydUserDetails principal,
                                                  HttpServletRequest request) {
        driverDocumentAccess.requireCanRead(driverId, principal, request);
        return ApiResponse.ok(driverDocumentService.findByDriver(driverId));
    }
}
