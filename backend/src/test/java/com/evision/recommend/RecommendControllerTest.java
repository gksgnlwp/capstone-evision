package com.evision.recommend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.evision.TestcontainersConfiguration;
import com.evision.support.TestDb;

import jakarta.persistence.EntityManagerFactory;

/**
 * 추천 API. 현재 위치 (36.00, 127.00), 노선 A 북쪽으로 주행. 고정 데이터:
 *
 * <pre>
 * 노선 A: 휴게소 RA1(상행, 36.20), RA2(하행, 36.25), RA3(양방향, 36.40), IC1(36.30)    노선 B: IC2
 * S1 → RA1   급속 2대 (가용 1, 충전중 1), 출력 100kW
 * S2 → RA2   급속 1대 (가용)
 * S3 → RA3   급속 1대 (충전중, 타입 07)
 * S4 → IC1   우회 1.5km, 급속 1대 (가용)
 * S5 → RA1   완속만 → 제외
 * S6 → RA1   삭제됨 → 제외
 * S7 → IC2   다른 노선 → 제외
 * </pre>
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class RecommendControllerTest {

    static final LocalDateTime T = LocalDateTime.of(2026, 10, 5, 10, 0);

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    EntityManagerFactory emf;
    @Autowired
    Clock clock;

    long routeA;

    @BeforeEach
    void setUp() {
        new TestDb(jdbc).clear();
        jdbc.execute("TRUNCATE route, rest_area, interchange RESTART IDENTITY CASCADE");

        routeA = id("INSERT INTO route (route_no, route_name) VALUES ('10', '경부선') RETURNING route_id");
        long routeB = id("INSERT INTO route (route_no, route_name) VALUES ('500', '영동선') RETURNING route_id");
        long ra1 = restArea(routeA, "천안", "상행", 36.20);
        long ra2 = restArea(routeA, "천안", "하행", 36.25);
        long ra3 = restArea(routeA, "망향", "양방향", 36.40);
        long ic1 = ic(routeA, "0010I00001", 36.30);
        long ic2 = ic(routeB, "0500I00001", 36.10);

        long s1 = station("ST000001", false);
        charger(s1, "01", "04", true, false, "AVAILABLE", 100);
        charger(s1, "02", "04", true, false, "CHARGING", 50);
        mapRestArea(s1, ra1);

        long s2 = station("ST000002", false);
        charger(s2, "01", "04", true, false, "AVAILABLE", 100);
        mapRestArea(s2, ra2);

        long s3 = station("ST000003", false);
        charger(s3, "01", "07", true, false, "CHARGING", 100);
        mapRestArea(s3, ra3);

        long s4 = station("ST000004", false);
        charger(s4, "01", "04", true, false, "AVAILABLE", 100);
        mapIc(s4, ic1);

        long s5 = station("ST000005", false);
        charger(s5, "01", "02", false, false, "AVAILABLE", 7);
        mapRestArea(s5, ra1);

        long s6 = station("ST000006", true);
        charger(s6, "01", "04", true, false, "AVAILABLE", 100);
        mapRestArea(s6, ra1);

        long s7 = station("ST000007", false);
        charger(s7, "01", "04", true, false, "AVAILABLE", 100);
        mapIc(s7, ic2);
    }

    @Test
    void 노선_위_급속_충전소를_점수순으로_추천한다() throws Exception {
        Statistics stats = statistics();
        mvc.perform(base())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reachableKm").value(160.0))
                .andExpect(jsonPath("$.candidateCount").value(4))
                .andExpect(jsonPath("$.unreachableCount").value(0))
                .andExpect(jsonPath("$.recommendations.length()").value(4))
                .andExpect(jsonPath("$.recommendations[0].rank").value(1))
                .andExpect(jsonPath("$.recommendations[?(@.statId == 'ST000001')].fastChargerCount").value(2))
                .andExpect(jsonPath("$.recommendations[?(@.statId == 'ST000001')].maxOutputKw").value(100))
                .andExpect(jsonPath("$.recommendations[?(@.statId == 'ST000004')].access.accessType").value("IC"))
                .andExpect(jsonPath("$.recommendations[?(@.statId == 'ST000004')].detourKm").value(1.5));
        // 후보, 상태 집계, 최대 출력, 점유율 이력, AI 예측 5회 (N+1 없음)
        assertThat(stats.getPrepareStatementCount()).isEqualTo(5);
    }

    @Test
    void 방향을_주면_그_방향과_양방향_휴게소_그리고_IC만_본다() throws Exception {
        mvc.perform(base("direction", "상행"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidateCount").value(3));   // RA2(하행) 제외
    }

    @Test
    void 충전기_타입으로_좁힌다() throws Exception {
        mvc.perform(base("chargerType", "07"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recommendations[*].statId", contains("ST000003")));
    }

    @Test
    void 도달_거리_밖은_제외한다() throws Exception {
        // 도달 가능 40km: RA1(36.20)만 28.9km. RA2 36.1km, IC1 43.4+1.5km, RA3 57.8km
        mvc.perform(base("remainingRangeKm", "50", "direction", "상행"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recommendations[*].statId", contains("ST000001")))
                .andExpect(jsonPath("$.unreachableCount").value(2));
    }

    @Test
    void 후보가_없으면_빈_목록으로_200() throws Exception {
        mvc.perform(base("routeId", "9999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recommendations.length()").value(0));
    }

    @Test
    void 도착_구간의_신선한_AI_예측을_쓰고_오래된_예측은_무시한다() throws Exception {
        // RA1 28.9km → 약 22분 뒤 도착. 지금 시각 기준으로 도착 구간을 계산해 넣는다
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime window = AvailabilityPredictor.floorToWindow(now.plusMinutes(22));
        forecast("ST000001", window, "0.1234", "lgbm-v1", now.minusMinutes(10));
        forecast("ST000004", AvailabilityPredictor.floorToWindow(now.plusMinutes(34)), "0.5000", "lgbm-v0",
                now.minusHours(5));   // 오래된 예측

        mvc.perform(base("direction", "상행"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recommendations[?(@.statId == 'ST000001')].basis").value("AI_FORECAST"))
                .andExpect(jsonPath("$.recommendations[?(@.statId == 'ST000001')].modelVersion").value("lgbm-v1"))
                .andExpect(jsonPath("$.recommendations[?(@.statId == 'ST000001')].baselineProbability").value(0.123))
                .andExpect(jsonPath("$.recommendations[?(@.statId == 'ST000004')].basis").value("PRIOR"));
    }

    @Test
    void 조건_오류는_INVALID_QUERY() throws Exception {
        mvc.perform(base("remainingRangeKm", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_QUERY"));
        mvc.perform(get("/api/recommendations").param("lat", "36").param("lng", "127").param("routeId", "1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_QUERY"));
    }

    /** 기본 조건 (36.0, 127.0, 노선 A, 남은 200km). overrides는 이름, 값 순서로 기본값을 바꾸거나 더한다. */
    private MockHttpServletRequestBuilder base(String... overrides) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("lat", "36.0");
        params.put("lng", "127.0");
        params.put("routeId", String.valueOf(routeA));
        params.put("remainingRangeKm", "200");
        for (int i = 0; i < overrides.length; i += 2) {
            params.put(overrides[i], overrides[i + 1]);
        }
        MockHttpServletRequestBuilder request = get("/api/recommendations");
        params.forEach(request::param);
        return request;
    }

    private Statistics statistics() {
        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        stats.clear();
        return stats;
    }

    private long id(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private long restArea(long routeId, String name, String direction, double lat) {
        return id("""
                INSERT INTO rest_area (route_id, name, direction, latitude, longitude, ev_charger_yn)
                VALUES (?, ?, ?, ?, 127.0, 'Y') RETURNING rest_area_id""", routeId, name, direction, lat);
    }

    private long ic(long routeId, String code, double lat) {
        return id("""
                INSERT INTO interchange (route_id, facility_code, name, facility_type, latitude, longitude)
                VALUES (?, ?, '테스트IC', 'IC', ?, 127.0) RETURNING ic_id""", routeId, code, lat);
    }

    private long station(String statId, boolean deleted) {
        return id("""
                INSERT INTO station (stat_id, name, address, latitude, longitude, zcode, busi_id, del_yn, updated_at)
                VALUES (?, '테스트 충전소', '테스트 주소', 36.0, 127.0, '44', 'ME', ?, now())
                RETURNING station_id""", statId, deleted ? "Y" : "N");
    }

    private void charger(long stationId, String chgerId, String type, boolean fast, boolean deleted,
            String normalized, int outputKw) {
        jdbc.update("""
                INSERT INTO charger (station_id, chger_id, charger_type, output_kw, is_fast, del_yn,
                                     current_normalized_status, current_status_updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
                stationId, chgerId, type, outputKw, fast, deleted ? "Y" : "N", normalized, T);
    }

    private void forecast(String statId, LocalDateTime window, String p, String modelVersion,
            LocalDateTime generatedAt) {
        jdbc.update("""
                INSERT INTO occupancy_forecast (station_id, target_window_start, p_available, model_version, generated_at)
                SELECT station_id, ?, ?::numeric, ?, ? FROM station WHERE stat_id = ?""",
                window, p, modelVersion, generatedAt, statId);
    }

    private void mapRestArea(long stationId, long restAreaId) {
        jdbc.update("""
                INSERT INTO station_access (station_id, access_type, rest_area_id, mapping_method, verified_at)
                VALUES (?, 'REST_AREA', ?, 'MANUAL', now())""", stationId, restAreaId);
    }

    private void mapIc(long stationId, long icId) {
        jdbc.update("""
                INSERT INTO station_access (station_id, access_type, ic_id, detour_km, mapping_method, verified_at)
                VALUES (?, 'IC', ?, 1.5, 'MANUAL', now())""", stationId, icId);
    }
}
