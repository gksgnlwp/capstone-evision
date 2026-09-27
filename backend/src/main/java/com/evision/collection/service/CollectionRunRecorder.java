package com.evision.collection.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.evision.collection.domain.CollectionRun;
import com.evision.collection.domain.RunCounts;
import com.evision.collection.domain.RunStatus;
import com.evision.collection.domain.RunType;
import com.evision.collection.repository.CollectionRunRepository;

/**
 * 수집 회차 기록. 저장 트랜잭션이 롤백돼도 회차 기록은 남도록 REQUIRES_NEW로 분리한다.
 */
@Component
public class CollectionRunRecorder {

    private static final Logger log = LoggerFactory.getLogger(CollectionRunRecorder.class);

    private final CollectionRunRepository runRepository;
    private final Clock clock;

    public CollectionRunRecorder(CollectionRunRepository runRepository, Clock clock) {
        this.runRepository = runRepository;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long start(RunType runType) {
        return runRepository.save(CollectionRun.start(runType, now())).getId();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finish(long runId, RunStatus status, RunCounts counts, String errorCode, String errorMessage) {
        CollectionRun run = runRepository.findById(runId)
                .orElseThrow(() -> new IllegalStateException("수집 회차가 없습니다: " + runId));
        run.finish(status, now(), counts, errorCode, errorMessage);
    }

    /** 시작하기 전에 거부된 회차(예: 일일 한도 초과)를 FAILED로 바로 기록한다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long recordRejected(RunType runType, String errorCode, String errorMessage) {
        CollectionRun run = CollectionRun.start(runType, now());
        run.finish(RunStatus.FAILED, now(), RunCounts.empty(), errorCode, errorMessage);
        return runRepository.save(run).getId();
    }

    /** 앱이 재시작될 때 RUNNING으로 남은 회차를 FAILED(ABORTED)로 정리한다. */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public int abortDanglingRuns() {
        List<CollectionRun> dangling = runRepository.findByRunStatus(RunStatus.RUNNING);
        dangling.forEach(run -> run.abort(now()));
        if (!dangling.isEmpty()) {
            log.warn("RUNNING으로 남은 수집 회차 {}건을 ABORTED로 정리했습니다.", dangling.size());
        }
        return dangling.size();
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }
}
