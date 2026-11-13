package org.coryd.notification.gateway.whatsapp;

/** What the provider said. delivered means accepted by the provider, as with SMS - not read on the handset. */
public record WhatsAppSendResult(boolean delivered, String providerMessageId, String errorCode) {

    public static WhatsAppSendResult accepted(String providerMessageId) {
        return new WhatsAppSendResult(true, providerMessageId, null);
    }

    public static WhatsAppSendResult failed(String errorCode) {
        return new WhatsAppSendResult(false, null, errorCode);
    }
}
