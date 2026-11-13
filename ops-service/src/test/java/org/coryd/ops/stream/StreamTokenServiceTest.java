package org.coryd.ops.stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.coryd.common.exception.UnauthorizedException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The stream token: who it's for, until when, and that nothing but this service's own signature opens a stream. */
class StreamTokenServiceTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void aTokenNamesItsAdminAndExpiresAfterItsLifetime() {
        StreamTokenService tokens = new StreamTokenService("secret-1", 60, json);
        Instant now = Instant.parse("2026-11-13T10:00:00Z");
        tokens.setClock(Clock.fixed(now, ZoneOffset.UTC));
        StreamTokenService.Issued issued = tokens.issue("admin-1", "Ada Ops");
        assertThat(issued.expiresAt()).isEqualTo(now.plusSeconds(60));

        StreamTokenService.Claims claims = tokens.verify(issued.token());
        assertThat(claims.adminId()).isEqualTo("admin-1");
        assertThat(claims.adminName()).isEqualTo("Ada Ops");

        tokens.setClock(Clock.fixed(now.plus(Duration.ofSeconds(59)), ZoneOffset.UTC));
        assertThat(tokens.verify(issued.token()).adminId()).isEqualTo("admin-1");
        tokens.setClock(Clock.fixed(now.plus(Duration.ofSeconds(60)), ZoneOffset.UTC));
        assertThatThrownBy(() -> tokens.verify(issued.token())).isInstanceOf(UnauthorizedException.class).hasMessageContaining("expired");
    }

    @Test
    void aTamperedForeignOrMalformedTokenIsRefused() {
        StreamTokenService tokens = new StreamTokenService("secret-1", 60, json);
        String token = tokens.issue("admin-1", "Ada").token();
        String otherAdmin = tokens.issue("admin-2", "Bola").token();
        String spliced = otherAdmin.substring(0, otherAdmin.indexOf('.')) + token.substring(token.indexOf('.'));

        assertThatThrownBy(() -> tokens.verify(spliced)).isInstanceOf(UnauthorizedException.class);
        assertThatThrownBy(() -> tokens.verify(new StreamTokenService("secret-2", 60, json).issue("admin-1", "Ada").token()))
                .isInstanceOf(UnauthorizedException.class);
        for (String bad : new String[]{null, "", "abc", "abc.", ".abc", "a.b.c", "!!!.???"}) {
            assertThatThrownBy(() -> tokens.verify(bad)).isInstanceOf(UnauthorizedException.class);
        }
    }
}
