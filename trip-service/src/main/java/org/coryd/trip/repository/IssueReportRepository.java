package org.coryd.trip.repository;

import org.coryd.trip.entity.IssueReport;
import org.springframework.data.jpa.repository.JpaRepository;


public interface IssueReportRepository extends JpaRepository<IssueReport, String> {
}
