package org.coryd.identity.dto;

import java.time.Instant;
import java.util.List;

/** Sign-ups in a window by "How did you hear about us?", and the most common "Other" answers. */
public record HeardAboutUsStatsResponse(Instant from, Instant to, long signups, List<Count> bySource, List<Count> otherAnswers) {

    public record Count(String source, long signups) {
    }
}
