package org.coryd.notification.service;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** REPORT_RECIPIENTS is comma-separated and operator-edited, so it must tolerate untidy values. */
class ReportRecipientsTest {

    @Test
    @SuppressWarnings("unchecked")
    void acceptsManyAddressesAndIgnoresUntidyEntries() {
        ReportService service = new ReportService(null, null, null);
        ReflectionTestUtils.setField(service, "recipients",
                List.of("hello@coryd.ng", "  victor.oyeks@coryd.ng  ", "", "hello@coryd.ng", "ops@coryd.ng"));

        List<String> cleaned = (List<String>) ReflectionTestUtils.invokeMethod(service, "cleanRecipients");

        assertThat(cleaned).containsExactly("hello@coryd.ng", "victor.oyeks@coryd.ng", "ops@coryd.ng");
    }

    @Test
    @SuppressWarnings("unchecked")
    void handlesAnUnsetVariable() {
        ReportService service = new ReportService(null, null, null);
        ReflectionTestUtils.setField(service, "recipients", null);

        assertThat((List<String>) ReflectionTestUtils.invokeMethod(service, "cleanRecipients")).isEmpty();
    }
}
