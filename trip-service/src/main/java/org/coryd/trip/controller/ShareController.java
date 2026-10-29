package org.coryd.trip.controller;

import lombok.RequiredArgsConstructor;
import org.coryd.common.response.ApiResponse;
import org.coryd.trip.dto.SharedTripResponse;
import org.coryd.trip.service.BookingService;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Public, unauthenticated - see SecurityConfig's /shared/** permitAll. Whoever gets the SMS link has no CoRyd account.
 * A browser opening the link gets the tracking page; the page (and any app) reads the same link as JSON.
 */
@RestController
@RequestMapping("/shared")
@RequiredArgsConstructor
public class ShareController {

    /** The page carries no trip data itself - it asks for it as JSON - so one copy serves every link. */
    private static final String PAGE = readPage();

    private final BookingService bookingService;

    @GetMapping("/{token}")
    public ApiResponse<SharedTripResponse> view(@PathVariable String token) {
        return ApiResponse.ok(bookingService.getSharedTripView(token));
    }

    @GetMapping(value = "/{token}", produces = MediaType.TEXT_HTML_VALUE)
    public String page(@PathVariable String token) {
        return PAGE;
    }

    private static String readPage() {
        try (var in = new ClassPathResource("share/trip.html").getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Shared trip page missing", e);
        }
    }
}
