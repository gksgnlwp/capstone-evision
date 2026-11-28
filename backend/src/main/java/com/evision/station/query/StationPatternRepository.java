package com.evision.station.query;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 충전소 혼잡 패턴용 30분 집계 조회. (station_id, window_start) INCLUDE (occupancy_rate) 인덱스(V8)만 읽는다.
 */
@Repository
public class StationPatternRepository {

    /** 요일(월=1 … 일=7)·하루 중 30분 칸(0 … 47)별 구간 수, 사용률을 아는 구간 수, 평균 사용률, 쾌적 구간 수 */
    static final String SLOT_STATS = """
            SELECT extract(isodow FROM window_start)::int                                      AS dow,
                   extract(hour FROM window_start)::int * 2 + extract(minute FROM window_start)::int / 30 AS slot,
                   count(*)                                                                    AS total,
                   count(occupancy_rate)                                                       AS n,
                   avg(occupancy_rate)                                                         AS avg_rate,
                   count(*) FILTER (WHERE occupancy_rate < ?)                                  AS comfort
            FROM occupancy_30m
            WHERE station_id = ? AND window_start >= ? AND window_start < ?
            GROUP BY 1, 2
            """;

    private final JdbcTemplate jdbc;

    public StationPatternRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record SlotRow(int dayOfWeek, int slot, int total, int valid, Double averageRate, int comfort) {
    }

    /** [from, to) 구간의 요일·시간대별 집계. 구간이 하나도 없는 칸은 나오지 않는다. */
    public List<SlotRow> slotStats(long stationId, LocalDateTime from, LocalDateTime to, double comfortThreshold) {
        return jdbc.query(SLOT_STATS, (rs, i) -> new SlotRow(
                rs.getInt("dow"), rs.getInt("slot"), rs.getInt("total"), rs.getInt("n"),
                rs.getObject("avg_rate") == null ? null : rs.getDouble("avg_rate"), rs.getInt("comfort")),
                comfortThreshold, stationId, from, to);
    }
}
