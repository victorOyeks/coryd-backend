package org.coryd.ops.service;

import lombok.RequiredArgsConstructor;
import org.coryd.common.enums.VerificationQueueTab;
import org.coryd.common.response.PagedResponse;
import org.coryd.common.security.CoRydUserDetails;
import org.coryd.common.util.Names;
import org.coryd.ops.client.IdentityServiceClient;
import org.coryd.ops.client.ProfileServiceClient;
import org.coryd.ops.dto.DriverSummary;
import org.coryd.ops.dto.VerificationDecisionRequest;
import org.coryd.ops.dto.VerificationQueueCountsResponse;
import org.coryd.ops.dto.VerificationQueueItem;
import org.coryd.ops.dto.VerificationStatusResponse;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/** Ops Console module 2 - the driver verification queue, a driver's four items, and the decision. */
@Service
@RequiredArgsConstructor
public class DriverVerificationOpsService {

    private final ProfileServiceClient profileServiceClient;
    private final IdentityServiceClient identityServiceClient;

    /** A null tab is "All". Longest-waiting driver first. */
    public PagedResponse<VerificationQueueItem> queue(VerificationQueueTab tab, String search, Pageable pageable) {
        ProfileServiceClient.QueuePage page = profileServiceClient.queue(tab, search, pageable.getPageNumber(), pageable.getPageSize());
        Map<String, IdentityServiceClient.Contact> phones = identityServiceClient.contacts(
                page.content().stream().map(ProfileServiceClient.QueueRow::driverId).toList());
        List<VerificationQueueItem> items = page.content().stream().map(row -> {
            IdentityServiceClient.Contact contact = phones.get(row.driverId());
            DriverSummary driver = new DriverSummary(row.driverId(), TripOpsService.ref("D", row.driverId()),
                    Names.join(row.firstName(), row.lastName()), contact == null ? null : contact.phone(), row.photoUrl());
            return new VerificationQueueItem(driver, row.tab(), row.waitingSince(), row.items());
        }).toList();
        return new PagedResponse<>(items, page.page(), page.size(), page.totalElements(), page.totalPages(), page.last());
    }

    public VerificationQueueCountsResponse counts(String search) {
        return profileServiceClient.queueCounts(search);
    }

    public VerificationStatusResponse driver(String driverId) {
        return withContact(driverId, profileServiceClient.verificationStatus(driverId));
    }

    /** reviewedBy is always the signed-in admin, so the audit trail can't be written in someone else's name. */
    public VerificationStatusResponse decide(String driverId, VerificationDecisionRequest request, CoRydUserDetails admin) {
        String name = admin.getFullName();
        return withContact(driverId, profileServiceClient.decideVerification(driverId, request.decisions(),
                name == null || name.isBlank() ? admin.getUserId() : name));
    }

    private VerificationStatusResponse withContact(String driverId, VerificationStatusResponse status) {
        if (status == null) {
            return null;
        }
        VerificationStatusResponse.DriverDetails d = status.driver();
        IdentityServiceClient.Contact contact = identityServiceClient.contacts(List.of(driverId)).get(driverId);
        return status.withDriver(new VerificationStatusResponse.DriverDetails(driverId, TripOpsService.ref("D", driverId),
                d == null ? null : d.firstName(), d == null ? null : d.lastName(), contact == null ? null : contact.phone(),
                d == null ? null : d.photoUrl(), d == null ? null : d.licenceNumber(), d == null ? null : d.dateOfBirth()));
    }
}
