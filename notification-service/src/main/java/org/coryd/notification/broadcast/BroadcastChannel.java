package org.coryd.notification.broadcast;

/** PUSH also puts the message in the app's notification feed (the bell). WHATSAPP needs the approved broadcast template. */
public enum BroadcastChannel { PUSH, SMS, EMAIL, WHATSAPP }
