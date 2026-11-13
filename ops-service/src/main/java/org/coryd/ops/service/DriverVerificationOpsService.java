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
        return queue(tab, search, pageable, null, null);
    }

    /** sort is a column header - longest-waiting first when left out. */
    public PagedResponse<VerificationQueueItem> queue(VerificationQueueTab tab, String search, Pageable pageable,
                                                      org.coryd.common.enums.VerificationQueueSort sort,
                                                      org.springframework.data.domain.Sort.Direction direction) {
        ProfileServiceClient.QueuePage page = profileServiceClient.queue(tab, search, pageable.getPageNumber(), pageable.getPageSize(),
                sort, direction);
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

    /** The review screen: the four items with their files, plus the reviewer's marks. */
    public VerificationStatusResponse driver(String driverId) {
        return withReview(driverId, withContact(driverId, profileServiceClient.verificationStatus(driverId)));
    }

    /** Verified, or Flagged with the reason the driver will see - then the review screen as it now stands. */
    public VerificationStatusResponse mark(String driverId, org.coryd.common.enums.ReviewableItem item,
                                           org.coryd.ops.dto.ReviewMarkRequest request, CoRydUserDetails admin) {
        org.coryd.ops.dto.VerificationReview review = profileServiceClient.markVerificationItem(driverId, item, request.mark(),
                request.note(), reviewer(admin));
        return withContact(driverId, profileServiceClient.verificationStatus(driverId)).withReview(review);
    }

    /** "Reset to Default". */
    public VerificationStatusResponse reset(String driverId, org.coryd.common.enums.ReviewableItem item) {
        org.coryd.ops.dto.VerificationReview review = profileServiceClient.resetVerificationItem(driverId, item);
        return withContact(driverId, profileServiceClient.verificationStatus(driverId)).withReview(review);
    }

    public VerificationStatusResponse approve(String driverId, org.coryd.ops.dto.ApproveDriverRequest request, CoRydUserDetails admin) {
        VerificationStatusResponse status = profileServiceClient.approveVerification(driverId, request == null ? null : request.note(), reviewer(admin));
        return withReview(driverId, withContact(driverId, status));
    }

    /** One document approved on its own - over Smile ID's failed licence check too. Then the review page as it stands. */
    public VerificationStatusResponse approveItem(String driverId, org.coryd.common.enums.ReviewableItem item, String note,
                                                  CoRydUserDetails admin) {
        VerificationStatusResponse status = profileServiceClient.approveVerificationItem(driverId, item, note, reviewer(admin));
        return withReview(driverId, withContact(driverId, status));
    }

    /** One document sent back to the driver with the reason they'll see. Then the review page as it stands. */
    public VerificationStatusResponse requestResubmission(String driverId, org.coryd.common.enums.ReviewableItem item, String reason,
                                                          CoRydUserDetails admin) {
        VerificationStatusResponse status = profileServiceClient.requestResubmission(driverId, item, reason, reviewer(admin));
        return withReview(driverId, withContact(driverId, status));
    }

    /** Save Changes: the marks as they stand - flagged rejected (an approved document loses its approval), verified approved. */
    public VerificationStatusResponse save(String driverId, CoRydUserDetails admin) {
        VerificationStatusResponse status = profileServiceClient.saveVerification(driverId, reviewer(admin));
        return withReview(driverId, withContact(driverId, status));
    }

    public VerificationStatusResponse reject(String driverId, CoRydUserDetails admin) {
        VerificationStatusResponse status = profileServiceClient.rejectVerification(driverId, reviewer(admin));
        return withReview(driverId, withContact(driverId, status));
    }

    public com.fasterxml.jackson.databind.JsonNode licence(String driverId) {
        return profileServiceClient.licenceReview(driverId);
    }

    public List<org.coryd.ops.dto.VerificationDecisionHistoryItem> history(String driverId) {
        return profileServiceClient.verificationHistory(driverId);
    }

    /** The marks are extra - if they can't be loaded, the rest of the screen still shows. */
    private VerificationStatusResponse withReview(String driverId, VerificationStatusResponse status) {
        if (status == null) {
            return null;
        }
        try {
            return status.withReview(profileServiceClient.verificationReview(driverId));
        } catch (Exception e) {
            return status;
        }
    }

    private static String reviewer(CoRydUserDetails admin) {
        String name = admin.getFullName();
        return name == null || name.isBlank() ? admin.getUserId() : name;
    }

    /** reviewedBy is always the signed-in admin, so the audit trail can't be written in someone else's name. */
    public VerificationStatusResponse decide(String driverId, VerificationDecisionRequest request, CoRydUserDetails admin) {
        return withReview(driverId, withContact(driverId, profileServiceClient.decideVerification(driverId, request.decisions(), reviewer(admin))));
    }

    private VerificationStatusResponse withContact(String driverId, VerificationStatusResponse status) {
        if (status == null) {
            return null;
        }
        VerificationStatusResponse.DriverDetails d = status.driver();
        IdentityServiceClient.Contact contact = identityServiceClient.contacts(List.of(driverId)).get(driverId);
        return status.withDriver(new VerificationStatusResponse.DriverDetails(driverId, TripOpsService.ref("D", driverId),
                d == null ? null : d.firstName(), d == null ? null : d.lastName(), contact == null ? null : contact.phone(),
                d == null ? null : d.photoUrl(), d == null ? null : d.masked(), d == null ? null : d.year(), null, null));
    }
}
