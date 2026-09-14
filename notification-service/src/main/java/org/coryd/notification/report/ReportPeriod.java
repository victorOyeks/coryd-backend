package org.coryd.notification.report;

public enum ReportPeriod {

    DAILY("Daily Report"),
    WEEKLY("Weekly Report"),
    MONTHLY("Monthly Report");

    private final String label;

    ReportPeriod(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
