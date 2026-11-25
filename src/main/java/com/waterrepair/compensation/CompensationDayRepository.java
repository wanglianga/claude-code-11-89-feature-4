package com.waterrepair.compensation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface CompensationDayRepository extends JpaRepository<CompensationDay, Long> {

    Optional<CompensationDay> findByAccountNoAndDay(String accountNo, LocalDate day);

    List<CompensationDay> findByAccountNoOrderByDayAsc(String accountNo);

    List<CompensationDay> findByBillId(Long billId);

    List<CompensationDay> findByDayBetweenOrderByDayAscAccountNoAsc(LocalDate from, LocalDate to);
}
