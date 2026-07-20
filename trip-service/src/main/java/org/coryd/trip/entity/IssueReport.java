package org.coryd.trip.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.coryd.common.entity.BaseEntity;
import org.coryd.common.enums.IssueCategory;


@Entity
@Table(name = "issue_reports", schema = "trip")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class IssueReport extends BaseEntity {

    @Column(nullable = false)
    private String bookingId;

    @Column(nullable = false)
    private String reportedBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private IssueCategory category;

    @Column(nullable = false)
    private String description;

    @Column(nullable = false, unique = true, length = 20)
    private String referenceNumber;

    @Column(nullable = false, length = 20)
    private String status; // OPEN, IN_REVIEW, RESOLVED
}
