package org.coryd.common.audit;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the one query each owning service runs for the unified audit log: its audit entity, the filters that apply,
 * the keyset cursor, newest first (createdAt desc, id desc) - so every source pages the same way. Filters given null are
 * left out. Entity paths are the caller's ("e.actorId"); values are always bound, never concatenated.
 */
public final class OpsAuditJpql {

    private final String entity;
    private final StringBuilder where = new StringBuilder();
    private final Map<String, Object> params = new LinkedHashMap<>();
    private boolean impossible;

    private OpsAuditJpql(String entity) {
        this.entity = entity;
    }

    /** select e from {entity} e ... */
    public static OpsAuditJpql from(String entity) {
        return new OpsAuditJpql(entity);
    }

    private String param(Object value) {
        String name = "p" + params.size();
        params.put(name, value);
        return ":" + name;
    }

    private OpsAuditJpql and(String clause) {
        where.append(where.isEmpty() ? " where " : " and ").append('(').append(clause).append(')');
        return this;
    }

    public OpsAuditJpql eq(String path, Object value) {
        return value == null ? this : and(path + " = " + param(value));
    }

    /** null: no filter. Empty: nothing can match (a filter whose values this source never has). */
    public OpsAuditJpql in(String path, Collection<?> values) {
        if (values == null) {
            return this;
        }
        if (values.isEmpty()) {
            impossible = true;
            return this;
        }
        return and(path + " in " + param(values));
    }

    /** A clause written by the caller; {@code ?} placeholders are bound to values in order. */
    public OpsAuditJpql where(String clause, Object... values) {
        StringBuilder out = new StringBuilder();
        int next = 0;
        for (char c : clause.toCharArray()) {
            if (c == '?') {
                out.append(param(values[next++]));
            } else {
                out.append(c);
            }
        }
        return and(out.toString());
    }

    public OpsAuditJpql notIn(String path, Collection<?> values) {
        return values == null || values.isEmpty() ? this : and(path + " is null or " + path + " not in " + param(values));
    }

    public OpsAuditJpql range(String atPath, Instant from, Instant to) {
        if (from != null) {
            and(atPath + " >= " + param(from));
        }
        if (to != null) {
            and(atPath + " < " + param(to));
        }
        return this;
    }

    /** Only rows after the cursor in newest-first order. */
    public OpsAuditJpql before(String atPath, String idPath, Instant at, String id) {
        if (at == null) {
            return this;
        }
        if (id == null) {
            return and(atPath + " < " + param(at));
        }
        String atParam = param(at);
        return and(atPath + " < " + atParam + " or (" + atPath + " = " + atParam + " and " + idPath + " < " + param(id) + ")");
    }

    /** lower(path) like pattern on any of the paths (pattern from OpsAuditQuery.searchPattern(), already lower case). */
    public OpsAuditJpql search(String pattern, String... paths) {
        if (pattern == null || paths.length == 0) {
            return this;
        }
        String p = param(pattern);
        StringBuilder any = new StringBuilder();
        for (String path : paths) {
            if (!any.isEmpty()) {
                any.append(" or ");
            }
            any.append("lower(").append(path).append(") like ").append(p).append(" escape '\\'");
        }
        return and(any.toString());
    }

    /** Up to limit + 1 rows, newest first - the extra one only says there are more. */
    public <T> List<T> fetch(EntityManager em, Class<T> type, String atPath, String idPath, int limit) {
        if (impossible) {
            return List.of();
        }
        TypedQuery<T> query = em.createQuery("select e from " + entity + " e" + where + " order by " + atPath + " desc, " + idPath
                + " desc", type);
        params.forEach(query::setParameter);
        query.setMaxResults(limit + 1);
        return query.getResultList();
    }

    /** The JPQL as built - for tests. */
    public String jpql() {
        return "select e from " + entity + " e" + where;
    }
}
