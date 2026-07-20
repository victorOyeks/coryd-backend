package org.coryd.trip.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.trip.config.MatchingProperties;
import org.coryd.trip.entity.FareReport;
import org.coryd.trip.entity.SuggestedFareRange;
import org.coryd.trip.repository.FareReportRepository;
import org.coryd.trip.repository.SuggestedFareRangeRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;

/**
 * D-205 / Section 10.2: nightly recalibration of the non-binding suggested-fare
 * range per corridor, from recent same-corridor postings. Skips corridors with
 * too few data points rather than showing a misleading number.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SuggestedFareJob {

    private final FareReportRepository fareReportRepository;
    private final SuggestedFareRangeRepository suggestedFareRangeRepository;
    private final MatchingProperties matchingProperties;

    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    public void recalculate() {
        List<String> corridorKeys = fareReportRepository.findCorridorKeysWithMinReports(matchingProperties.getMinFareReports());
        if (corridorKeys.isEmpty()) {
            log.info("Suggested-fare recalibration: no corridor has enough data points yet");
            return;
        }

        int updated = 0;
        for (String corridorKey : corridorKeys) {
            List<BigDecimal> fares = fareReportRepository.findTop30ByCorridorKeyOrderByCreatedAtDesc(corridorKey)
                    .stream()
                    .map(FareReport::getFarePerSeat)
                    .toList();

            BigDecimal min = fares.stream().min(Comparator.naturalOrder()).orElse(null);
            BigDecimal max = fares.stream().max(Comparator.naturalOrder()).orElse(null);
            if (min == null) {
                continue;
            }

            SuggestedFareRange range = suggestedFareRangeRepository.findByCorridorKey(corridorKey)
                    .orElseGet(() -> SuggestedFareRange.builder().corridorKey(corridorKey).build());
            range.setMinFare(min);
            range.setMaxFare(max);
            range.setSampleSize(fares.size());
            suggestedFareRangeRepository.save(range);
            updated++;
        }
        log.info("Suggested-fare recalibration complete: {}/{} corridors updated", updated, corridorKeys.size());
    }
}
