package org.coryd.common.kafka.events;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * An account was deleted - by its owner or by ops. Every service erases the person's data it holds and
 * anonymises what it must keep (trips, bookings, payments and ratings stay, under a user ID that no longer
 * leads to anyone). The phone is freed: signing up with it again starts a brand-new account.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserDeletedEvent {
    private String userId;
    /** Only so services that logged messages by phone number can strip it from those logs. */
    private String phone;
    /** "SELF" when the person deleted their own account, otherwise the ops user. */
    private String deletedBy;
    private Instant deletedAt;
}
