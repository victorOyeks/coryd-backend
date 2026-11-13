package org.coryd.ops.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.coryd.common.audit.OpsAuditCategory;
import org.coryd.common.audit.OpsAuditEntry;
import org.coryd.common.audit.OpsAuditPage;
import org.coryd.common.audit.OpsAuditQuery;
import org.coryd.common.exception.BadRequestException;
import org.coryd.ops.client.IdentityServiceClient;
import org.coryd.ops.client.OpsAuditClient;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The unified ops audit log: every owning service keeps its own audit table and pages it newest first behind
 * GET /internal/ops/audit (one filter shape, keyset cursor). This merges them - a k-way merge by time:
 *
 * <ol>
 *   <li>each source that can match the filter (by category and target type) is asked for up to {@code size} entries
 *       older than where the cursor left it;</li>
 *   <li>everything returned is merged newest first (ties: source name, then the source's own order) and the first
 *       {@code size} are the page - these are always a prefix of each source's answer, so</li>
 *   <li>the next cursor is, per source, the last entry taken from it (unchanged if none was taken), plus which sources
 *       have nothing left.</li>
 * </ol>
 *
 * Correct for any page size because every row of the true top {@code size} must be in its own source's top
 * {@code size}. Cost: up to sources x size rows read per page. No total count (it would mean counting four tables on
 * every filter change). Rows written while paging can be missed if they're older than the cursor (they aren't - audit
 * rows are written at "now").
 */
@Service
@RequiredArgsConstructor
public class OpsAuditService {

    public static final ZoneId LAGOS = ZoneId.of("Africa/Lagos");
    public static final int DEFAULT_SIZE = 50;
    public static final int MAX_SIZE = 100;
    public static final int MAX_EXPORT_ROWS = 10_000;

    /** What each source can hold - a filter outside it skips the call altogether. */
    record Source(String key, Set<OpsAuditCategory> categories, Set<String> targetTypes) {
        boolean relevant(AuditFilter f) {
            boolean category = f.categories() == null || f.categories().isEmpty() || f.categories().stream().anyMatch(categories::contains);
            boolean type = f.targetTypes() == null || f.targetTypes().isEmpty() || f.targetTypes().stream().anyMatch(targetTypes::contains);
            return category && type;
        }
    }

    static final List<Source> SOURCES = List.of(
            new Source(OpsAuditClient.IDENTITY, Set.of(OpsAuditCategory.ACCOUNT, OpsAuditCategory.TEAM), Set.of("ACCOUNT", "ADMIN")),
            new Source(OpsAuditClient.PAYMENT, Set.of(OpsAuditCategory.MONEY),
                    Set.of("BOOKING", "FARE_HOLD", "SETTLEMENT", "DRIVER", "MONEY_REQUEST", "PAYOUT_ACCOUNT")),
            new Source(OpsAuditClient.PROFILE, Set.of(OpsAuditCategory.VERIFICATION, OpsAuditCategory.PRIVACY), Set.of("VERIFICATION", "ACCOUNT")),
            new Source(OpsAuditClient.TRIP, Set.of(OpsAuditCategory.TRIPS, OpsAuditCategory.SAFETY),
                    Set.of("ROUTE", "BOOKING", "SOS", "ISSUE", "RATING")));

    public static final Set<String> TARGET_TYPES = SOURCES.stream().flatMap(s -> s.targetTypes().stream())
            .collect(Collectors.toCollection(java.util.TreeSet::new));

    /** Targets that are people - named from identity-service. */
    private static final Set<String> PERSON_TARGETS = Set.of("ACCOUNT", "ADMIN", "VERIFICATION", "DRIVER", "PAYOUT_ACCOUNT");
    private static final Map<String, String> REF_PREFIX = Map.of("ROUTE", "T", "BOOKING", "B", "SOS", "S", "DRIVER", "D",
            "PAYOUT_ACCOUNT", "D");
    private static final Pattern REF = Pattern.compile("^[A-Za-z]{1,2}-([0-9A-Fa-f]{6})$");

    /** How every system, provider or seed action is shown: no id, name "System" (docs/ops-console/audit-log.md). */
    public static final String SYSTEM = "System";
    /** Names the services record for actions no admin took: the system token ("system", "CoRyd System"), scheduled
     * changes, Smile ID's automatic decisions and the dev seeders. */
    static final Set<String> SYSTEM_NAMES = Set.of("SYSTEM", "CORYD SYSTEM", "SCHEDULED CHANGE", "SEED", "DATASEEDER", "SMILE_ID",
            "SMILE ID", "SMILE_ID_NIN_LOOKUP_AND_LICENCE", "PREMBLY");
    /** Targets whose id is a driver - target.driverId repeats it so the console needn't know which types those are. */
    private static final Set<String> DRIVER_TARGETS = Set.of("DRIVER", "PAYOUT_ACCOUNT");

