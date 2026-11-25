package com.waterrepair.compensation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface CompensationNoticeRepository extends JpaRepository<CompensationNotice, Long> {

    List<CompensationNotice> findByAccountNoAndDayOrderByIdAsc(String accountNo, LocalDate day);

    List<CompensationNotice> findByAccountNoOrderByIdAsc(String accountNo);
}
