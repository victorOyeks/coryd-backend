package org.coryd.ops.service;

import lombok.RequiredArgsConstructor;
import org.coryd.common.enums.DocumentExpirySort;
import org.coryd.common.enums.DocumentExpiryTab;
import org.coryd.common.enums.DocumentType;
import org.coryd.common.response.PagedResponse;
import org.coryd.common.security.CoRydUserDetails;
import org.coryd.common.util.Names;
import org.coryd.ops.client.ProfileServiceClient;
import org.coryd.ops.dto.DocumentExpiryCounts;
import org.coryd.ops.dto.DocumentExpiryItem;
import org.coryd.ops.dto.DocumentExpiryNotifyResult;
import org.coryd.ops.dto.DriverSummary;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

/**
 * Ops Console - Document Expiry Tracker. profile-service filters, sorts and pages the documents in its database and sends
 * the reminders; this shapes rows for the console and signs reminders with the admin's name.
 */
@Service
@RequiredArgsConstructor
public class DocumentExpiryOpsService {

    private final ProfileServiceClient profileServiceClient;

    public PagedResponse<DocumentExpiryItem> documents(DocumentExpiryTab tab, String search, DocumentExpirySort sort,
                                                       Sort.Direction direction, int page, int size) {
        ProfileServiceClient.ExpiryPage result = profileServiceClient.documentExpiry(tab, search, sort, direction,
                Math.max(0, page), Math.clamp(size, 1, 50));
        return new PagedResponse<>(result.content().stream().map(DocumentExpiryOpsService::row).toList(),
                result.page(), result.size(), result.totalElements(), result.totalPages(), result.last());
    }

    public DocumentExpiryCounts counts(String search) {
        return profileServiceClient.documentExpiryCounts(search);
    }

    public DocumentExpiryItem notifyDriver(String documentId, CoRydUserDetails admin) {
        return row(profileServiceClient.notifyDocument(documentId, adminName(admin)));
    }

    public DocumentExpiryNotifyResult notifyAll(DocumentExpiryTab tab, String search, CoRydUserDetails admin) {
        return profileServiceClient.notifyAllDocuments(tab, search, adminName(admin));
    }

    static String adminName(CoRydUserDetails admin) {
        String name = admin.getFullName();
        return name == null || name.isBlank() ? admin.getUserId() : name;
    }

    static String label(DocumentType type) {
        if (type == null) {
            return null;
        }
        return switch (type) {
            case INSURANCE -> "Insurance Certificate";
            case ROADWORTHINESS_CERT -> "Roadworthiness Certificate";
        };
    }

    private static DocumentExpiryItem row(ProfileServiceClient.ExpiryRow r) {
        DriverSummary driver = new DriverSummary(r.driverId(), TripOpsService.ref("D", r.driverId()),
                Names.join(r.firstName(), r.lastName()), r.phone(), r.photoUrl());
        return new DocumentExpiryItem(r.documentId(), driver, r.documentType(), label(r.documentType()), r.expiresOn(), r.daysLeft(),
                r.status(), r.verificationStatus(), r.verifiedAt(), r.lastNotifiedAt(), r.lastNotifiedBy(),
                r.notifiedRecently(), r.notifiable() && !r.notifiedRecently());
    }
}
