package com.waterrepair.compensation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ServiceAccountRepository extends JpaRepository<ServiceAccount, Long> {

    Optional<ServiceAccount> findByAccountNo(String accountNo);

    /** 通过关联地址反查服务账户（一个地址原则上对应一个居民账户，也兼容多账户）。 */
    @Query("select distinct a from ServiceAccount a join a.addressCodes ac where ac = :addressCode")
    List<ServiceAccount> findByAddressCode(@Param("addressCode") String addressCode);
}
