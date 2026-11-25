package com.waterrepair.compensation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface OutageIntervalRepository extends JpaRepository<OutageInterval, Long> {

    Optional<OutageInterval> findByIntervalCode(String intervalCode);

    List<OutageInterval> findByAccountNoAndDayOrderByIdAsc(String accountNo, LocalDate day);

    /**
     * 与给定自然日时间窗 [dayStart, dayEnd) 有交集的全部区间（含跨午夜区间）。
     * 区间是否真正参与并集仍由其 state/endAt 决定。
     */
    @Query("select i from OutageInterval i where i.accountNo = :accountNo "
            + "and i.startAt < :dayEnd and (i.endAt is null or i.endAt > :dayStart) "
            + "order by i.id asc")
    List<OutageInterval> findIntersectingDay(@Param("accountNo") String accountNo,
                                             @Param("dayStart") OffsetDateTime dayStart,
                                             @Param("dayEnd") OffsetDateTime dayEnd);

    List<OutageInterval> findByAccountNoOrderByDayAscIdAsc(String accountNo);

    List<OutageInterval> findByDay(LocalDate day);

    List<OutageInterval> findByIncidentId(Long incidentId);

    List<OutageInterval> findByTicketCodeAndAddressCode(String ticketCode, String addressCode);

    List<OutageInterval> findByAccountNoAndDayBetweenOrderByDayAscIdAsc(
            String accountNo, LocalDate from, LocalDate to);
}
