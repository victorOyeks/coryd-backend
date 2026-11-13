package org.coryd.notification.gateway.whatsapp;

import org.coryd.notification.config.WhatsAppProperties;
import org.springframework.web.client.RestClient;

/** Lets tests in other packages build the Termii provider over a mocked RestClient. */
public final class TermiiWhatsAppProviderTestAccess {

    private TermiiWhatsAppProviderTestAccess() {
    }

    public static WhatsAppProvider create(RestClient restClient, WhatsAppProperties properties) {
        return new TermiiWhatsAppProvider(restClient, properties);
    }
}
