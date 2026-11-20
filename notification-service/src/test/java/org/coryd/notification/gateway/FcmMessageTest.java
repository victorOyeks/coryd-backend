package org.coryd.notification.gateway;

import com.google.api.client.json.gson.GsonFactory;
import com.google.firebase.messaging.Message;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** What reaches Firebase: a chat push is grouped per trip, every other push exactly as before. */
class FcmMessageTest {

    @Test
    void aGroupedPushIsTaggedOnAndroidAndThreadedOnIos() throws Exception {
        String json = json(FcmService.message("token-1", "Ikeja City Mall → Victoria Island", "Tunde B.: On my way",
                "CHAT_MESSAGE", Map.of("routePostId", "route-9", "bookingId", "b-1"), "route-9"));

        assertThat(json).contains("\"tag\":\"route-9\"").contains("\"thread-id\":\"route-9\"")
                .contains("\"channel_id\":\"default\"").contains("\"sound\":\"default\"")
                .contains("\"type\":\"CHAT_MESSAGE\"").contains("\"routePostId\":\"route-9\"").contains("\"bookingId\":\"b-1\"");
    }

    @Test
    void anUngroupedPushHasNoTagOrThread() throws Exception {
        String json = json(FcmService.message("token-1", "Booking confirmed", "See you at pickup!", "BOOKING_CONFIRMED",
                Map.of("referenceId", "b-1"), null));

        assertThat(json).doesNotContain("\"tag\"").doesNotContain("thread-id")
                .contains("\"channel_id\":\"default\"").contains("\"referenceId\":\"b-1\"");
    }

    private static String json(Message message) throws Exception {
        return GsonFactory.getDefaultInstance().toString(message);
    }
}
