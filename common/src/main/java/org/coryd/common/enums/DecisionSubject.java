package org.coryd.common.enums;

/**
 * What a verification decision was about, in the decision audit log: one of a driver's four items (ReviewableItem, same
 * names), or a person's identity (NIN) check - riders and drivers alike. Kept apart from ReviewableItem so that enum
 * stays exactly a driver's four documents.
 */
public enum DecisionSubject {
    GOVERNMENT_ID("Driver's licence"),
    VEHICLE("Vehicle registration"),
    INSURANCE("Insurance certificate"),
    ROADWORTHINESS_CERT("Roadworthiness certificate"),
    IDENTITY("Identity (NIN)");

    private final String label;

    DecisionSubject(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static DecisionSubject of(ReviewableItem item) {
        return valueOf(item.name());
    }

    /** The driver item this is, or null for IDENTITY. */
    public ReviewableItem reviewableItem() {
        return this == IDENTITY ? null : ReviewableItem.valueOf(name());
    }
}
