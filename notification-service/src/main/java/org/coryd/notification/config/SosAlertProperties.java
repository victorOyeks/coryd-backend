package org.coryd.notification.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/** Who at CoRyd hears about every SOS - an SMS to each phone and an email to each address. */
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "sos")
public class SosAlertProperties {

    private List<String> opsPhones = List.of();

    private List<String> opsEmails = List.of();

    /** Tolerates spaces and a trailing comma in the env var - one blank entry would otherwise be texted. */
    public List<String> phones() {
        return clean(opsPhones);
    }

    public List<String> emails() {
        return clean(opsEmails);
    }

    private static List<String> clean(List<String> values) {
        return values == null ? List.of() : values.stream().map(String::trim).filter(v -> !v.isEmpty()).toList();
    }
}
