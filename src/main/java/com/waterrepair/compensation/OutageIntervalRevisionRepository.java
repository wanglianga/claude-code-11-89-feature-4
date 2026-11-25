package com.waterrepair.compensation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OutageIntervalRevisionRepository
        extends JpaRepository<OutageIntervalRevision, Long> {

    List<OutageIntervalRevision> findByIntervalCodeOrderByIdAsc(String intervalCode);
}
