package com.example.bankingrisk.risk;

import com.example.bankingrisk.risk.model.RiskAlert;
import com.example.bankingrisk.risk.model.RiskAlertStatus;
import com.example.bankingrisk.risk.model.RiskLevel;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RiskAlertRepository extends JpaRepository<RiskAlert, UUID> {

    List<RiskAlert> findByStatusInOrderByCreatedAtAsc(List<RiskAlertStatus> statuses);

    List<RiskAlert> findByTransferId(UUID transferId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM RiskAlert a WHERE a.id = :id")
    Optional<RiskAlert> lockById(@Param("id") UUID id);

    @Query("SELECT a FROM RiskAlert a " +
           "WHERE (:status IS NULL OR a.status = :status) " +
           "AND (:riskLevel IS NULL OR a.riskLevel = :riskLevel) " +
           "ORDER BY a.createdAt DESC")
    List<RiskAlert> findWithFilters(
        @Param("status") RiskAlertStatus status,
        @Param("riskLevel") RiskLevel riskLevel,
        Pageable pageable
    );
}
