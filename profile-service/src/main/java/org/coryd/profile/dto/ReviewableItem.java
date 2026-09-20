package org.coryd.profile.dto;

/** Every independently-reviewable item on a driver's verification, unified into one enum so a single decision endpoint covers all of them instead of one endpoint per item type. */
public enum ReviewableItem {
    GOVERNMENT_ID,
    VEHICLE,
    INSURANCE,
    ROADWORTHINESS_CERT
}
