package com.evision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.evision.support.TestDb;

/**
 * V1·V2 마이그레이션의 초기 데이터와 station_access CHECK 제약을 검증한다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class SchemaConstraintTest {

    @Autowired
    JdbcTemplate jdbc;

    long stationId;
    long restAreaId;
    long icId;

    @BeforeEach
    void setUp() {
        // 다른 테스트가 남긴 데이터와 겹치지 않게 비우고 시작한다
        new TestDb(jdbc).clear();
        jdbc.execute("TRUNCATE route, rest_area, interchange RESTART IDENTITY CASCADE");
        long routeId = jdbc.queryForObject(
                "INSERT INTO route (route_no, route_name) VALUES ('0010', '경부선') RETURNING route_id", Long.class);
        restAreaId = jdbc.queryForObject("""
                INSERT INTO rest_area (route_id, name, direction, latitude, longitude, ev_charger_yn)
                VALUES (?, '천안호두휴게소', '부산', 36.8, 127.2, 'Y') RETURNING rest_area_id""", Long.class, routeId);
        icId = jdbc.queryForObject("""
                INSERT INTO interchange (route_id, facility_code, name, facility_type, latitude, longitude)
                VALUES (?, 'IC001', '천안IC', 'IC', 36.8, 127.1) RETURNING ic_id""", Long.class, routeId);
        stationId = jdbc.queryForObject("""
                INSERT INTO station (stat_id, name, address, latitude, longitude, zcode, busi_id, updated_at)
                VALUES ('ME21A209', '천안호두휴게소(부산방향)', '충남 천안시', 36.8, 127.2, '44', 'ME', now())
                RETURNING station_id""", Long.class);
    }

    @Test
    void 코드_초기데이터가_적재된다() {
        Integer statCount = jdbc.queryForObject(
                "SELECT count(*) FROM code_dictionary WHERE code_group = 'CHARGER_STAT'", Integer.class);
        String unknown = jdbc.queryForObject(
                "SELECT normalized_status FROM code_dictionary WHERE code_group = 'CHARGER_STAT' AND code = '0'",
                String.class);

        assertThat(statCount).isEqualTo(6);
        assertThat(unknown).isEqualTo("UNKNOWN");
    }

    @Test
    void 휴게소_수동매핑은_저장된다() {
        jdbc.update("""
                INSERT INTO station_access (station_id, access_type, rest_area_id, mapping_method)
                VALUES (?, 'REST_AREA', ?, 'MANUAL')""", stationId, restAreaId);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM station_access", Integer.class)).isEqualTo(1);
    }

    @Test
    void 휴게소_자동매핑은_CHECK_제약으로_거부된다() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO station_access (station_id, access_type, rest_area_id, mapping_method)
                VALUES (?, 'REST_AREA', ?, 'AUTO')""", stationId, restAreaId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 접근유형과_대상이_어긋나면_거부된다() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO station_access (station_id, access_type, ic_id, mapping_method)
                VALUES (?, 'REST_AREA', ?, 'MANUAL')""", stationId, icId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
