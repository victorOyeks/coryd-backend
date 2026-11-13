package org.coryd.common.instrumentation;

import org.junit.jupiter.api.Test;
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
}
