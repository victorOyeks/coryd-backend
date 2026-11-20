package org.coryd.notification.gateway;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TermiiSmsServiceTest {

    // enabled=true with an unreachable base URL: a blank or junk number must be skipped before any call is attempted
    private final TermiiSmsService termii = new TermiiSmsService("http://127.0.0.1:9", true, "test-key", "Test");

    @Test
    void aBlankOrJunkNumberIsSkippedWithoutCallingTermii() {
        assertThat(termii.send("", "hello")).isFalse();
        assertThat(termii.send(null, "hello")).isFalse();
        assertThat(termii.send("abc", "hello")).isFalse();
        assertThat(termii.sendVoiceOtp("", "123456")).isFalse();
    }

    @Test
    void onlyInternationalDigitsOfAPhoneNumberLengthAreDialable() {
        assertThat(TermiiSmsService.isDialable("2347010001002")).isTrue();
        assertThat(TermiiSmsService.isDialable("")).isFalse();
        assertThat(TermiiSmsService.isDialable("12345")).isFalse();
        assertThat(TermiiSmsService.isDialable("23470100010021234")).isFalse();
        assertThat(TermiiSmsService.isDialable(null)).isFalse();
    }
}
