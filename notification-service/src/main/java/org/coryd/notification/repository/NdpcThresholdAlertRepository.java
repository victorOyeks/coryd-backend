package org.coryd.notification.repository;

import org.coryd.notification.entity.NdpcThresholdAlert;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NdpcThresholdAlertRepository extends JpaRepository<NdpcThresholdAlert, String> {

    boolean existsByThresholdAndKind(long threshold, NdpcThresholdAlert.Kind kind);
}
