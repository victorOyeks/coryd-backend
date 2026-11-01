package org.coryd.trip.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** What counts as a date or a day in the Trip History search box - anything else is searched as text. */
class OpsRiderTripSearchTest {

    @Test
    void aDayAndMonthInAnyOfTheUsualShapes() {
        OpsRiderTripService.DateTerm expected = new OpsRiderTripService.DateTerm(0, 7, 11, 0);
        assertThat(OpsRiderTripService.DateTerm.parse("11 July")).isEqualTo(expected);
        assertThat(OpsRiderTripService.DateTerm.parse("11th jul")).isEqualTo(expected);
        assertThat(OpsRiderTripService.DateTerm.parse("Wed, 11th July")).isEqualTo(expected);
        assertThat(OpsRiderTripService.DateTerm.parse("July 11")).isEqualTo(expected);
        assertThat(OpsRiderTripService.DateTerm.parse("11/7")).isEqualTo(expected);
    }

    @Test
    void aFullDate() {
        OpsRiderTripService.DateTerm expected = new OpsRiderTripService.DateTerm(2026, 7, 11, 0);
        assertThat(OpsRiderTripService.DateTerm.parse("2026-07-11")).isEqualTo(expected);
        assertThat(OpsRiderTripService.DateTerm.parse("11 July 2026")).isEqualTo(expected);
        assertThat(OpsRiderTripService.DateTerm.parse("11/7/2026")).isEqualTo(expected);
    }

    @Test
    void aDayOfTheWeekOrAMonth() {
        assertThat(OpsRiderTripService.DateTerm.parse("wed")).isEqualTo(new OpsRiderTripService.DateTerm(0, 0, 0, 3));
        assertThat(OpsRiderTripService.DateTerm.parse("Sunday")).isEqualTo(new OpsRiderTripService.DateTerm(0, 0, 0, 7));
        assertThat(OpsRiderTripService.DateTerm.parse("july")).isEqualTo(new OpsRiderTripService.DateTerm(0, 7, 0, 0));
    }

    @Test
    void anythingElseIsText() {
        assertThat(OpsRiderTripService.DateTerm.parse("Ikorodu")).isNull();
        assertThat(OpsRiderTripService.DateTerm.parse("11")).isNull();
        assertThat(OpsRiderTripService.DateTerm.parse("32 July")).isNull();
        assertThat(OpsRiderTripService.DateTerm.parse(" ")).isNull();
        assertThat(OpsRiderTripService.DateTerm.parse(null)).isNull();
    }
}
