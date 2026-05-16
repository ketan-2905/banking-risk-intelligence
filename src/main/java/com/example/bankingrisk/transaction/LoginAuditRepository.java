package com.example.bankingrisk.transaction;

import com.example.bankingrisk.transaction.model.LoginAudit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.UUID;

public interface LoginAuditRepository extends JpaRepository<LoginAudit, UUID> {

    boolean existsByUserIdAndAnomalyFlagTrueAndCreatedAtAfter(UUID userId, Instant after);
}
