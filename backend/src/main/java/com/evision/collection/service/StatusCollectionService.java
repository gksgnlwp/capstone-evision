package com.evision.collection.service;

import java.time.Clock;
import java.time.LocalDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import com.evision.collection.domain.RunCounts;
import com.evision.collection.domain.RunStatus;
import com.evision.collection.domain.RunType;
import com.evision.external.evcharger.EvChargerClient;
import com.evision.external.evcharger.SensitiveDataMasker;
import com.evision.external.evcharger.StatusFetchResult;

/**
 * UC-07 / SD-06 / OP-10 충전기 상태 5분 수집.
 *
 * <pre>
 * 1. 실행 중인 회차가 있으면 건너뛴다
 * 2. 일일 호출 한도 확인 → 부족하면 FAILED(BUDGET_EXCEEDED)
 * 3. 회차 시작 (RUNNING)                    [별도 트랜잭션]
 * 4. 전국 상태 조회 (페이지 순회, 재시도)     [트랜잭션 밖]
 * 5. 정규화 (미등록 → unknown, 형식 오류 → invalid)
 * 6. 이력 적재 + 현재 상태 갱신             [단일 트랜잭션]
 * 7. 회차 종료 (SUCCESS / PARTIAL / FAILED) [별도 트랜잭션]
 * </pre>
 */
@Service
public class StatusCollectionService {

    private static final Logger log = LoggerFactory.getLogger(StatusCollectionService.class);

    static final String DB_WRITE_FAILED = "DB_WRITE_FAILED";
    static final String INTERNAL_ERROR = "INTERNAL_ERROR";

    private final SingleRunGuard guard = new SingleRunGuard();

    private final ApiCallBudget budget;
    private final CollectionRunRecorder recorder;
    private final EvChargerClient client;
    private final ChargerKeyCache chargerKeyCache;
    private final ChargerStatusCodes statusCodes;
    private final StateNormalizer normalizer;
    private final StateChangePersister persister;
    private final Clock clock;

    public StatusCollectionService(ApiCallBudget budget, CollectionRunRecorder recorder, EvChargerClient client,
            ChargerKeyCache chargerKeyCache, ChargerStatusCodes statusCodes, StateNormalizer normalizer,
            StateChangePersister persister, Clock clock) {
        this.budget = budget;
        this.recorder = recorder;
        this.client = client;
        this.chargerKeyCache = chargerKeyCache;
        this.statusCodes = statusCodes;
        this.normalizer = normalizer;
        this.persister = persister;
        this.clock = clock;
    }

    /**
     * OP-10 collectChargerStates
     *
     * @return 실행했으면 true, 이전 회차가 아직 실행 중이라 건너뛰었으면 false
     */
    public boolean collectChargerStates() {
        boolean ran = guard.runExclusively(this::collectOnce);
        if (!ran) {
            log.warn("이전 상태 수집 회차가 아직 실행 중이라 이번 회차를 건너뜁니다.");
        }
        return ran;
    }

    private void collectOnce() {
        int allowance = budget.remainingToday();
        if (allowance <= 0) {
            recorder.recordRejected(RunType.STATUS, EvChargerClient.BUDGET_EXCEEDED, "일일 API 호출 한도에 도달했습니다.");
            log.warn("일일 API 호출 한도에 도달해 상태 수집을 건너뜁니다.");
            return;
        }

        long runId = recorder.start(RunType.STATUS);
        StatusFetchResult fetch = null;
        NormalizationResult normalized = null;
        try {
            fetch = client.fetchAllStatus(allowance);
            if (fetch.pagesReceived() == 0) {
                recorder.finish(runId, RunStatus.FAILED, counts(fetch, null, null), fetch.errorCode(), fetch.errorMessage());
                log.error("상태 수집 실패 runId={}: {} {}", runId, fetch.errorCode(), fetch.errorMessage());
                return;
            }

            normalized = normalizer.normalize(fetch.items(), chargerKeyCache.get(), statusCodes.load());
            PersistResult saved = persister.persist(runId, normalized.states(), LocalDateTime.now(clock));

            RunStatus status = fetch.complete() ? RunStatus.SUCCESS : RunStatus.PARTIAL;
            RunCounts counts = counts(fetch, normalized, saved);
            recorder.finish(runId, status, counts, fetch.errorCode(), fetch.errorMessage());
            log.info("상태 수집 {} runId={} 수신={} 신규={} 중복={} 미등록={} 오류={} 호출={}", status, runId,
                    counts.fetchedCount(), counts.insertedCount(), counts.duplicateCount(),
                    counts.unknownCount(), counts.invalidCount(), counts.apiCallCount());
        } catch (DataAccessException e) {
            log.error("상태 이력 저장 실패로 회차를 롤백합니다. runId={}", runId, e);
            recorder.finish(runId, RunStatus.FAILED, counts(fetch, normalized, null), DB_WRITE_FAILED,
                    SensitiveDataMasker.mask(e.getMostSpecificCause().getMessage()));
        } catch (RuntimeException e) {
            log.error("상태 수집 중 예외 runId={}", runId, e);
            recorder.finish(runId, RunStatus.FAILED, counts(fetch, normalized, null), INTERNAL_ERROR,
                    SensitiveDataMasker.mask(e.getClass().getSimpleName() + ": " + e.getMessage()));
        }
    }

    private static RunCounts counts(StatusFetchResult fetch, NormalizationResult normalized, PersistResult saved) {
        if (fetch == null) {
            return RunCounts.empty();
        }
        int unknown = normalized == null ? 0 : normalized.unknownCount();
        int invalid = (normalized == null ? 0 : normalized.invalidCount()) + (saved == null ? 0 : saved.conflictCount());
        return new RunCounts(
                fetch.apiCallCount(),
                fetch.totalCount(),
                fetch.items().size(),
                saved == null ? 0 : saved.insertedCount(),
                saved == null ? 0 : saved.duplicateCount(),
                unknown,
                invalid,
                fetch.retryCount());
    }
}
