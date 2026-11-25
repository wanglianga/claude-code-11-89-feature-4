package com.waterrepair.compensation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AccountBillRepository extends JpaRepository<AccountBill, Long> {

    Optional<AccountBill> findByBillNo(String billNo);

    Optional<AccountBill> findByAccountNoAndPeriod(String accountNo, String period);

    List<AccountBill> findByPeriodOrderByAccountNoAsc(String period);

    List<AccountBill> findByAccountNoOrderByPeriodDesc(String accountNo);
}
