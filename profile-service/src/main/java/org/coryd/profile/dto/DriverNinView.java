package org.coryd.profile.dto;

import org.coryd.profile.entity.SmileIdRetryReason;

/** The driver's NIN step as GET /drivers/me shows it - see NinLookupService.view. ninLookup: the step is the lookup
 * (false = the older NIN + selfie check). */
public record DriverNinView(boolean ninLookup, String ninStatus, String ninLast4, SmileIdRetryReason ninRetryReason, int ninTriesLeft) {
}
