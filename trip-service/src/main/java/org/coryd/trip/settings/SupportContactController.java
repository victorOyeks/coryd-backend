package org.coryd.trip.settings;

import org.coryd.common.response.ApiResponse;
import org.coryd.common.settings.PlatformSetting;
import org.coryd.common.settings.PlatformSettings;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/**
 * GET /trip/public/support-contact - the WhatsApp number and email ops set under Settings > Support contact, for the apps'
 * Contact support. Public and read-only (SecurityConfig's /public/** permitAll): a suspended rider or driver can't sign in and
 * still needs to reach support. Read from memory (the live settings), never the database; a field ops left empty is null.
 */
@RestController
public class SupportContactController {

    /** Long enough for a phone to skip a refetch, short enough that a change in the console reaches the apps quickly. */
    static final Duration MAX_AGE = Duration.ofMinutes(5);

    public record SupportContact(String whatsapp, String whatsappLink, String email) {
    }

    private final PlatformSettings platformSettings;
    private final PlatformSettingDefaults defaults;

    public SupportContactController(@Autowired(required = false) PlatformSettings platformSettings, PlatformSettingDefaults defaults) {
        this.platformSettings = platformSettings;
        this.defaults = defaults;
    }

    @GetMapping("/public/support-contact")
    public ResponseEntity<ApiResponse<SupportContact>> supportContact() {
        String whatsapp = read(PlatformSetting.SUPPORT_WHATSAPP);
        String email = read(PlatformSetting.SUPPORT_EMAIL);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(MAX_AGE).cachePublic())
                .body(ApiResponse.ok(new SupportContact(whatsapp, PlatformSetting.whatsappLink(whatsapp), email)));
    }

    private String read(PlatformSetting setting) {
        String fallback = PlatformSetting.parseOptional(defaults.of(setting));
        return platformSettings == null ? fallback : platformSettings.optional(setting, fallback);
    }
}
