package org.coryd.profile.dto;

public enum VerificationCheckType {

    /** FRSC driver's licence, by licence number + the driver's own first/last name. */
    DRIVERS_LICENCE,

    /** Plate number against the vehicle register, cross-checked against the declared make and colour. */
    VEHICLE_PLATE
}
