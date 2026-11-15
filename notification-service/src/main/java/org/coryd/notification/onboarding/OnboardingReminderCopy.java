package org.coryd.notification.onboarding;

import org.coryd.common.enums.OnboardingStep;

/**
 * The reminder's words: the title, and a body that names the one next step (docs/ops-console/onboarding-reminders.md).
 * Drivers are told what it unlocks ("to start driving"), riders "to start booking rides".
 */
public final class OnboardingReminderCopy {

    public record Message(String title, String body) {
    }

    private OnboardingReminderCopy() {
    }

    public static Message of(OnboardingStep step, OnboardingStep.Reason reason, String role, String firstName) {
        boolean driver = "DRIVER".equals(role);
        String goal = driver ? "to start driving" : "to start booking rides";
        String name = firstName == null || firstName.isBlank() ? null : firstName.trim();
        if (step == OnboardingStep.NIN_LOCKED_OUT) {
            return new Message("Let's get you verified",
                    "We couldn't verify your NIN in the app. Contact CoRyd support and we'll help you finish setting up your account.");
        }
        String title = name == null ? "You're almost there" : "You're almost there, " + name;
        String body = switch (step) {
            case PROFILE -> (driver ? "Complete your driver profile " : "Complete your profile ") + goal + ".";
            case TERMS -> "Accept CoRyd's terms of service " + goal + ".";
            case NIN -> reason == OnboardingStep.Reason.RETRY
                    ? "Your NIN check needs another try. Verify your NIN " + goal + "."
                    : "Verify your NIN " + goal + ".";
            case LICENCE -> document("driver's licence", reason, goal);
            case VEHICLE -> switch (reason) {
                case RESUBMIT -> "CoRyd asked you to update your vehicle registration. Do it now " + goal + ".";
                case REJECTED -> "Your vehicle registration wasn't accepted. Update your vehicle details " + goal + ".";
                default -> "Add your vehicle and its registration " + goal + ".";
            };
            case INSURANCE -> document("insurance certificate", reason, goal);
            case ROADWORTHINESS -> document("roadworthiness certificate", reason, goal);
            case NIN_LOCKED_OUT -> throw new IllegalStateException("handled above");
        };
        return new Message(title, body);
    }

    private static String document(String what, OnboardingStep.Reason reason, String goal) {
        return switch (reason) {
            case RETRY -> "Your " + what + " check needs another try. Retake it " + goal + ".";
            case RESUBMIT -> "CoRyd asked you to upload your " + what + " again. Upload it " + goal + ".";
            case REJECTED -> "Your " + what + " wasn't accepted. Upload a new one " + goal + ".";
            case EXPIRED -> "Your " + what + " has expired. Upload a current one " + goal + ".";
            default -> "Upload your " + what + " " + goal + ".";
        };
    }
}
