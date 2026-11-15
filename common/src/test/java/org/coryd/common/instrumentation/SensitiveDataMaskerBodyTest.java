package org.coryd.common.instrumentation;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** maskBody: the capped, streamed form of maskJsonBody - same masking, at most the cap of output. */
class SensitiveDataMaskerBodyTest {

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void aBodyUnderTheCapIsMaskedExactlyAsMaskJsonBodyMasksIt() {
        String json = """
                {"data":{"id":"u-1","email":"ada@coryd.ng","phone":"08053636321","token":"eyJhbGciOi.secret",
                 "items":[{"gatewayReference":"PSK_abc123","accountNumber":"0123456789","note":"hi"}],
                 "tags":["email","x"],"count":3,"ok":true,"nothing":null}}
                """;

        assertThat(SensitiveDataMasker.maskBody(bytes(json), 4096)).isEqualTo(SensitiveDataMasker.maskJsonBody(json));
    }

    @Test
    void aLargeBodyIsCutAtTheCapAndNothingSensitivePassesThrough() {
        StringBuilder json = new StringBuilder("{\"data\":[");
        for (int i = 0; i < 2000; i++) {
            json.append(i == 0 ? "" : ",").append("{\"id\":\"row-").append(i).append("\",\"phone\":\"0805363")
                    .append(String.format("%04d", i)).append("\",\"email\":\"rider").append(i).append("@coryd.ng\"}");
        }
        json.append("]}");

        String logged = SensitiveDataMasker.maskBody(bytes(json.toString()), 2048);

        assertThat(logged).startsWith("{");
        assertThat(logged.length()).isLessThan(2048 + 80);
        assertThat(logged).contains("[truncated: " + json.length() + " bytes in all]");
        assertThat(logged).doesNotContain("rider1@").doesNotContain("\"0805363");
        assertThat(logged).contains("\"r***@coryd.ng\"").contains("\"***0000\"");
    }

    @Test
    void aSensitiveValueAtTheCutIsMaskedBeforeItIsCut() {
        String json = "{\"id\":\"" + "x".repeat(40) + "\",\"token\":\"" + "s".repeat(500) + "\"}";

        String logged = SensitiveDataMasker.maskBody(bytes(json), 60);

        assertThat(logged).doesNotContain("sss");
    }

    @Test
    void aRequestHeldOnlyInPartKeepsWhatWasMaskedBeforeTheCut() {
        String cutShort = "{\"phone\":\"08053636321\",\"name\":\"Ada\",\"email\":\"ada@cor";

        String logged = SensitiveDataMasker.maskBody(bytes(cutShort), 2048);

        assertThat(logged).contains("\"***6321\"").contains("\"Ada\"").doesNotContain("ada@cor");
        assertThat(logged).contains("[truncated:");
    }

    @Test
    void plainTextIsMaskedThenCut() {
        String text = "call 08053636321 or mail ada@coryd.ng " + "z".repeat(5000);

        String logged = SensitiveDataMasker.maskBody(bytes(text), 100);

        assertThat(logged).startsWith("call ***6321 or mail a***@coryd.ng").contains("[truncated: 5038 bytes in all]");
        assertThat(SensitiveDataMasker.maskBody(bytes("short 08053636321"), 100)).isEqualTo("short ***6321");
    }

    @Test
    void noCapMeansTheWholeBodyMaskedAsBefore() {
        String json = "{\"email\":\"ada@coryd.ng\",\"big\":\"" + "y".repeat(5000) + "\"}";

        assertThat(SensitiveDataMasker.maskBody(bytes(json), 0)).isEqualTo(SensitiveDataMasker.maskJsonBody(json));
        assertThat(SensitiveDataMasker.maskBody(new byte[0], 2048)).isEmpty();
    }
}
