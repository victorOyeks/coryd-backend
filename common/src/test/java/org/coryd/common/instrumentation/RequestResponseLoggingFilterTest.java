package org.coryd.common.instrumentation;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.coryd.common.security.filter.InternalApiKeyFilter;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class RequestResponseLoggingFilterTest {

    private final RequestResponseLoggingFilter filter = new RequestResponseLoggingFilter();

    private static MockHttpServletRequest get(String servletPath) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", servletPath);
        request.setServletPath(servletPath);
        return request;
    }

    @Test
    void csvDownloadsAreNotBufferedForTheBodyLog() {
        assertThat(filter.shouldNotFilter(get("/reports/analytics/bookings.csv"))).isTrue();
        assertThat(filter.shouldNotFilter(get("/internal/ops/analytics/trips.csv"))).isTrue();
        assertThat(filter.shouldNotFilter(get("/audit/export"))).isTrue();
        assertThat(filter.shouldNotFilter(get("/reports/analytics/bookings"))).isFalse();
        assertThat(filter.shouldNotFilter(get("/riders"))).isFalse();
    }

    @Test
    void aStreamedDownloadReachesTheClientBeforeTheHandlerFinishes() throws Exception {
        MockHttpServletRequest request = get("/reports/analytics/bookings.csv");
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean[] committedWhileWriting = {false};

        filter.doFilter(request, response, (req, res) -> {
            try {
                res.getWriter().write("a,b\r\n");
                res.flushBuffer();
                committedWhileWriting[0] = res.isCommitted();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });

        assertThat(committedWhileWriting[0]).as("not held in a caching wrapper").isTrue();
        assertThat(response.getContentAsString()).isEqualTo("a,b\r\n");
    }

    @Test
    void aCallFromAnotherServiceIsNotBodyLoggedButAMadeUpKeyIs() {
        RequestResponseLoggingFilter withKey = new RequestResponseLoggingFilter("the-internal-key", 2048);
        MockHttpServletRequest internal = get("/internal/ops/audit");
        internal.addHeader(InternalApiKeyFilter.HEADER, "the-internal-key");
        MockHttpServletRequest forged = get("/internal/ops/audit");
        forged.addHeader(InternalApiKeyFilter.HEADER, "guess");

        assertThat(withKey.shouldNotFilter(internal)).isTrue();
        assertThat(withKey.shouldNotFilter(forged)).isFalse();
        assertThat(withKey.shouldNotFilter(get("/internal/ops/audit"))).isFalse();
        assertThat(filter.shouldNotFilter(internal)).as("no key configured: nothing is skipped").isFalse();
    }

    @Test
    void aLargeResponseIsLoggedUpToTheCapMaskedAndReachesTheCallerWhole() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(RequestResponseLoggingFilter.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        StringBuilder json = new StringBuilder("{\"data\":[");
        for (int i = 0; i < 500; i++) {
            json.append(i == 0 ? "" : ",").append("{\"id\":\"row-").append(i).append("\",\"email\":\"rider").append(i).append("@coryd.ng\"}");
        }
        String body = json.append("]}").toString();
        MockHttpServletResponse response = new MockHttpServletResponse();
        try {
            new RequestResponseLoggingFilter("", 1024).doFilter(get("/riders"), response, (req, res) -> {
                try {
                    res.setContentType("application/json");
                    res.getWriter().write(body);
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            });
        } finally {
            logger.detachAppender(logs);
        }

        assertThat(response.getContentAsString()).isEqualTo(body);
        String logged = logs.list.stream().map(ILoggingEvent::getFormattedMessage).filter(m -> m.startsWith("RESPONSE")).findFirst().orElseThrow();
        assertThat(logged.length()).isLessThan(1024 + 120);
        assertThat(logged).contains("[truncated: " + body.length() + " bytes in all]").doesNotContain("rider0@coryd.ng");
    }
}
