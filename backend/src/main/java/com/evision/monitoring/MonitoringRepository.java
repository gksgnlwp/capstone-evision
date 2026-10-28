package com.evision.monitoring;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.evision.collection.domain.RunType;

@Repository
public class MonitoringRepository {

    private final JdbcTemplate jdbc;

    public MonitoringRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<RunType, LocalDateTime> lastSuccess() {
        Map<RunType, LocalDateTime> map = new EnumMap<>(RunType.class);
        jdbc.query("""
                SELECT run_type, max(coalesce(ended_at, started_at))
                FROM collection_run WHERE run_status IN ('SUCCESS', 'PARTIAL')
                GROUP BY run_type
                """, rs -> {
            map.put(RunType.valueOf(rs.getString(1)), rs.getTimestamp(2).toLocalDateTime());
        });
        return map;
    }

    public record StatusRow(String runStatus, int runs, long fetched, long inserted, long duplicate, long unknown,
            long invalid) {
    }

    public List<StatusRow> runsByStatus(LocalDateTime from, LocalDateTime to) {
        return jdbc.query("""
                SELECT run_status, count(*), coalesce(sum(fetched_count), 0), coalesce(sum(inserted_count), 0),
                       coalesce(sum(duplicate_count), 0), coalesce(sum(unknown_count), 0), coalesce(sum(invalid_count), 0)
                FROM collection_run
                WHERE started_at >= ? AND started_at < ?
                GROUP BY run_status
                """, (rs, i) -> new StatusRow(rs.getString(1), rs.getInt(2), rs.getLong(3), rs.getLong(4),
                rs.getLong(5), rs.getLong(6), rs.getLong(7)),
                Timestamp.valueOf(from), Timestamp.valueOf(to));
    }

    public List<LocalDateTime> successfulStatusRunTimes(LocalDateTime from, LocalDateTime to) {
        return jdbc.queryForList("""
                SELECT started_at FROM collection_run
                WHERE run_type = 'STATUS' AND run_status IN ('SUCCESS', 'PARTIAL')
                  AND started_at >= ? AND started_at < ?
                ORDER BY started_at
                """, Timestamp.class, Timestamp.valueOf(from), Timestamp.valueOf(to))
                .stream().map(Timestamp::toLocalDateTime).toList();
    }
}
