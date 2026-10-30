package org.coryd.profile.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NameMatcherTest {

    @Test
    void aMarriedNameSharesTheFirstName() {
        assertThat(NameMatcher.bestOrder("Grace", "Oyekanmi", "GRACE", "DARE")).isLessThan(0.85);
        assertThat(NameMatcher.sharesAName(List.of("Grace", "Oyekanmi"),
                List.of("GRACE", "OLUWAFUNMILOLA", "DARE", "GRACE OLUWAFUNMILOLA DARE"), 0.85)).isTrue();
    }

    @Test
    void aMiddleNameOnTheRecordCountsAndSpellingDriftIsTolerated() {
        assertThat(NameMatcher.sharesAName(List.of("Funmilola", "Adebayo"), List.of("GRACE", "FUNMILOLA", "DARE"), 0.85)).isTrue();
        assertThat(NameMatcher.sharesAName(List.of("Oluwatimilehin", "Ojo"), List.of("OLUWATIMILEYIN", "ADE"), 0.85)).isTrue();
    }

    @Test
    void differentPeopleAndInitialsDontCount() {
        assertThat(NameMatcher.sharesAName(List.of("Tunde", "Bakare"), List.of("CHIOMA", "EZE"), 0.85)).isFalse();
        assertThat(NameMatcher.sharesAName(List.of("A", "Bakare"), List.of("A", "EZE"), 0.85)).isFalse();
        assertThat(NameMatcher.sharesAName(List.of("Tunde", "Bakare"), java.util.Arrays.asList(null, "", "EZE"), 0.85)).isFalse();
    }
}
