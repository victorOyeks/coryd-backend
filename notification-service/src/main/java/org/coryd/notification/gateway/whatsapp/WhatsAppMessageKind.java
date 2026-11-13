package org.coryd.notification.gateway.whatsapp;

/**
 * The notifications that can go out on WhatsApp. Each is an approved template on the provider side, and
 * the keys of {@link WhatsAppMessage#data()} must match that template's placeholders
 * (listed per kind in docs/whatsapp-notifications.md).
 */
public enum WhatsAppMessageKind {
    /** data: otp (Termii authentication templates accept only this key). */
    OTP,
    /** data: name, link */
    TRIP_SHARE,
    /** data: name, location */
    SOS_CONTACT,
    /** data: who, route, rider_phone, driver, driver_phone, car, location */
    SOS_OPS,
    /** data: route, what, not_answered, answered_ok, car, location */
    SAFETY_ESCALATED,
    /** data: title, message - an Ops Console broadcast (docs/ops-console/broadcasts.md). */
    BROADCAST
}
