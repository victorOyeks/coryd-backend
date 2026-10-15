package org.coryd.trip.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Whether along-the-way matching can ask PostGIS for its candidates. True once the PostGIS migrations
 * have run (the extension, the route_posts.route_geog column and its trigger) and matching.use-postgis
 * is on; otherwise trip-service matches in Java, with the same results. Checked once, on first use -
 * installing PostGIS needs a restart anyway, for the migrations to run.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GeoCapabilities {

    private final JdbcTemplate jdbcTemplate;
    private final MatchingProperties matchingProperties;
    private volatile Boolean postgis;

    public boolean postgis() {
        if (!matchingProperties.isUsePostgis()) {
            return false;
        }
        Boolean known = postgis;
        if (known == null) {
            known = detect();
            postgis = known;
            log.info("Along-the-way matching uses {}", known ? "PostGIS (indexed)" : "the in-memory fallback - PostGIS isn't set up on this database");
        }
        return known;
    }

    private boolean detect() {
        try {
            Integer columns = jdbcTemplate.queryForObject("""
                    select count(*) from information_schema.columns
                    where table_schema = 'trip' and table_name = 'route_posts' and column_name = 'route_geog'
                    """, Integer.class);
            return columns != null && columns > 0;
        } catch (Exception e) {
            log.warn("Could not check for PostGIS - using the in-memory fallback: {}", e.getMessage());
            return false;
        }
    }
}
