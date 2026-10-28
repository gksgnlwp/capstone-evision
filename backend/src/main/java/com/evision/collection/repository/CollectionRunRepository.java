package com.evision.collection.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.evision.collection.domain.CollectionRun;
import com.evision.collection.domain.RunStatus;

public interface CollectionRunRepository extends JpaRepository<CollectionRun, Long> {

    /** 기준 시각 이후 시작한 회차(INFO + STATUS)의 API 호출 합계 */
    @Query("select coalesce(sum(r.apiCallCount), 0) from CollectionRun r where r.startedAt >= :from")
    long sumApiCallCountSince(@Param("from") LocalDateTime from);

    List<CollectionRun> findByRunStatus(RunStatus runStatus);
}
