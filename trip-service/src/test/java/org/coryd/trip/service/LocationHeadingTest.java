package org.coryd.trip.service;

import org.coryd.trip.dto.LocationResponse;
import org.coryd.trip.dto.LocationUpdateRequest;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class LocationHeadingTest {

    @Test
    void aRealHeadingAndSpeedAreKept() {
        LocationUpdateRequest request = new LocationUpdateRequest(6.6, 3.36, 147.5, 12.3);

        assertThat(request.cleanHeading()).isEqualTo(147.5);
        assertThat(request.cleanSpeed()).isEqualTo(12.3);
    }

    @Test
    void unknownOrImpossibleValuesAreDroppedNotRefused() {
        // expo-location reports -1 for "don't know"
        LocationUpdateRequest unknown = new LocationUpdateRequest(6.6, 3.36, -1.0, -1.0);
        assertThat(unknown.cleanHeading()).isNull();
        assertThat(unknown.cleanSpeed()).isNull();

        LocationUpdateRequest silly = new LocationUpdateRequest(6.6, 3.36, 400.0, 250.0);
        assertThat(silly.cleanHeading()).isNull();
        assertThat(silly.cleanSpeed()).isNull();

        LocationUpdateRequest olderApp = new LocationUpdateRequest(6.6, 3.36, null, null);
        assertThat(olderApp.cleanHeading()).isNull();
        assertThat(olderApp.cleanSpeed()).isNull();
    }

    @Test
    void threeSixtyIsNorth() {
        assertThat(new LocationUpdateRequest(6.6, 3.36, 360.0, null).cleanHeading()).isEqualTo(0.0);
    }

    @Test
    void aPointStoredBeforeHeadingExistedStillReads() {
        LocationResponse point = LocationService.parse("6.6,3.36,1795160000000");

        assertThat(point.lat()).isEqualTo(6.6);
        assertThat(point.lng()).isEqualTo(3.36);
        assertThat(point.recordedAt()).isEqualTo(Instant.ofEpochMilli(1795160000000L));
        assertThat(point.heading()).isNull();
        assertThat(point.speed()).isNull();
    }

    @Test
    void aPointWithHeadingAndSpeedReadsBack() {
        LocationResponse point = LocationService.parse("6.6,3.36,1795160000000,147.5,12.3");

        assertThat(point.heading()).isEqualTo(147.5);
        assertThat(point.speed()).isEqualTo(12.3);
    }

    @Test
    void aPointWithTheExtrasLeftEmptyReadsAsUnknown() {
        LocationResponse point = LocationService.parse("6.6,3.36,1795160000000,,");

        assertThat(point.heading()).isNull();
        assertThat(point.speed()).isNull();
    }
}
