package com.evision.aggregation;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.evision.aggregation.OccupancyWindowCalculator.PointObservation;

/**
 * 집계용 SQL. 창 시작 이전의 마지막 상태가 필요하므로 LATERAL로 "t 이하 최신 이력"을 구한다.
 * 인덱스는 UNIQUE (charger_id, status_updated_at)를 그대로 쓴다.
 */
@Repository
public class OccupancyAggregationRepository {

    /** 서비스 대상(station_access 존재) 충전소와 급속충전기 수 */
    static final String TARGET_STATIONS = """
            SELECT s.station_id,
                   count(c.charger_id) FILTER (WHERE c.is_fast AND c.del_yn = 'N') AS fast_count
            FROM station s
            LEFT JOIN charger c ON c.station_id = s.station_id
            WHERE EXISTS (SELECT 1 FROM station_access sa WHERE sa.station_id = s.station_id)
            GROUP BY s.station_id
            """;

    /** 관측 시점 6개 × 서비스 대상 급속충전기의 정규화 상태 분포 */
    static final String POINT_COUNTS = """
            WITH pts AS (
              SELECT CAST(? AS timestamp) + make_interval(mins => n * 5) AS t
              FROM generate_series(0, 5) AS n
            ), tg AS (
              SELECT c.charger_id, c.station_id
              FROM charger c
              WHERE c.is_fast AND c.del_yn = 'N'
                AND EXISTS (SELECT 1 FROM station_access sa WHERE sa.station_id = c.station_id)
            )
            SELECT tg.station_id, pts.t,
                   count(*) FILTER (WHERE st.ns = 'AVAILABLE')   AS available,
                   count(*) FILTER (WHERE st.ns = 'CHARGING')    AS charging,
                   count(*) FILTER (WHERE st.ns = 'UNAVAILABLE') AS unavailable,
                   count(*) FILTER (WHERE st.ns = 'UNKNOWN')     AS unknown
            FROM pts
            CROSS JOIN tg
            LEFT JOIN LATERAL (
              SELECT h.status_code FROM charger_status_history h
              WHERE h.charger_id = tg.charger_id AND h.status_updated_at <= pts.t
              ORDER BY h.status_updated_at DESC
              LIMIT 1
            ) latest ON true
            LEFT JOIN code_dictionary cd
              ON cd.code_group = 'CHARGER_STAT' AND cd.code = CAST(latest.status_code AS text)
            CROSS JOIN LATERAL (SELECT coalesce(cd.normalized_status, 'UNKNOWN') AS ns) st
            GROUP BY tg.station_id, pts.t
            """;

    static final String SUCCESSFUL_STATUS_RUNS = """
            SELECT started_at FROM collection_run
            WHERE run_type = 'STATUS' AND run_status IN ('SUCCESS', 'PARTIAL')
              AND started_at >= ? AND started_at < ?
            """;

    static final String UPSERT = """
            INSERT INTO occupancy_30m (station_id, window_start, fast_charger_count, occupancy_rate, available,
                                       full_minutes, valid_minutes, observed_points, computed_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (station_id, window_start) DO UPDATE SET
              fast_charger_count = EXCLUDED.fast_charger_count, occupancy_rate = EXCLUDED.occupancy_rate,
              available = EXCLUDED.available, full_minutes = EXCLUDED.full_minutes,
              valid_minutes = EXCLUDED.valid_minutes, observed_points = EXCLUDED.observed_points,
              computed_at = EXCLUDED.computed_at
            """;

    private final JdbcTemplate jdbc;

    public OccupancyAggregationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** station_id → 급속충전기 수 */
    public Map<Long, Integer> targetStations() {
        Map<Long, Integer> map = new HashMap<>();
        jdbc.query(TARGET_STATIONS, rs -> {
            map.put(rs.getLong(1), rs.getInt(2));
        });
        return map;
    }

    /** (station_id, 시점) → 상태 분포. 유효성은 호출한 쪽에서 채운다 */
    public Map<Long, Map<LocalDateTime, PointObservation>> pointCounts(LocalDateTime windowStart) {
        Map<Long, Map<LocalDateTime, PointObservation>> map = new HashMap<>();
        jdbc.query(POINT_COUNTS, rs -> {
            long stationId = rs.getLong(1);
            LocalDateTime t = rs.getTimestamp(2).toLocalDateTime();
            map.computeIfAbsent(stationId, k -> new HashMap<>())
                    .put(t, new PointObservation(t, true, rs.getInt(3), rs.getInt(4), rs.getInt(5), rs.getInt(6)));
        }, Timestamp.valueOf(windowStart));
        return map;
    }

    public List<LocalDateTime> successfulStatusRunTimes(LocalDateTime from, LocalDateTime to) {
        return jdbc.queryForList(SUCCESSFUL_STATUS_RUNS, Timestamp.class, Timestamp.valueOf(from), Timestamp.valueOf(to))
                .stream().map(Timestamp::toLocalDateTime).toList();
    }

    public record Row(long stationId, LocalDateTime windowStart, int fastChargerCount, BigDecimal occupancyRate,
            Boolean available, short fullMinutes, short validMinutes, short observedPoints) {
    }

    public void upsert(List<Row> rows, LocalDateTime computedAt) {
        jdbc.batchUpdate(UPSERT, rows, 500, (ps, r) -> {
            ps.setLong(1, r.stationId());
            ps.setObject(2, r.windowStart());
            ps.setShort(3, (short) Math.min(r.fastChargerCount(), Short.MAX_VALUE));
            ps.setBigDecimal(4, r.occupancyRate());
            ps.setObject(5, r.available(), Types.BOOLEAN);
            ps.setShort(6, r.fullMinutes());
            ps.setShort(7, r.validMinutes());
            ps.setShort(8, r.observedPoints());
            ps.setObject(9, computedAt);
        });
    }
}
