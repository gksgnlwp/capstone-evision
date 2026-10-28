package com.evision.support;

import java.time.LocalDateTime;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 통합 테스트용 데이터 준비 도우미. 테스트는 트랜잭션 밖에서 실제 커밋을 하므로 매번 비운다.
 */
public class TestDb {

    private final JdbcTemplate jdbc;

    public TestDb(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void clear() {
        jdbc.execute("""
                TRUNCATE charger_status_history, occupancy_30m, station_access, charger, station, collection_run
                RESTART IDENTITY CASCADE
                """);
    }

    public long station(String statId) {
        return jdbc.queryForObject("""
                INSERT INTO station (stat_id, name, address, latitude, longitude, zcode, busi_id, updated_at)
                VALUES (?, '테스트 충전소', '테스트 주소', 36.8, 127.2, '44', 'ME', now())
                RETURNING station_id""", Long.class, statId);
    }

    public long charger(long stationId, String chgerId) {
        return jdbc.queryForObject("""
                INSERT INTO charger (station_id, chger_id, charger_type, is_fast)
                VALUES (?, ?, '04', true)
                RETURNING charger_id""", Long.class, stationId, chgerId);
    }

    public long runningRun() {
        return jdbc.queryForObject("""
                INSERT INTO collection_run (run_type, started_at, run_status)
                VALUES ('STATUS', now(), 'RUNNING')
                RETURNING run_id""", Long.class);
    }

    public void finishedRunToday(int apiCallCount) {
        jdbc.update("""
                INSERT INTO collection_run (run_type, started_at, ended_at, run_status, api_call_count)
                VALUES ('STATUS', ?, ?, 'SUCCESS', ?)""",
                LocalDateTime.now().withHour(0).withMinute(1), LocalDateTime.now().withHour(0).withMinute(2), apiCallCount);
    }

    public long charger(long stationId, String chgerId, boolean fast) {
        return jdbc.queryForObject("""
                INSERT INTO charger (station_id, chger_id, charger_type, is_fast)
                VALUES (?, ?, '04', ?)
                RETURNING charger_id""", Long.class, stationId, chgerId, fast);
    }

    /** 테스트용 노선·휴게소를 만들고 충전소를 휴게소에 매핑한다 (서비스 대상으로 만든다). */
    public void mapToRestArea(long stationId) {
        long routeId = jdbc.queryForObject("""
                INSERT INTO route (route_no, route_name) VALUES ('9999', '테스트선')
                ON CONFLICT (route_no) DO UPDATE SET route_name = EXCLUDED.route_name
                RETURNING route_id""", Long.class);
        long restAreaId = jdbc.queryForObject("""
                INSERT INTO rest_area (route_id, name, direction, latitude, longitude, ev_charger_yn)
                VALUES (?, '테스트휴게소', '상행', 36.8, 127.2, 'Y')
                ON CONFLICT (route_id, name, direction) DO UPDATE SET ev_charger_yn = 'Y'
                RETURNING rest_area_id""", Long.class, routeId);
        jdbc.update("""
                INSERT INTO station_access (station_id, access_type, rest_area_id, mapping_method, verified_at)
                VALUES (?, 'REST_AREA', ?, 'MANUAL', now())""", stationId, restAreaId);
    }

    public long statusRun(LocalDateTime startedAt, String runStatus) {
        return jdbc.queryForObject("""
                INSERT INTO collection_run (run_type, started_at, ended_at, run_status)
                VALUES ('STATUS', ?, ?, ?)
                RETURNING run_id""", Long.class, startedAt, startedAt.plusSeconds(3), runStatus);
    }

    public void history(long chargerId, String statusCode, LocalDateTime statusUpdatedAt, long runId) {
        jdbc.update("""
                INSERT INTO charger_status_history (charger_id, status_code, status_updated_at, source_api, run_id, collected_at)
                VALUES (?, ?, ?, 'STATUS', ?, ?)""", chargerId, statusCode, statusUpdatedAt, runId, statusUpdatedAt);
    }

    public int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }
}
