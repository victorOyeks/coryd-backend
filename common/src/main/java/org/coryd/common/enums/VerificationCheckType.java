package org.coryd.common.enums;

public enum VerificationCheckType {

    /** FRSC driver's licence, by licence number + the driver's own first/last name. */
    DRIVERS_LICENCE,

    /** Plate number against the vehicle register, cross-checked against the declared make and colour. */
    VEHICLE_PLATE,

    /** A rider's NIN against NIMC, with their selfie matched to the NIN photo and their name to the record. */
    NIN_FACE
}
