package org.coryd.notification.onboarding;

/** Who ran a daily job: the scheduler, or a super admin from the ops console. */
public record JobActor(String id, String name, boolean system) {

    public static final JobActor SCHEDULER = new JobActor("system", "Scheduled job", true);

    public static JobActor admin(String id, String name) {
        return new JobActor(id, name, false);
    }
}
