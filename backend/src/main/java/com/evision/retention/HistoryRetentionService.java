package com.evision.retention;

import java.time.Clock;
import java.time.LocalDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.evision.collection.service.SingleRunGuard;

/**
 * 비대상 충전기 상태 이력 정리. 서버 디스크가 이력 때문에 하루 약 0.5GB씩 늘고, 그중 대부분이 서비스 대상이 아닌 충전기라서
 * 서비스 대상이 아닌 충전기의 이력은 최근 keep-days일만 남긴다.
 *
 * <ul>
 *   <li>서비스 대상(station_access에 매핑된 충전소)의 충전기는 완속까지 모두 그대로 둔다</li>
 *   <li>충전기마다 가장 최근 이력 1건은 지우지 않는다. 상태가 오래 안 바뀐 충전기도 현재 상태를 알 수 있고,
 *       나중에 서비스 대상이 되면 집계가 바로 이 상태부터 시작한다. 기본정보 수집이 같은 이력을 다시 넣지도 않는다</li>
 *   <li>history_id 범위로 나눠 범위마다 따로 커밋한다 (한 번에 수천만 건을 지우면 WAL이 디스크를 채울 수 있다)</li>
 *   <li>매핑이 하나도 없으면(기준 데이터 적재 전) 모두 비대상으로 보이므로 아무것도 지우지 않는다</li>
 * </ul>
 * 지운 공간은 PostgreSQL이 다시 쓰므로 디스크가 더 늘지 않는다. 운영체제에 돌려주려면 VACUUM FULL을 따로 해야 한다.
 */
@Service
public class HistoryRetentionService {

    private static final Logger log = LoggerFactory.getLogger(HistoryRetentionService.class);

    static final String DELETE_RANGE = """
            DELETE FROM charger_status_history h
            WHERE h.history_id >= ? AND h.history_id < ?
              AND h.status_updated_at < ?
              AND NOT EXISTS (SELECT 1 FROM charger c JOIN station_access a ON a.station_id = c.station_id
                              WHERE c.charger_id = h.charger_id)
              AND EXISTS (SELECT 1 FROM charger_status_history n
                          WHERE n.charger_id = h.charger_id AND n.status_updated_at > h.status_updated_at)
            """;

    private final SingleRunGuard guard = new SingleRunGuard();

    private final JdbcTemplate jdbc;
    private final RetentionProperties properties;
    private final Clock clock;

    public HistoryRetentionService(JdbcTemplate jdbc, RetentionProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.clock = clock;
    }

    public record Result(long deleted, int batches) {
    }

    /** 스케줄러용: 지금부터 keep-days일 전보다 오래된 비대상 이력을 지운다 */
    public void purgeExpired() {
        LocalDateTime cutoff = LocalDateTime.now(clock).minusDays(properties.keepDays());
        boolean ran = guard.runExclusively(() -> purgeBefore(cutoff));
        if (!ran) {
            log.warn("이전 이력 정리가 아직 실행 중이라 건너뜁니다.");
        }
    }

    /** cutoff보다 이전에 바뀐 비대상 충전기 이력을 지운다 (충전기별 최신 1건 제외). 트랜잭션 없이 범위마다 커밋한다. */
    public Result purgeBefore(LocalDateTime cutoff) {
        if (jdbc.queryForObject("SELECT count(*) FROM station_access", Long.class) == 0) {
            log.warn("station_access가 비어 있어 이력 정리를 건너뜁니다 (기준 데이터 적재 전).");
            return new Result(0, 0);
        }
        Long minId = jdbc.queryForObject("SELECT min(history_id) FROM charger_status_history", Long.class);
        if (minId == null) {
            return new Result(0, 0);
        }
        long endId = firstIdCollectedAtOrAfter(minId, cutoff);
        long started = System.currentTimeMillis();
        long deleted = 0;
        int batches = 0;
        for (long from = minId; from < endId; from += properties.batchSize()) {
            long to = Math.min(from + properties.batchSize(), endId);
            deleted += jdbc.update(DELETE_RANGE, from, to, cutoff);
            batches++;
        }
        log.info("비대상 충전기 이력 정리: {} 이전 {}건 삭제 ({}개 범위, {}ms)", cutoff, deleted, batches,
                System.currentTimeMillis() - started);
        return new Result(deleted, batches);
    }

    /**
     * collected_at이 cutoff 이상인 첫 history_id. 이력은 수집 순서대로 쌓여 history_id와 collected_at이 함께 커지므로
     * 인덱스 없는 collected_at을 전체 스캔하지 않고 기본키로 이분 탐색한다. 모두 cutoff 이전이면 max + 1.
     */
    long firstIdCollectedAtOrAfter(long minId, LocalDateTime cutoff) {
        long lo = minId;
        long hi = jdbc.queryForObject("SELECT max(history_id) FROM charger_status_history", Long.class) + 1;
        while (lo < hi) {
            long mid = lo + (hi - lo) / 2;
            LocalDateTime at = jdbc.query("""
                    SELECT collected_at FROM charger_status_history
                    WHERE history_id >= ? ORDER BY history_id LIMIT 1""",
                    rs -> rs.next() ? rs.getTimestamp(1).toLocalDateTime() : null, mid);
            if (at == null || !at.isBefore(cutoff)) {
                hi = mid;
            } else {
                lo = mid + 1;
            }
        }
        return lo;
    }
}
