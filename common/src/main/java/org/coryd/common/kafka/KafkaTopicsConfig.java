package org.coryd.common.kafka;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Declares every topic as a NewTopic bean (mirroring marketrun-ng) so Spring's
 * auto-configured KafkaAdmin provisions it at startup, on whichever CoRyd
 * service boots first - not left to the shared broker's own auto-create
 * behavior, which only creates a topic lazily on first produce/consume and
 * would silently break everything if that broker setting is ever disabled.
 */
@Configuration
public class KafkaTopicsConfig {

    @Bean
    public NewTopic otpIssuedTopic() {
        return TopicBuilder.name(KafkaTopics.OTP_ISSUED).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic routePublishedTopic() {
        return TopicBuilder.name(KafkaTopics.ROUTE_PUBLISHED).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic routeCancelledTopic() {
        return TopicBuilder.name(KafkaTopics.ROUTE_CANCELLED).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic notifyMeMatchedTopic() {
        return TopicBuilder.name(KafkaTopics.NOTIFY_ME_MATCHED).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic driverDemandAlertTopic() {
        return TopicBuilder.name(KafkaTopics.DRIVER_DEMAND_ALERT).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic driverNearbyDemandTopic() {
        return TopicBuilder.name(KafkaTopics.DRIVER_NEARBY_DEMAND).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic seatReservedTopic() {
        return TopicBuilder.name(KafkaTopics.SEAT_RESERVED).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic routeDepartureReminderTopic() {
        return TopicBuilder.name(KafkaTopics.ROUTE_DEPARTURE_REMINDER).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic routeTimeChangedTopic() {
        return TopicBuilder.name(KafkaTopics.ROUTE_TIME_CHANGED).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic bookingConfirmedTopic() {
        return TopicBuilder.name(KafkaTopics.BOOKING_CONFIRMED).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic bookingCancelledTopic() {
        return TopicBuilder.name(KafkaTopics.BOOKING_CANCELLED).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic driverEnRouteTopic() {
        return TopicBuilder.name(KafkaTopics.DRIVER_EN_ROUTE).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic driverArrivedTopic() {
        return TopicBuilder.name(KafkaTopics.DRIVER_ARRIVED).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic tripCompletedTopic() {
        return TopicBuilder.name(KafkaTopics.TRIP_COMPLETED).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic sosTriggeredTopic() {
        return TopicBuilder.name(KafkaTopics.SOS_TRIGGERED).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic issueReportedTopic() {
        return TopicBuilder.name(KafkaTopics.ISSUE_REPORTED).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic tripShareRequestedTopic() {
        return TopicBuilder.name(KafkaTopics.TRIP_SHARE_REQUESTED).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic paymentSucceededTopic() {
        return TopicBuilder.name(KafkaTopics.PAYMENT_SUCCEEDED).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic paymentFailedTopic() {
        return TopicBuilder.name(KafkaTopics.PAYMENT_FAILED).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic refundIssuedTopic() {
        return TopicBuilder.name(KafkaTopics.REFUND_ISSUED).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic driverOwedChangedTopic() {
        return TopicBuilder.name(KafkaTopics.DRIVER_OWED_CHANGED).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic payoutProcessedTopic() {
        return TopicBuilder.name(KafkaTopics.PAYOUT_PROCESSED).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic driverApprovedTopic() {
        return TopicBuilder.name(KafkaTopics.DRIVER_APPROVED).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic driverRejectedTopic() {
        return TopicBuilder.name(KafkaTopics.DRIVER_REJECTED).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic verificationItemDecidedTopic() {
        return TopicBuilder.name(KafkaTopics.VERIFICATION_ITEM_DECIDED).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic documentExpiringTopic() {
        return TopicBuilder.name(KafkaTopics.DOCUMENT_EXPIRING).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic userSuspendedTopic() {
        return TopicBuilder.name(KafkaTopics.USER_SUSPENDED).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic userReinstatedTopic() {
        return TopicBuilder.name(KafkaTopics.USER_REINSTATED).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic userDeletedTopic() {
        return TopicBuilder.name(KafkaTopics.USER_DELETED).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic tripSafetyCheckTopic() {
        return TopicBuilder.name(KafkaTopics.TRIP_SAFETY_CHECK).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic tripSafetyEscalatedTopic() {
        return TopicBuilder.name(KafkaTopics.TRIP_SAFETY_ESCALATED).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic identityVerificationChangedTopic() {
        return TopicBuilder.name(KafkaTopics.IDENTITY_VERIFICATION_CHANGED).partitions(3).replicas(1).build();
    }
}
