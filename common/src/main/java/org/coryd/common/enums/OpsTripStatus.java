package org.coryd.common.enums;

/** Ops Console Trips table's filter tabs - omit the query param entirely for "All". ACTIVE is every trip
 * not finished yet: scheduled, full or under way. */
public enum OpsTripStatus {
    ACTIVE,
    COMPLETED,
    CANCELLED
}