    /**
     * {id: null, name: "System"} for system/automatic/seed actions; the recorded admin otherwise; null only when nothing
     * at all was recorded (account actions from before the history kept who did them).
     */
    static AuditItem.Actor actor(String actorId, String actorName) {
        boolean noName = actorName == null || actorName.isBlank();
        boolean systemId = actorId != null && actorId.trim().equalsIgnoreCase("system");
        boolean systemName = !noName && SYSTEM_NAMES.contains(actorName.trim().toUpperCase(Locale.ROOT));
        if (systemId || (actorId == null && systemName)) {
            return new AuditItem.Actor(null, SYSTEM);
        }
        if (actorId == null && noName) {
            return null;
        }
        return new AuditItem.Actor(actorId, noName ? null : actorName);
    }

    private final OpsAuditClient auditClient;
    private final IdentityServiceClient identityServiceClient;
    private final ObjectMapper objectMapper;

    public AuditFeed page(AuditFilter filter, String cursor, Integer size) {
        int pageSize = size == null ? DEFAULT_SIZE : Math.clamp(size, 1, MAX_SIZE);
        Merged merged = merge(filter, AuditCursor.decode(cursor, objectMapper), pageSize);
        return new AuditFeed(enrich(merged.entries()), merged.hasMore(), merged.hasMore() ? merged.next().encode(objectMapper) : null,
                pageSize);
    }

    /** The filtered feed, newest first, up to `limit` rows (at most 10,000) - for the CSV export. */
    public Export export(AuditFilter filter, Integer limit) {
        int max = limit == null ? MAX_EXPORT_ROWS : Math.clamp(limit, 1, MAX_EXPORT_ROWS);
        List<AuditItem> rows = new ArrayList<>();
        AuditCursor cursor = AuditCursor.start();
        boolean more = true;
        while (more && rows.size() < max) {
            Merged merged = merge(filter, cursor, Math.min(OpsAuditQuery.MAX_SIZE, max - rows.size()));
            rows.addAll(enrich(merged.entries()));
            more = merged.hasMore();
            cursor = merged.next();
        }
        return new Export(rows, more);
    }

    public record Export(List<AuditItem> rows, boolean truncated) {
    }

    record Merged(List<Tagged> entries, boolean hasMore, AuditCursor next) {
    }

    record Tagged(String source, int index, OpsAuditEntry entry) {
    }

    static final Comparator<Tagged> NEWEST_FIRST = Comparator.comparing((Tagged t) -> t.entry().at()).reversed()
            .thenComparing(Tagged::source)
            .thenComparingInt(Tagged::index);

    Merged merge(AuditFilter filter, AuditCursor cursor, int size) {
        validate(filter);
        Map<String, OpsAuditPage> pages = new LinkedHashMap<>();
        for (Source source : SOURCES) {
            if (!source.relevant(filter) || cursor.exhausted().contains(source.key())) {
                continue;
            }
            AuditCursor.Position pos = cursor.positions().get(source.key());
            pages.put(source.key(), auditClient.page(source.key(), query(filter, pos, size)));
        }
        List<Tagged> all = new ArrayList<>();
        pages.forEach((key, page) -> {
            for (int i = 0; i < page.items().size(); i++) {
                all.add(new Tagged(key, i, page.items().get(i)));
            }
        });
        all.sort(NEWEST_FIRST);
        List<Tagged> taken = all.subList(0, Math.min(size, all.size()));

        AuditCursor next = new AuditCursor(new LinkedHashMap<>(cursor.positions()), new java.util.LinkedHashSet<>(cursor.exhausted()));
        Map<String, Integer> consumed = new LinkedHashMap<>();
        for (Tagged t : taken) {
            consumed.merge(t.source(), 1, Integer::sum);
            next.positions().put(t.source(), new AuditCursor.Position(t.entry().at(), t.entry().id()));
        }
        boolean hasMore = false;
        for (Source source : SOURCES) {
            OpsAuditPage page = pages.get(source.key());
            if (page == null) {
                continue;
            }
            boolean allTaken = consumed.getOrDefault(source.key(), 0) == page.items().size();
            if (allTaken && !page.hasMore()) {
                next.exhausted().add(source.key());
            } else {
                hasMore = true;
            }
        }
        return new Merged(List.copyOf(taken), hasMore, next);
    }

    private static OpsAuditQuery query(AuditFilter f, AuditCursor.Position pos, int size) {
        Instant from = f.from() == null ? null : f.from().atStartOfDay(LAGOS).toInstant();
        Instant to = f.to() == null ? null : f.to().plusDays(1).atStartOfDay(LAGOS).toInstant();
        return new OpsAuditQuery(blank(f.actorId()), f.actions(), f.categories(), f.targetTypes(), blank(f.targetId()), from, to,
                searchTerm(f.search()), pos == null ? null : pos.at(), pos == null ? null : pos.id(), size);
    }

