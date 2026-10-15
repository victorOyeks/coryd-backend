package org.coryd.profile.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Prembly is off by default: without a key every check would fail, and a failed check must not
 * block a driver, so running with it disabled is the same as running the way we did before.
 * Test and live share one host - the key alone decides which of the two Prembly answers as, so
 * moving to production is a key swap and nothing else.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "prembly")
public class PremblyProperties {

    private boolean enabled = false;

    private String baseUrl = "https://api.prembly.com";

    /** Sent as x-api-key. Prembly explicitly wants no Authorization header alongside it. */
    private String apiKey;

    /**
     * How close the FRSC name has to be to what the driver typed, 0-1. Names come back from FRSC
     * uppercased and sometimes with the middle name in the first-name field, so an exact match is
     * too strict; 0.85 tolerates spelling drift without letting a different person through.
     */
    private double nameMatchThreshold = 0.85;

    /** A vehicle whose declared make or colour contradicts the plate record goes to ops, not straight through. */
    private boolean crossCheckVehicleDetails = true;

    /**
     * How a rider's NIN + selfie is checked - whichever Prembly product the account is on:
     * NIN_WITH_FACE (default): the NIN record and a face match of the selfie against the NIN photo.
     * NIN_BASIC (fallback): the NIN record's name (no photo), plus a liveness check on the selfie - proves
     * a real NIN, the rider's own name and a live person, but not that the face is the NIN holder's.
     */
    public enum NinCheck { NIN_BASIC, NIN_WITH_FACE }

    private NinCheck ninCheck = NinCheck.NIN_WITH_FACE;

    /** https://docs.prembly.com/docs/national-id-basic-1 */
    private String ninBasicPath = "/verification/vnin-basic";

    /** https://docs.prembly.com/docs/nin-with-face-copy */
    private String ninFacePath = "/verification/nin_w_face";

    /** https://docs.prembly.com/docs/face-livelinness-check */
    private String livenessPath = "/verification/biometrics/face/liveliness_check";

    /** Sent to NIN-with-face as face_confidence_threshold (70-100) - Prembly decides the match and says so in face_data.status. */
    private int faceMatchThreshold = 80;

    /** Liveness confidence, in percent, a selfie needs to count as a live person (NIN_BASIC). */
    private double livenessThreshold = 80.0;
}
