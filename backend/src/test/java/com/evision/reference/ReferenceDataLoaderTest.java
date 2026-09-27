package com.evision.reference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.evision.TestcontainersConfiguration;
import com.evision.support.TestDb;

/**
 * 실제 기준 데이터 파일(src/main/resources/reference)로 적재를 검증한다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ReferenceDataLoaderTest {

    @Autowired
    ReferenceDataLoader loader;
    @Autowired
    JdbcTemplate jdbc;

    TestDb db;
    long stationId;

    @BeforeEach
    void setUp() {
        db = new TestDb(jdbc);
        db.clear();
        jdbc.execute("TRUNCATE route, rest_area, interchange RESTART IDENTITY CASCADE");
        loader.loadAll();
        stationId = db.station("ME000001");
    }

    @Test
    void 실제_기준_데이터를_모두_적재한다() {
        ReferenceDataLoader.LoadReport report = loader.loadAll();   // 두 번째 실행

        assertThat(report.routes()).isEqualTo(37);
        assertThat(report.restAreas()).isEqualTo(210);
        assertThat(report.restAreasSkipped()).isZero();
        assertThat(report.interchanges()).isEqualTo(659);
        assertThat(report.interchangesSkipped()).isZero();
        // 다시 실행해도 행 수가 같다
        assertThat(db.count("route")).isEqualTo(37);
        assertThat(db.count("rest_area")).isEqualTo(210);
        assertThat(db.count("interchange")).isEqualTo(659);
        // 노선번호가 두 원천에서 같은 노선으로 연결된다 (휴게소 1 = IC 0010)
        assertThat(jdbc.queryForObject("""
                SELECT count(DISTINCT r.route_id) FROM route r
                JOIN rest_area ra ON ra.route_id = r.route_id JOIN interchange i ON i.route_id = r.route_id
                WHERE r.route_no = '1'""", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT facility_type FROM interchange WHERE name = '영천JCT' LIMIT 1", String.class))
                .isEqualTo("JCT");
    }

    @Test
    void 검증된_휴게소_매핑을_적재하고_다시_실행해도_중복이_없다() {
        List<Map<String, String>> rows = List.of(access("ME000001", "REST_AREA", "천안호두(부산)", "0", "2026-09-27"));

        loader.loadStationAccess(rows);
        int[] second = loader.loadStationAccess(rows);

        assertThat(second).containsExactly(1, 0);
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM station_access");
        assertThat(row).containsEntry("access_type", "REST_AREA").containsEntry("mapping_method", "MANUAL");
        assertThat(db.count("station_access")).isEqualTo(1);
    }

    @Test
    void IC_매핑은_시설코드로_가리킨다() {
        loader.loadStationAccess(List.of(access("ME000001", "IC", "0010I00001", "1.25", "2026-09-27")));

        assertThat(jdbc.queryForObject("SELECT detour_km FROM station_access", Double.class)).isEqualTo(1.25);
    }

    @Test
    void 파일에서_빠진_매핑은_지운다() {
        loader.loadStationAccess(List.of(access("ME000001", "REST_AREA", "천안호두(부산)", "0", "2026-09-27")));

        int[] result = loader.loadStationAccess(List.of());

        assertThat(result).containsExactly(0, 1);
        assertThat(db.count("station_access")).isZero();
    }

    @Test
    void 오류가_하나라도_있으면_아무것도_바꾸지_않고_모든_오류를_알려준다() {
        loader.loadStationAccess(List.of(access("ME000001", "REST_AREA", "천안호두(부산)", "0", "2026-09-27")));

        assertThatThrownBy(() -> loader.loadStationAccess(List.of(
                access("ME999999", "REST_AREA", "천안호두(부산)", "0", "2026-09-27"),   // 없는 충전소
                access("ME000001", "REST_AREA", "없는휴게소", "0", "2026-09-27"),       // 없는 휴게소
                access("ME000001", "REST_AREA", "천안호두(부산)", "0", ""))))          // 검증일 없음
                .isInstanceOf(ReferenceDataException.class)
                .hasMessageContaining("3건")
                .hasMessageContaining("ME999999")
                .hasMessageContaining("없는휴게소")
                .hasMessageContaining("verified_at");

        assertThat(db.count("station_access")).isEqualTo(1);   // 기존 매핑은 그대로
    }

    private static Map<String, String> access(String statId, String type, String target, String detour, String verifiedAt) {
        return Map.of("stat_id", statId, "access_type", type, "target_key", target,
                "detour_km", detour, "verified_at", verifiedAt);
    }
}
