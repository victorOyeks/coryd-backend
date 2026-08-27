package org.coryd.trip.repository;

import org.coryd.trip.entity.IssueReport;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;


public interface IssueReportRepository extends JpaRepository<IssueReport, String> {

    /** Dev route reset only - see RouteResetService. */
    long deleteByBookingIdIn(List<String> bookingIds);
}