    /** "T-2F2F53" (a console reference) searches by the id it was made from. */
    static String searchTerm(String search) {
        String s = blank(search);
        if (s == null) {
            return null;
        }
        Matcher m = REF.matcher(s);
        return m.matches() ? m.group(1).toLowerCase(Locale.ROOT) : s;
    }

    private static void validate(AuditFilter f) {
        if (f.from() != null && f.to() != null && f.from().isAfter(f.to())) {
            throw new BadRequestException("from must be on or before to");
        }
        if (f.targetTypes() != null) {
            for (String type : f.targetTypes()) {
                if (!TARGET_TYPES.contains(type)) {
                    throw new BadRequestException("Invalid targetType '" + type + "' - must be one of " + TARGET_TYPES);
                }
            }
        }
    }

    private List<AuditItem> enrich(List<Tagged> tagged) {
        Set<String> people = new HashSet<>();
        for (Tagged t : tagged) {
            OpsAuditEntry e = t.entry();
            if (PERSON_TARGETS.contains(e.targetType()) && e.targetId() != null) {
                people.add(e.targetId());
            }
            if (e.actorId() != null && !e.actorId().equalsIgnoreCase("system") && (e.actorName() == null || e.actorName().isBlank())) {
                people.add(e.actorId());
            }
        }
        Map<String, IdentityServiceClient.Contact> contacts = people.isEmpty() ? Map.of() : identityServiceClient.contacts(people);
        return tagged.stream().map(t -> item(t, contacts)).toList();
    }

    private AuditItem item(Tagged t, Map<String, IdentityServiceClient.Contact> contacts) {
        OpsAuditEntry e = t.entry();
        String actorName = e.actorName();
        if ((actorName == null || actorName.isBlank()) && e.actorId() != null && contacts.containsKey(e.actorId())) {
            actorName = contacts.get(e.actorId()).fullName();
        }
        IdentityServiceClient.Contact target = PERSON_TARGETS.contains(e.targetType()) ? contacts.get(e.targetId()) : null;
        String relatedRef = e.relatedId() == null ? null
                : ref("MONEY".equals(e.category().name()) ? null : "T", e.relatedId());
        return new AuditItem(t.source() + ":" + e.id(), e.at(), t.source(), e.category(), e.action(),
                actor(e.actorId(), actorName),
                new AuditItem.Target(e.targetType(), e.targetId(), ref(REF_PREFIX.get(e.targetType()), e.targetId()),
                        target == null ? null : target.fullName(), DRIVER_TARGETS.contains(e.targetType()) ? e.targetId() : null),
                e.subject(), e.reason(), json(e.before()), json(e.after()), e.amount(), e.relatedId(), relatedRef);
    }

    private JsonNode json(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(text);
        } catch (Exception e) {
            return objectMapper.getNodeFactory().textNode(text);
        }
    }

    static String ref(String prefix, String id) {
        if (prefix == null || id == null) {
            return null;
        }
        String compact = id.replace("-", "").toUpperCase(Locale.ROOT);
        return prefix + "-" + compact.substring(0, Math.min(6, compact.length()));
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    // ---- CSV ------------------------------------------------------------------------------------------------------

    private static final DateTimeFormatter CSV_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(LAGOS);

    static final List<String> CSV_HEADER = List.of("Time (Lagos)", "Category", "Action", "Actor", "Actor ID", "Target type",
            "Target ID", "Target ref", "Target name", "Subject", "Reason", "Amount", "Before", "After", "Related ID", "Entry ID");

    public static String csv(List<AuditItem> rows) {
        StringBuilder out = new StringBuilder();
        line(out, CSV_HEADER);
        for (AuditItem r : rows) {
            line(out, Stream.of(CSV_TIME.format(r.at()), r.category().name(), r.action(),
                    r.actor() == null ? null : r.actor().name(), r.actor() == null ? null : r.actor().id(),
                    r.target().type(), r.target().id(), r.target().ref(), r.target().name(), r.subject(), r.reason(),
                    r.amount() == null ? null : r.amount().toPlainString(),
                    r.before() == null ? null : r.before().toString(), r.after() == null ? null : r.after().toString(),
                    r.relatedId(), r.id()).toList());
        }
        return out.toString();
    }

    private static void line(StringBuilder out, List<String> cells) {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(cell(cells.get(i)));
        }
        out.append("\r\n");
    }

    /** Quoted when needed; a cell a spreadsheet would run as a formula (=, +, -, @) is prefixed with '. */
    static String cell(String value) {
        if (value == null) {
            return "";
        }
        String v = value;
        if (!v.isEmpty() && "=+-@\t\r".indexOf(v.charAt(0)) >= 0) {
            v = "'" + v;
        }
        if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
            v = "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }

    /** "coryd-audit-2026-11-13.csv" - today in Lagos. */
    public static String fileName() {
        return "coryd-audit-" + LocalDate.now(LAGOS) + ".csv";
    }
}
