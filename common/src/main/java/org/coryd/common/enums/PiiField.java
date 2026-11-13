package org.coryd.common.enums;

/**
 * A personal identifier the ops console masks by default and reveals only on a deliberate, audited request
 * (POST /ops/users/{userId}/reveal - docs/ops-console/privacy.md). Bank account numbers have their own reveal in money.
 */
public enum PiiField {
    NIN("NIN"),
    LICENCE_NUMBER("Driver's licence number"),
    DATE_OF_BIRTH("Date of birth");

    private final String label;

    PiiField(String label) {
        this.label = label;
    }

    /** How the audit log names it ("Driver's licence number"). */
    public String label() {
        return label;
    }
}
