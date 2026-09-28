package com.evision.collection.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import com.evision.collection.domain.RunCounts;
import com.evision.collection.domain.RunStatus;
import com.evision.collection.domain.RunType;
import com.evision.common.code.NormalizedStatus;
import com.evision.external.evcharger.EvChargerClient;
import com.evision.external.evcharger.FetchSummary;
import com.evision.external.evcharger.PageHandlingException;
import com.evision.external.evcharger.SensitiveDataMasker;
import com.evision.external.evcharger.dto.InfoItem;

/**
 * UC-07 / SD-07 / OP-09 충전소 기본정보 일 단위 갱신.
 *
 * <pre>
 * 1. 실행 중인 회차가 있으면 건너뛴다
 * 2. 일일 호출 한도 확인 (상태 수집과 합산)
 * 3. 회차 시작 (INFO, RUNNING)                         [별도 트랜잭션]
 * 4. getChargerInfo 전국 페이지 순회 → 페이지마다 적재   [페이지당 트랜잭션]
 *    - station upsert, charger upsert
 *    - 같은 행의 상태 항목 → 이력 source_api=INFO
 * 5. 모든 페이지를 받았으면 충전소 삭제 여부 동기화
 * 6. 충전기 키 캐시 무효화 (상태 수집이 새 충전기를 인식하도록)
 * 7. 회차 종료                                         [별도 트랜잭션]
 * </pre>
 */
@Service
public class StationCatalogService {

    private static final Logger log = LoggerFactory.getLogger(StationCatalogService.class);

    private final SingleRunGuard guard = new SingleRunGuard();

    private final ApiCallBudget budget;
    private final CollectionRunRecorder recorder;
    private final EvChargerClient client;
    private final CatalogNormalizer normalizer;
    private final CatalogPageWriter pageWriter;
    private final ChargerStatusCodes statusCodes;
    private final ChargerKeyCache chargerKeyCache;
    private final Clock clock;

    public StationCatalogService(ApiCallBudget budget, CollectionRunRecorder recorder, EvChargerClient client,
            CatalogNormalizer normalizer, CatalogPageWriter pageWriter, ChargerStatusCodes statusCodes,
            ChargerKeyCache chargerKeyCache, Clock clock) {
        this.budget = budget;
        this.recorder = recorder;
        this.client = client;
        this.normalizer = normalizer;
        this.pageWriter = pageWriter;
        this.statusCodes = statusCodes;
        this.chargerKeyCache = chargerKeyCache;
        this.clock = clock;
    }

    /**
     * OP-09 refreshStationCatalog
     *
     * @return 실행했으면 true, 이전 회차가 아직 실행 중이라 건너뛰었으면 false
     */
    public boolean refreshStationCatalog() {
        boolean ran = guard.runExclusively(this::refreshOnce);
        if (!ran) {
            log.warn("이전 기본정보 갱신 회차가 아직 실행 중이라 이번 회차를 건너뜁니다.");
        }
        return ran;
    }

    private void refreshOnce() {
        int allowance = budget.remainingToday();
        if (allowance <= 0) {
            recorder.recordRejected(RunType.INFO, EvChargerClient.BUDGET_EXCEEDED, "일일 API 호출 한도에 도달했습니다.");
            log.warn("일일 API 호출 한도에 도달해 기본정보 갱신을 건너뜁니다.");
            return;
        }

        LocalDateTime startedAt = LocalDateTime.now(clock);
        long runId = recorder.start(RunType.INFO);
        Map<String, NormalizedStatus> codes = statusCodes.load();
        Tally tally = new Tally();
        FetchSummary fetch = null;

        try {
            fetch = client.fetchAllInfo(allowance, items -> writePage(runId, items, codes, tally));
            if (tally.pages > 0) {
                chargerKeyCache.invalidate();
            }
            if (fetch.pagesReceived() == 0) {
                recorder.finish(runId, RunStatus.FAILED, tally.counts(fetch), fetch.errorCode(), fetch.errorMessage());
                log.error("기본정보 갱신 실패 runId={}: {} {}", runId, fetch.errorCode(), fetch.errorMessage());
                return;
            }
            if (fetch.complete()) {
                int synced = pageWriter.syncStationDeletion(startedAt);
                log.info("충전소 삭제 여부 동기화: {}곳", synced);
            }

            RunStatus status = fetch.complete() ? RunStatus.SUCCESS : RunStatus.PARTIAL;
            recorder.finish(runId, status, tally.counts(fetch), fetch.errorCode(), fetch.errorMessage());
            log.info("기본정보 갱신 {} runId={} 수신={} 충전소={} 충전기={} 이력신규={} 이력중복={} 오류={} 호출={}",
                    status, runId, fetch.fetchedCount(), tally.stations, tally.chargers, tally.inserted,
                    tally.duplicates, tally.invalid, fetch.apiCallCount());
        } catch (PageHandlingException e) {
            chargerKeyCache.invalidate();
            RuntimeException cause = e.getCause();
            log.error("기본정보 적재 실패 runId={} ({}페이지까지 반영됨)", runId, tally.pages, cause);
            if (cause instanceof DataAccessException dae) {
                recorder.finish(runId, RunStatus.FAILED, tally.counts(e.summary()), StatusCollectionService.DB_WRITE_FAILED,
                        SensitiveDataMasker.mask(dae.getMostSpecificCause().getMessage()));
            } else {
                recorder.finish(runId, RunStatus.FAILED, tally.counts(e.summary()), StatusCollectionService.INTERNAL_ERROR,
                        SensitiveDataMasker.mask(cause.getClass().getSimpleName() + ": " + cause.getMessage()));
            }
        } catch (RuntimeException e) {
            chargerKeyCache.invalidate();
            log.error("기본정보 갱신 중 예외 runId={}", runId, e);
            recorder.finish(runId, RunStatus.FAILED, tally.counts(fetch), StatusCollectionService.INTERNAL_ERROR,
                    SensitiveDataMasker.mask(e.getClass().getSimpleName() + ": " + e.getMessage()));
        }
    }

    private void writePage(long runId, List<InfoItem> items, Map<String, NormalizedStatus> codes, Tally tally) {
        CatalogNormalizer.Result normalized = normalizer.normalize(items);
        CatalogPageWriter.PageResult result = pageWriter.writePage(runId, normalized.entries(),
                LocalDateTime.now(clock), codes);
        tally.add(items.size(), normalized.invalidCount(), result);
    }

    /** 페이지별 결과 누적. 중간에 실패해도 그때까지의 건수를 회차 기록에 남긴다. */
    private static final class Tally {
        int pages;
        int fetched;
        int invalid;
        int stations;
        int chargers;
        int inserted;
        int duplicates;

        void add(int fetchedInPage, int invalidInPage, CatalogPageWriter.PageResult result) {
            pages++;
            fetched += fetchedInPage;
            invalid += invalidInPage + result.history().conflictCount();
            stations += result.stationCount();
            chargers += result.chargerCount();
            inserted += result.history().insertedCount();
            duplicates += result.history().duplicateCount();
        }

        RunCounts counts(FetchSummary fetch) {
            return new RunCounts(
                    fetch == null ? 0 : fetch.apiCallCount(),
                    fetch == null ? null : fetch.totalCount(),
                    fetched, inserted, duplicates, 0, invalid,
                    fetch == null ? 0 : fetch.retryCount());
        }
    }
}
