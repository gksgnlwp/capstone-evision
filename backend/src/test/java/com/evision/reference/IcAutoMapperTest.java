package com.evision.reference;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.evision.TestcontainersConfiguration;
import com.evision.support.TestDb;

/**
 * IC 인근 충전소 자동 매핑 (FR-46). 위도 0.01도 ≈ 1.11km.
 *
 * <pre>
 * 천안IC (36.800, 127.100)          영천JCT (36.500, 127.500)
 *   A 0.56km  B 1.11km  C 1.67km  D 1.89km  → 가까운 3곳(A·B·C)만
 *   E 3.33km                                  → 2km 밖
 *   F 아파트 / G 이용 제한 / H 24시간 아님 / I 완속만 / J 휴게소 매핑됨 / K 삭제됨  → 제외
 *   L JCT 옆                                  → JCT는 매핑하지 않음
 * </pre>
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class IcAutoMapperTest {

    @Autowired
    IcAutoMapper mapper;
    @Autowired
    JdbcTemplate jdbc;

    long ic;
    long a, b, c, d;

    @BeforeEach
    void setUp() {
        new TestDb(jdbc).clear();
        jdbc.execute("TRUNCATE route, rest_area, interchange RESTART IDENTITY CASCADE");
        long route = id("INSERT INTO route (route_no, route_name) VALUES ('1', '경부선') RETURNING route_id");
        ic = id("""
                INSERT INTO interchange (route_id, facility_code, name, facility_type, latitude, longitude)
                VALUES (?, '0010I00001', '천안IC', 'IC', 36.800, 127.100) RETURNING ic_id""", route);
        id("""
                INSERT INTO interchange (route_id, facility_code, name, facility_type, latitude, longitude)
                VALUES (?, '0010J00001', '영천JCT', 'JCT', 36.500, 127.500) RETURNING ic_id""", route);
        long restArea = id("""
                INSERT INTO rest_area (route_id, name, direction, latitude, longitude, ev_charger_yn)
                VALUES (?, '천안', '상행', 36.8, 127.1, 'Y') RETURNING rest_area_id""", route);

        a = station("A0000001", "주유소 충전소", 36.805, "24시간 이용가능", "N", false, true);
        b = station("B0000001", "공영주차장", 36.810, "24시간 이용가능", "N", false, true);
        c = station("C0000001", "카페 충전소", 36.815, "24시간 이용가능", null, false, true);
        d = station("D0000001", "마트 충전소", 36.817, "24시간 이용가능", "N", false, true);
        station("E0000001", "먼 충전소", 36.830, "24시간 이용가능", "N", false, true);
        station("F0000001", "행복아파트", 36.801, "24시간 이용가능", "N", false, true);
        station("G0000001", "회사 전용", 36.802, "24시간 이용가능", "Y", false, true);
        station("H0000001", "낮에만", 36.803, "09:00~18:00", "N", false, true);
        station("I0000001", "완속만", 36.804, "24시간 이용가능", "N", false, false);
        long j = station("J0000001", "휴게소 충전소", 36.8005, "24시간 이용가능", "N", false, true);
        jdbc.update("""
                INSERT INTO station_access (station_id, access_type, rest_area_id, mapping_method, verified_at)
                VALUES (?, 'REST_AREA', ?, 'MANUAL', now())""", j, restArea);
        station("K0000001", "삭제됨", 36.8006, "24시간 이용가능", "N", true, true);
        stationAt("L0000001", "JCT 옆", 36.501, 127.500);
    }

    @Test
    void 조건에_맞는_충전소를_IC당_가까운_3곳까지_매핑한다() {
        IcAutoMapper.Result result = mapper.rebuild();

        assertThat(result.mapped()).isEqualTo(3);
        assertThat(autoStations()).containsExactly(a, b, c);
        // 우회거리는 직선거리(약 0.56km)의 2배
        assertThat(jdbc.queryForObject(
                "SELECT detour_km FROM station_access WHERE station_id = ? AND access_type = 'IC'", Double.class, a))
                .isBetween(1.05, 1.17);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM station_access WHERE access_type = 'IC' AND verified_at IS NULL""", Integer.class))
                .isEqualTo(3);
    }

    @Test
    void 다시_실행하면_AUTO만_새로_만들어_중복이_없다() {
        mapper.rebuild();
        IcAutoMapper.Result second = mapper.rebuild();

        assertThat(second.removed()).isEqualTo(3);
        assertThat(second.mapped()).isEqualTo(3);
        assertThat(autoStations()).containsExactly(a, b, c);
    }

    @Test
    void 사람이_넣은_IC_매핑은_지우지_않고_건너뛴다() {
        jdbc.update("""
                INSERT INTO station_access (station_id, access_type, ic_id, detour_km, mapping_method, verified_at)
                VALUES (?, 'IC', ?, 1.0, 'MANUAL', now())""", a, ic);

        mapper.rebuild();

        assertThat(jdbc.queryForObject("""
                SELECT mapping_method FROM station_access WHERE station_id = ? AND access_type = 'IC'""",
                String.class, a)).isEqualTo("MANUAL");
        assertThat(autoStations()).containsExactly(b, c);
    }

    private List<Long> autoStations() {
        return jdbc.queryForList("""
                SELECT station_id FROM station_access
                WHERE access_type = 'IC' AND mapping_method = 'AUTO' ORDER BY detour_km""", Long.class);
    }

    private long station(String statId, String name, double lat, String useTime, String limitYn, boolean deleted,
            boolean fast) {
        long id = id("""
                INSERT INTO station (stat_id, name, address, latitude, longitude, zcode, busi_id,
                                     use_time, limit_yn, del_yn, updated_at)
                VALUES (?, ?, '주소', ?, 127.100, '44', 'ME', ?, ?, ?, now()) RETURNING station_id""",
                statId, name, lat, useTime, limitYn, deleted ? "Y" : "N");
        jdbc.update("""
                INSERT INTO charger (station_id, chger_id, charger_type, is_fast) VALUES (?, '01', '04', ?)""",
                id, fast);
        return id;
    }

    private void stationAt(String statId, String name, double lat, double lng) {
        long id = id("""
                INSERT INTO station (stat_id, name, address, latitude, longitude, zcode, busi_id, use_time, updated_at)
                VALUES (?, ?, '주소', ?, ?, '47', 'ME', '24시간 이용가능', now()) RETURNING station_id""",
                statId, name, lat, lng);
        jdbc.update("INSERT INTO charger (station_id, chger_id, charger_type, is_fast) VALUES (?, '01', '04', true)", id);
    }

    private long id(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }
}
