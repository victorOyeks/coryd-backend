package org.coryd.common.security;

import org.coryd.common.enums.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class JwtUtilTest {

    private JwtUtil jwtUtil(String secret) {
        JwtUtil jwt = new JwtUtil();
        ReflectionTestUtils.setField(jwt, "secret", secret);
        ReflectionTestUtils.setField(jwt, "expirationMs", 60_000L);
        jwt.buildParser();
        return jwt;
    }

    @Test
    void manyRequestsAtOnceAllHaveTheirTokenChecked() throws Exception {
        JwtUtil jwt = jwtUtil("a-test-secret-that-is-long-enough-for-hmac-sha-256-0123456789");
        String token = jwt.generateToken("user-1", "08012345678", "Ada", "Obi", UserRole.DRIVER);
        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            List<Callable<Boolean>> calls = new ArrayList<>();
            for (int i = 0; i < 200; i++) {
                calls.add(() -> jwt.isTokenValid(token) && "user-1".equals(jwt.extractUserDetails(token).getUserId()));
            }
            for (Future<Boolean> result : pool.invokeAll(calls)) {
                assertThat(result.get()).isTrue();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aTokenSignedWithAnotherKeyIsRefused() {
        String foreign = jwtUtil("another-secret-that-is-long-enough-for-hmac-sha-256-0123456789")
                .generateToken("user-1", "08012345678", "Ada", "Obi", UserRole.RIDER);

        assertThat(jwtUtil("a-test-secret-that-is-long-enough-for-hmac-sha-256-0123456789").isTokenValid(foreign)).isFalse();
    }
}
