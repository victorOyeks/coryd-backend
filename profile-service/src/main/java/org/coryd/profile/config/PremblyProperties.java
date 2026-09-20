package org.coryd.profile.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Prembly is off by default: without a key every check would fail, and a failed check must not
 * block a driver, so running with it disabled is the same as running the way we did before.
 * Point the base URL at the sandbox until the live key is issued - the key itself decides which
 * environment Prembly answers as, so a live key against the sandbox URL is the mistake to avoid.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "prembly")
public class PremblyProperties {

    private boolean enabled = false;

    private String baseUrl = "https://sandbox.prembly.com";

    /** Sent as x-api-key. Prembly explicitly wants no Authorization header alongside it. */
    private String apiKey;

    /** Some Prembly tenants also require app-id; left blank it simply isn't sent. */
    private String appId;

    /**
     * How close the FRSC name has to be to what the driver typed, 0-1. Names come back from FRSC
     * uppercased and sometimes with the middle name in the first-name field, so an exact match is
     * too strict; 0.85 tolerates spelling drift without letting a different person through.
     */
    private double nameMatchThreshold = 0.85;

    /** A vehicle whose declared make or colour contradicts the plate record goes to ops, not straight through. */
    private boolean crossCheckVehicleDetails = true;
}
