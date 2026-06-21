package com.example.bankingrisk.transaction;

import com.example.bankingrisk.transaction.model.Beneficiary;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface BeneficiaryRepository extends JpaRepository<Beneficiary, UUID> {

    boolean existsByOwnerUserIdAndDestinationAccountId(UUID ownerUserId, UUID destinationAccountId);

    Optional<Beneficiary> findByOwnerUserIdAndDestinationAccountId(UUID ownerUserId, UUID destinationAccountId);
}
