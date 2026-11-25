package com.waterrepair.compensation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {

    List<LedgerEntry> findByAccountNoAndDayOrderByIdAsc(String accountNo, LocalDate day);

    List<LedgerEntry> findByAccountNoOrderByIdAsc(String accountNo);

    List<LedgerEntry> findByBillIdOrderByIdAsc(Long billId);

    Optional<LedgerEntry> findByIdempotencyKey(String idempotencyKey);

    long countByEntryType(String entryType);
}
