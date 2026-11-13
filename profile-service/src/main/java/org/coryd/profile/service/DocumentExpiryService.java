package org.coryd.profile.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.common.enums.DocumentExpirySort;
import org.coryd.common.enums.DocumentExpiryStatus;
import org.coryd.common.enums.DocumentExpiryTab;
import org.coryd.common.enums.VerificationStatus;
import org.coryd.common.exception.BadRequestException;
import org.coryd.common.exception.ResourceNotFoundException;
import org.coryd.common.kafka.KafkaTopics;
import org.coryd.common.kafka.events.DocumentExpiringEvent;
import org.coryd.common.response.PagedResponse;
import org.coryd.profile.dto.DocumentExpiryCounts;
import org.coryd.profile.dto.DocumentExpiryNotifyResult;
import org.coryd.profile.dto.DocumentExpiryView;
import org.coryd.profile.entity.DriverDocument;
import org.coryd.profile.entity.DriverIndex;
import org.coryd.profile.repository.DriverDocumentRepository;
import org.coryd.profile.repository.DriverIndexRepository;
import org.coryd.profile.service.DocumentExpirySpecs.Filter;
import org.coryd.profile.service.DocumentExpirySpecs.Window;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.Sort;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Insurance and roadworthiness certificates against their expiry dates: the Ops Console's Document Expiry Tracker, ops
 * reminding drivers, and the nightly job - 14- and 3-day reminders, and expiry, which stops the driver posting until a
 * current document is approved. Everything the tracker shows is filtered, sorted and paged in the database.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentExpiryService {

    public static final ZoneId LAGOS = ZoneId.of("Africa/Lagos");
    public static final String SYSTEM = "system";
    static final int SOON_DAYS = 14;
    static final int FINAL_REMINDER_DAYS = 3;
    static final int BATCH = 200;
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("d MMM, h:mm a", java.util.Locale.ENGLISH).withZone(LAGOS);

    private final DriverDocumentRepository documentRepository;
    private final DriverIndexRepository driverIndexRepository;
    private final VerificationAggregationService verificationAggregationService;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final org.springframework.transaction.PlatformTransactionManager transactionManager;

    /** How long after one reminder before ops can send the driver another. */
    @Value("${document-expiry.notify-cooldown:PT24H}")
    private Duration notifyCooldown = Duration.ofHours(24);

    /** Configured reminder days - the defaults for ops' documents.* settings (docs/ops-console/settings.md). */
    @Value("${document-expiry.reminder-days:14}")
    private int reminderDays = SOON_DAYS;

    @Value("${document-expiry.final-reminder-days:3}")
    private int finalReminderDays = FINAL_REMINDER_DAYS;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private org.coryd.common.settings.PlatformSettings platformSettings;

    /** Days before expiry of the first reminder - also the tracker's "expiring soon" window. */
    int soonDays() {
        return org.coryd.common.settings.PlatformSettings.integer(platformSettings,
                org.coryd.common.settings.PlatformSetting.DOCUMENT_REMINDER_DAYS, reminderDays);
    }

    int finalReminderDays() {
        int last = org.coryd.common.settings.PlatformSettings.integer(platformSettings,
                org.coryd.common.settings.PlatformSetting.DOCUMENT_FINAL_REMINDER_DAYS, finalReminderDays);
        return Math.max(0, Math.min(last, soonDays() - 1));
    }

    /** Today and the end of the "expiring soon" window, with the reminder days in effect. */
    Window currentWindow() {
        return window(soonDays());
    }

    // ---- the tracker -----------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public PagedResponse<DocumentExpiryView> tracker(DocumentExpiryTab tab, String search, DocumentExpirySort sort,
                                                     Sort.Direction direction, int page, int size) {
        Window window = currentWindow();
        Page<DriverDocument> rows = documentRepository.findAll(DocumentExpirySpecs.matching(Filter.tracked(tab, search), window, sort, direction),
                PageRequest.of(Math.max(0, page), Math.max(1, Math.min(size, 50))));
        Map<String, DriverIndex> drivers = drivers(rows.getContent());
        return PagedResponse.from(rows.map(doc -> view(doc, drivers.get(doc.getDriverId()), window)));
    }

    @Transactional(readOnly = true)
    public DocumentExpiryCounts counts(String search) {
        Window window = currentWindow();
        return new DocumentExpiryCounts(
                count(Filter.tracked(null, search), window),
                count(Filter.tracked(DocumentExpiryTab.EXPIRING_SOON, search), window),
                count(Filter.tracked(DocumentExpiryTab.EXPIRED, search), window),
                count(Filter.tracked(DocumentExpiryTab.VERIFIED, search), window),
                count(new Filter(true, null, search, false, null), window)
                        - count(new Filter(true, null, search, true, null), window)
                        - count(Filter.tracked(DocumentExpiryTab.VERIFIED, search), window),
                count(new Filter(false, null, search, false, null), window));
    }

    @Transactional(readOnly = true)
    public DocumentExpiryView document(String documentId) {
        DriverDocument doc = documentRepository.findById(documentId)
                .orElseThrow(() -> new ResourceNotFoundException("Document not found"));
        return view(doc, driverIndexRepository.findById(doc.getDriverId()).orElse(null), currentWindow());
    }

    // ---- ops reminders ---------------------------------------------------------------------------

    /** The row's Notify button. */
    @Transactional
    public DocumentExpiryView notifyDriver(String documentId, String by) {
        DriverDocument doc = documentRepository.findById(documentId)
                .orElseThrow(() -> new ResourceNotFoundException("Document not found"));
        Window window = currentWindow();
        if (doc.getExpiresOn() == null || doc.getExpiresOn().isAfter(window.soonUntil())) {
            throw new BadRequestException("Only a document expiring within " + soonDays() + " days, or already expired, can be sent a reminder");
        }
        if (notifiedRecently(doc.getLastNotifiedAt())) {
            throw new BadRequestException("The driver was reminded at " + WHEN.format(doc.getLastNotifiedAt())
                    + " - you can remind them again after " + WHEN.format(doc.getLastNotifiedAt().plus(notifyCooldown)));
        }
        send(doc, window);
        documentRepository.markNotified(List.of(doc.getId()), Instant.now(), by);
        return document(documentId);
    }

    /**
     * Notify all: every expiring or expired document the tab and search show, except those reminded within the cool-down.
     * Each batch is recorded in its own transaction and its pushes go once that commits, so a failure part-way never
     * leaves drivers pushed but not recorded as reminded.
     */
    public DocumentExpiryNotifyResult notifyAll(DocumentExpiryTab tab, String search, String by) {
        Window window = currentWindow();
        Instant cutoff = Instant.now().minus(notifyCooldown);
        long notifiable = inTransaction(() -> count(new Filter(true, tab, search, true, null), window));
        Filter due = new Filter(true, tab, search, true, cutoff);
        int notified = 0;
        while (true) {
            List<DocumentExpiringEvent> pushes = inTransaction(() -> {
                List<DriverDocument> batch = documentRepository.findAll(DocumentExpirySpecs.matching(due, window, null, null),
                        PageRequest.of(0, BATCH)).getContent();
                if (!batch.isEmpty()) {
                    documentRepository.markNotified(ids(batch), Instant.now(), by);
                }
                return batch.stream().map(doc -> event(doc, window)).toList();
            });
            if (pushes.isEmpty()) {
                break;
            }
            pushes.forEach(this::send);
            notified += pushes.size();
        }
        return new DocumentExpiryNotifyResult(notified, Math.max(0, notifiable - notified));
    }

    // ---- the nightly job -------------------------------------------------------------------------

    /**
     * Past their date: the document becomes EXPIRED with a reason the driver sees, they're told, and trip-service stops
     * them posting. Their car is left as it is - the document holds them, and renewing it is all they need to do.
     * A batch at a time, each in its own transaction, with the pushes and trip-service's notice sent once it commits.
     */
    public int expireDue() {
        Window window = currentWindow();
        int done = 0;
        while (true) {
            Expired batch = inTransaction(() -> {
                List<DriverDocument> due = documentRepository.findDueToExpire(window.today(), PageRequest.of(0, BATCH)).getContent();
                Map<String, String> blocked = new LinkedHashMap<>();
                for (DriverDocument doc : due) {
                    doc.setStatus(VerificationStatus.EXPIRED);
                    doc.setRejectionReason("Expired on " + doc.getExpiresOn() + " - please upload a current " + label(doc));
                    documentRepository.save(doc);
                    blocked.putIfAbsent(doc.getDriverId(), label(doc) + " expired");
                }
                if (!due.isEmpty()) {
                    documentRepository.flush();
                    documentRepository.markNotified(ids(due), Instant.now(), SYSTEM);
                }
                return new Expired(due.stream().map(doc -> event(doc, window)).toList(), blocked);
            });
            if (batch.pushes().isEmpty()) {
                return done;
            }
            batch.pushes().forEach(this::send);
            batch.blocked().forEach((driverId, reason) -> verificationAggregationService.publishPostingBlocked(driverId, reason, SYSTEM));
            done += batch.pushes().size();
        }
    }

    private record Expired(List<DocumentExpiringEvent> pushes, Map<String, String> blocked) {
    }

    /** The 3-day reminder, then the 14-day one - each sent once per document, with the days actually left. */
    public int remindDue() {
        Window window = currentWindow();
        int sent = remind(p -> documentRepository.findDueThreeDayReminder(window.today(), window.today().plusDays(finalReminderDays()), p),
                ids -> documentRepository.markThreeDayReminderSent(ids, Instant.now(), SYSTEM), window);
        sent += remind(p -> documentRepository.findDueFourteenDayReminder(window.today(), window.soonUntil(), p),
                ids -> documentRepository.markFourteenDayReminderSent(ids, Instant.now(), SYSTEM), window);
        return sent;
    }

    /**
     * Tells trip-service again that every driver holding an expired document can't post - a lost message, or a driver
     * expired before this was in place, is put right by the next night.
     */
    @Transactional(readOnly = true)
    public int reassertBlocked() {
        int done = 0;
        Slice<String> page = documentRepository.findDriverIdsWithExpiredDocuments(PageRequest.of(0, BATCH));
        while (true) {
            page.getContent().forEach(driverId -> verificationAggregationService.publishPostingBlocked(driverId, "Document expired", SYSTEM));
            done += page.getNumberOfElements();
            if (!page.hasNext()) {
                return done;
            }
            page = documentRepository.findDriverIdsWithExpiredDocuments(page.nextPageable());
        }
    }

    /** A batch is marked in its own transaction, and its pushes go once that commits. */
    private int remind(Function<Pageable, Slice<DriverDocument>> due, Function<List<String>, Integer> mark, Window window) {
        int sent = 0;
        while (true) {
            List<DocumentExpiringEvent> pushes = inTransaction(() -> {
                List<DriverDocument> batch = due.apply(PageRequest.of(0, BATCH)).getContent();
                if (!batch.isEmpty()) {
                    mark.apply(ids(batch));
                }
                return batch.stream().map(doc -> event(doc, window)).toList();
            });
            if (pushes.isEmpty()) {
                return sent;
            }
            pushes.forEach(this::send);
            sent += pushes.size();
        }
    }

    // ---- helpers ---------------------------------------------------------------------------------

    static Window window() {
        return window(SOON_DAYS);
    }

    static Window window(int soonDays) {
        LocalDate today = LocalDate.now(LAGOS);
        return new Window(today, today.plusDays(soonDays));
    }

    private long count(Filter filter, Window window) {
        return documentRepository.count(DocumentExpirySpecs.matching(filter, window, null, null));
    }

    /** The driver's push - "expires in N days", or "expired" once past. */
    private void send(DriverDocument doc, Window window) {
        send(event(doc, window));
    }

    private void send(DocumentExpiringEvent event) {
        kafkaTemplate.send(KafkaTopics.DOCUMENT_EXPIRING, event.getDriverId(), event);
    }

    private static DocumentExpiringEvent event(DriverDocument doc, Window window) {
        return DocumentExpiringEvent.builder()
                .driverId(doc.getDriverId())
                .documentType(doc.getDocumentType().name())
                .expiresOn(doc.getExpiresOn())
                .daysRemaining((int) ChronoUnit.DAYS.between(window.today(), doc.getExpiresOn()))
                .build();
    }

    private <T> T inTransaction(java.util.function.Supplier<T> work) {
        return new org.springframework.transaction.support.TransactionTemplate(transactionManager).execute(status -> work.get());
    }

    private boolean notifiedRecently(Instant lastNotifiedAt) {
        return lastNotifiedAt != null && lastNotifiedAt.isAfter(Instant.now().minus(notifyCooldown));
    }

    private Map<String, DriverIndex> drivers(List<DriverDocument> docs) {
        return driverIndexRepository.findAllById(docs.stream().map(DriverDocument::getDriverId).distinct().toList()).stream()
                .collect(Collectors.toMap(DriverIndex::getDriverId, Function.identity()));
    }

    private static List<String> ids(List<DriverDocument> docs) {
        return docs.stream().map(DriverDocument::getId).toList();
    }

    private static String label(DriverDocument doc) {
        String label = DocumentExpirySpecs.LABELS.getOrDefault(doc.getDocumentType(), doc.getDocumentType().name());
        return Character.toUpperCase(label.charAt(0)) + label.substring(1);
    }

    private DocumentExpiryView view(DriverDocument doc, DriverIndex driver, Window window) {
        DocumentExpiryStatus status = DocumentExpirySpecs.statusOf(doc, window);
        boolean notifiable = status == DocumentExpiryStatus.EXPIRED || status == DocumentExpiryStatus.EXPIRING_SOON;
        return new DocumentExpiryView(doc.getId(), doc.getDriverId(),
                driver == null ? null : driver.getFirstName(), driver == null ? null : driver.getLastName(),
                driver == null ? null : driver.getPhone(), driver == null ? null : driver.getPhotoUrl(),
                doc.getDocumentType(), doc.getExpiresOn(),
                doc.getExpiresOn() == null ? 0 : ChronoUnit.DAYS.between(window.today(), doc.getExpiresOn()),
                status, doc.getStatus(),
                doc.getStatus() == VerificationStatus.APPROVED ? doc.getReviewedAt() : null,
                doc.getLastNotifiedAt(), doc.getLastNotifiedBy(),
                notifiable, notifiedRecently(doc.getLastNotifiedAt()));
    }
}
