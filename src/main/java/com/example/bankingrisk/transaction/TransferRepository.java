package com.example.bankingrisk.transaction;

import com.example.bankingrisk.transaction.model.Transfer;
import com.example.bankingrisk.transaction.model.TransferStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public interface TransferRepository extends JpaRepository<Transfer, UUID> {

    Optional<Transfer> findByRequestId(String requestId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM Transfer t WHERE t.id = :id")
    Optional<Transfer> lockById(@Param("id") UUID id);

    @Query("SELECT COALESCE(SUM(t.amountMinor), 0) FROM Transfer t " +
           "WHERE t.sourceAccountId = :accountId " +
           "AND t.status IN :statuses " +
           "AND t.createdAt >= :since")
    long sumRecentOutgoingAmounts(
        @Param("accountId") UUID accountId,
        @Param("statuses") Collection<TransferStatus> statuses,
        @Param("since") Instant since
    );
}
