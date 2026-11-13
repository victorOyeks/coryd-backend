package org.coryd.notification.broadcast;

/** Who a broadcast is for - resolved by profile-service (GET /internal/ops/audience). Suspended accounts are never included. */
public enum AudienceType { ALL_RIDERS, ALL_DRIVERS, VERIFIED_DRIVERS, ACTIVE_RIDERS, USERS }
