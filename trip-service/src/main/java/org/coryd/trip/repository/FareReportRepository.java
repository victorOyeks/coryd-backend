package org.coryd.trip.repository;

import org.coryd.trip.entity.FareReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface FareReportRepository extends JpaRepository<FareReport, String> {

    @Query("""
            select f.corridorKey from FareReport f
            group by f.corridorKey
            having count(f) >= :minReports
            """)
    List<String> findCorridorKeysWithMinReports(int minReports);

    List<FareReport> findTop30ByCorridorKeyOrderByCreatedAtDesc(String corridorKey);
}
