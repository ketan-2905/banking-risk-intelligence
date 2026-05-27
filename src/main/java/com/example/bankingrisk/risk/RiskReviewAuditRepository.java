package com.example.bankingrisk.risk;

import com.example.bankingrisk.risk.model.RiskReviewAudit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface RiskReviewAuditRepository extends JpaRepository<RiskReviewAudit, UUID> {

    List<RiskReviewAudit> findByAlertId(UUID alertId);
}
