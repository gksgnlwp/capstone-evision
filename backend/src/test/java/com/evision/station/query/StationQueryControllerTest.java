package com.evision.station.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;

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
import com.jayway.jsonpath.JsonPath;

import jakarta.persistence.EntityManagerFactory;

/**
 * 조회 API (명세 6.2~6.4). 고정 데이터:
 *
 * <pre>
 * 노선 A: 휴게소 RA1(상행), RA2(하행)      노선 B: IC1
 * S1 (36.80, 127.20) → RA1   급속 5대(가능1·충전중1·불가1·미확인2) + 완속 1대 + 삭제된 급속 1대
 * S2 (36.81, 127.21) → RA2, IC1   급속 1대 (타입 07, 충전중)
 * S3 (36.82, 127.22) 매핑 없음 → 검색 제외
 * S4 (36.80, 127.20) → RA1, 삭제됨 → 검색·상세 제외
 * S5 (37.50, 127.00) → IC1, 영역 밖, 충전기 없음 → 급속이 없어 검색·노선·운영기관 제외 (OP-101)
 * 운영기관은 모두 ME(환경부)
 * </pre>
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class StationQueryControllerTest {

    static final LocalDateTime T = LocalDateTime.of(2026, 9, 28, 10, 0);
    static final String AREA_MIN_LAT = "36.7", AREA_MIN_LNG = "127.1", AREA_MAX_LAT = "36.9", AREA_MAX_LNG = "127.3";

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    EntityManagerFactory emf;

    long routeA, routeB, ra1, ra2, ic1;
    long s1, s2, s4, s5;
    long c1;

    @BeforeEach
    void setUp() {
        new TestDb(jdbc).clear();
        jdbc.execute("TRUNCATE route, rest_area, interchange RESTART IDENTITY CASCADE");

        routeA = id("INSERT INTO route (route_no, route_name) VALUES ('10', '경부선') RETURNING route_id");
        routeB = id("INSERT INTO route (route_no, route_name) VALUES ('500', '영동선') RETURNING route_id");
        ra1 = restArea(routeA, "천안", "상행");
        ra2 = restArea(routeA, "천안", "하행");
        ic1 = id("""
                INSERT INTO interchange (route_id, facility_code, name, facility_type, latitude, longitude)
                VALUES (?, '0500I00001', '천안IC', 'IC', 36.81, 127.21) RETURNING ic_id""", routeB);

        s1 = station("ST000001", 36.80, 127.20, false);
        c1 = charger(s1, "01", "04", true, false, "2", "AVAILABLE", T);
        charger(s1, "02", "04", true, false, "3", "CHARGING", T.plusMinutes(5));
        charger(s1, "03", "04", true, false, "5", "UNAVAILABLE", T.minusHours(1));
        charger(s1, "04", "04", true, false, null, null, null);
        charger(s1, "05", "04", true, false, "9", "UNKNOWN", T.minusMinutes(1));
        charger(s1, "06", "02", false, false, "2", "AVAILABLE", T.plusHours(1));
        charger(s1, "07", "04", true, true, "2", "AVAILABLE", T.plusHours(2));
        mapRestArea(s1, ra1);

        s2 = station("ST000002", 36.81, 127.21, false);
        charger(s2, "01", "07", true, false, "3", "CHARGING", T);
        mapRestArea(s2, ra2);
        mapIc(s2, ic1);

        station("ST000003", 36.82, 127.22, false);

        s4 = station("ST000004", 36.80, 127.20, true);
        mapRestArea(s4, ra1);

        s5 = station("ST000005", 37.50, 127.00, false);
        mapIc(s5, ic1);
    }

    // ---- 노선 목록 (FR-06) ----

    @Test
    void 충전소가_매핑된_노선만_노선번호_순으로_방향과_함께_나온다() throws Exception {
        // 노선 A(10): 상행 휴게소 S1(삭제된 S4 제외), 하행 휴게소 S2 / 노선 B(500): IC1에 S2(급속 없는 S5 제외)
        jdbc.update("INSERT INTO route (route_no, route_name) VALUES ('35', '중부선')");   // 매핑 없는 노선은 빠진다
        Statistics stats = statistics();
        mvc.perform(get("/api/routes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.routes[*].routeNo", contains("10", "500")))
                .andExpect(jsonPath("$.routes[0].routeName").value("경부선"))
                .andExpect(jsonPath("$.routes[0].directions", contains("상행", "하행")))
                .andExpect(jsonPath("$.routes[0].restAreaStations").value(2))
                .andExpect(jsonPath("$.routes[0].icStations").value(0))
                .andExpect(jsonPath("$.routes[1].directions.length()").value(0))
                .andExpect(jsonPath("$.routes[1].restAreaStations").value(0))
                .andExpect(jsonPath("$.routes[1].icStations").value(1));
        assertThat(stats.getPrepareStatementCount()).isEqualTo(2);
    }

    // ---- 운영기관 목록 (FR-34) ----

    @Test
    void 운영기관_목록은_서비스_대상_충전소만_충전소_수가_많은_순으로_센다() throws Exception {
        // 서비스 대상: S1·S5(ME), S2(ST). 매핑 없는 S3(CV)과 삭제된 S4는 세지 않는다
        charger(s5, "01", "04", true, false, "2", "AVAILABLE", T);
        operator("ST000002", "ST", "SK일렉링크");
        operator("ST000003", "CV", "채비");
        Statistics stats = statistics();
        mvc.perform(get("/api/operators"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operators[*].busiId", contains("ME", "ST")))
                .andExpect(jsonPath("$.operators[0].name").value("환경부"))
                .andExpect(jsonPath("$.operators[0].stationCount").value(2))
                .andExpect(jsonPath("$.operators[1].name").value("SK일렉링크"))
                .andExpect(jsonPath("$.operators[1].stationCount").value(1));
        assertThat(stats.getPrepareStatementCount()).isEqualTo(1);
    }

    @Test
    void 운영기관별_충전소_수의_합은_서비스_대상_충전소_수와_같다() throws Exception {
        // TC-34. 서비스 대상 = 삭제되지 않고 접근지점 매핑과 급속 충전기가 있는 충전소 (S1, S2. S5는 급속 없음)
        operator("ST000002", "ST", "SK일렉링크");
        long serviceStations = id("""
                SELECT count(*) FROM station s
                WHERE s.del_yn = 'N' AND EXISTS (SELECT 1 FROM station_access sa WHERE sa.station_id = s.station_id)
                  AND EXISTS (SELECT 1 FROM charger c WHERE c.station_id = s.station_id AND c.is_fast AND c.del_yn = 'N')""");
        String body = mvc.perform(get("/api/operators")).andReturn().getResponse().getContentAsString();
        List<Integer> counts = JsonPath.read(body, "$.operators[*].stationCount");
        assertThat(counts.stream().mapToLong(Integer::longValue).sum()).isEqualTo(serviceStations).isEqualTo(2);
    }

    @Test
    void 기관명이_없으면_코드를_이름으로_쓴다() throws Exception {
        jdbc.update("UPDATE station SET org_name = NULL");
        mvc.perform(get("/api/operators"))
                .andExpect(jsonPath("$.operators[0].busiId").value("ME"))
                .andExpect(jsonPath("$.operators[0].name").value("ME"));
    }

    // ---- 6.2 검색 ----

    @Test
    void 지도_영역으로_검색하면_매핑된_충전소만_급속_기준_상태_대수와_함께_나온다() throws Exception {
        Statistics stats = statistics();
        mvc.perform(area(get("/api/stations")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(2))
                .andExpect(jsonPath("$.truncated").value(false))
                .andExpect(jsonPath("$.stations[*].statId", contains("ST000001", "ST000002")))
                .andExpect(jsonPath("$.stations[0].busiId").value("ME"))
                .andExpect(jsonPath("$.stations[0].fastChargerCount").value(5))
                .andExpect(jsonPath("$.stations[0].availableCount").value(1))
                .andExpect(jsonPath("$.stations[0].chargingCount").value(1))
                .andExpect(jsonPath("$.stations[0].unavailableCount").value(1))
                .andExpect(jsonPath("$.stations[0].unknownCount").value(2))
                .andExpect(jsonPath("$.stations[0].latestStatusUpdatedAt").value("2026-09-28T10:05:00"))
                .andExpect(jsonPath("$.stations[0].accesses[0].accessType").value("REST_AREA"))
                .andExpect(jsonPath("$.stations[0].accesses[0].restAreaName").value("천안"))
                .andExpect(jsonPath("$.stations[0].accesses[0].direction").value("상행"))
                .andExpect(jsonPath("$.stations[0].accesses[0].routeName").value("경부선"))
                .andExpect(jsonPath("$.stations[1].accesses.length()").value(2))
                .andExpect(jsonPath("$.stations[1].accesses[1].icName").value("천안IC"));
        // 충전소 조회, 접근지점 조회, 상태 집계 3회. 결과 건수가 늘어도 그대로여야 한다 (N+1 없음)
        assertThat(stats.getPrepareStatementCount()).isEqualTo(3);
    }

    @Test
    void 노선과_방향으로_검색한다() throws Exception {
        mvc.perform(get("/api/stations").param("routeId", String.valueOf(routeA)).param("direction", "상행"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stations[*].statId", contains("ST000001")));

        // 노선 B는 IC1만 있다. S2의 접근지점도 조건에 맞는 IC만 내려준다. S5는 급속이 없어 빠진다
        mvc.perform(get("/api/stations").param("routeId", String.valueOf(routeB)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stations[*].statId", contains("ST000002")))
                .andExpect(jsonPath("$.stations[0].accesses.length()").value(1))
                .andExpect(jsonPath("$.stations[0].accesses[0].accessType").value("IC"));
    }

    @Test
    void 방향을_지정해도_같은_노선의_IC_충전소는_남는다() throws Exception {
        // 노선 A에 IC2를 추가하고, 영역 밖의 S6을 IC2에만 매핑한다
        long ic2 = id("""
                INSERT INTO interchange (route_id, facility_code, name, facility_type, latitude, longitude)
                VALUES (?, '0010I00001', '북천안IC', 'IC', 36.9, 127.1) RETURNING ic_id""", routeA);
        long s6 = station("ST000006", 37.60, 127.10, false);
        charger(s6, "01", "04", true, false, "2", "AVAILABLE", T);
        mapIc(s6, ic2);

        // 노선 A + 상행: 상행 휴게소(S1)와 노선 A의 IC(S6). S2는 하행 휴게소와 노선 B IC라 빠진다
        mvc.perform(get("/api/stations").param("routeId", String.valueOf(routeA)).param("direction", "상행"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stations[*].statId", contains("ST000001", "ST000006")))
                .andExpect(jsonPath("$.stations[1].accesses.length()").value(1))
                .andExpect(jsonPath("$.stations[1].accesses[0].accessType").value("IC"))
                .andExpect(jsonPath("$.stations[1].accesses[0].icName").value("북천안IC"));

        // IC를 빼려면 접근유형을 휴게소로 지정한다
        mvc.perform(get("/api/stations").param("routeId", String.valueOf(routeA)).param("direction", "상행")
                        .param("accessType", "REST_AREA"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stations[*].statId", contains("ST000001")));
    }

    @Test
    void 접근유형과_충전기_타입으로_좁힌다() throws Exception {
        mvc.perform(area(get("/api/stations")).param("accessType", "IC"))
                .andExpect(jsonPath("$.stations[*].statId", contains("ST000002")));

        mvc.perform(area(get("/api/stations")).param("chargerType", "07"))
                .andExpect(jsonPath("$.stations[*].statId", contains("ST000002")));

        // 삭제된 충전기나 다른 충전소의 타입으로는 걸리지 않는다
        mvc.perform(get("/api/stations").param("routeId", String.valueOf(routeB)).param("chargerType", "04"))
                .andExpect(jsonPath("$.count").value(0));
    }

    @Test
    void 급속_충전기가_없는_충전소는_매핑이_있어도_검색에서_빠진다() throws Exception {
        // OP-101: 영역 안 S7은 휴게소·IC에 매핑됐지만 완속과 삭제된 급속만 있다
        long s7 = station("ST000007", 36.83, 127.23, false);
        charger(s7, "01", "02", false, false, "2", "AVAILABLE", T);
        charger(s7, "02", "04", true, true, "2", "AVAILABLE", T);
        mapRestArea(s7, ra1);
        mapIc(s7, ic1);
        mvc.perform(area(get("/api/stations")))
                .andExpect(jsonPath("$.stations[*].statId", contains("ST000001", "ST000002")));
        mvc.perform(get("/api/stations").param("routeId", String.valueOf(routeA)))
                .andExpect(jsonPath("$.stations[*].statId", contains("ST000001", "ST000002")));
        mvc.perform(get("/api/routes"))
                .andExpect(jsonPath("$.routes[0].restAreaStations").value(2))
                .andExpect(jsonPath("$.routes[1].icStations").value(1));
        mvc.perform(get("/api/operators"))
                .andExpect(jsonPath("$.operators[0].stationCount").value(2));
    }

    @Test
    void 운영기관이_달라도_검색에서_빠지지_않고_busiId로_구분된다() throws Exception {
        // FR-36: 선호 운영기관은 화면에서 강조만 한다. 서버 검색은 기관으로 거르지 않는다
        operator("ST000002", "ST", "SK일렉링크");
        mvc.perform(area(get("/api/stations")))
                .andExpect(jsonPath("$.stations[*].statId", contains("ST000001", "ST000002")))
                .andExpect(jsonPath("$.stations[*].busiId", contains("ME", "ST")));
    }

    @Test
    void 결과가_없으면_빈_목록으로_200() throws Exception {
        mvc.perform(get("/api/stations").param("routeId", String.valueOf(routeA)).param("accessType", "IC"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(0))
                .andExpect(jsonPath("$.stations").isEmpty());
    }

    @Test
    void limit을_넘으면_잘렸다고_알려준다() throws Exception {
        mvc.perform(area(get("/api/stations")).param("limit", "1"))
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.limit").value(1))
                .andExpect(jsonPath("$.truncated").value(true))
                .andExpect(jsonPath("$.stations[0].statId").value("ST000001"));
    }

    @Test
    void 검색_조건_오류는_INVALID_QUERY() throws Exception {
        expectInvalid(get("/api/stations"));
        expectInvalid(get("/api/stations").param("minLat", "36.7").param("maxLat", "36.9"));
        expectInvalid(get("/api/stations").param("minLat", "37").param("minLng", "127.1")
                .param("maxLat", "36").param("maxLng", "127.3"));
        expectInvalid(area(get("/api/stations")).param("direction", "상행"));
        expectInvalid(area(get("/api/stations")).param("limit", "501"));
        expectInvalid(area(get("/api/stations")).param("limit", "0"));
        expectInvalid(area(get("/api/stations")).param("accessType", "HIGHWAY"));
    }

    // ---- 6.3 상세 ----

    @Test
    void 상세는_기본정보_접근지점_충전기_목록을_쿼리_3회로_준다() throws Exception {
        Statistics stats = statistics();
        mvc.perform(get("/api/stations/{id}", s1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statId").value("ST000001"))
                .andExpect(jsonPath("$.address").value("테스트 주소"))
                .andExpect(jsonPath("$.busiId").value("ME"))
                .andExpect(jsonPath("$.orgName").value("환경부"))
                .andExpect(jsonPath("$.accesses.length()").value(1))
                .andExpect(jsonPath("$.accesses[0].restAreaId").value(ra1))
                // 삭제된 충전기(07)는 빠진다
                .andExpect(jsonPath("$.chargers[*].chgerId", contains("01", "02", "03", "04", "05", "06")))
                .andExpect(jsonPath("$.chargers[0].chargerId").value(c1))
                .andExpect(jsonPath("$.chargers[0].normalizedStatus").value("AVAILABLE"))
                .andExpect(jsonPath("$.chargers[0].statusCode").value("2"))
                .andExpect(jsonPath("$.chargers[0].statusUpdatedAt").value("2026-09-28T10:00:00"))
                // 상태 기록이 없으면 UNKNOWN이고 원천 코드는 null
                .andExpect(jsonPath("$.chargers[3].normalizedStatus").value("UNKNOWN"))
                .andExpect(jsonPath("$.chargers[3].statusCode").doesNotExist())
                .andExpect(jsonPath("$.chargers[5].fast").value(false));
        assertThat(stats.getPrepareStatementCount()).isEqualTo(3);
    }

    @Test
    void 없거나_삭제된_충전소는_NOT_FOUND() throws Exception {
        mvc.perform(get("/api/stations/{id}", s4))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mvc.perform(get("/api/stations/{id}", 999_999))
                .andExpect(status().isNotFound());
    }

    // ---- 6.4 이력 ----

    @Test
    void 이력은_시각순_keyset_페이지로_넘긴다() throws Exception {
        TestDb db = new TestDb(jdbc);
        long run = db.statusRun(T, "SUCCESS");
        db.history(c1, "2", T.minusHours(1), run);          // 기간 밖
        db.history(c1, "3", T, run);
        db.history(c1, "2", T.plusMinutes(10), run);
        db.history(c1, "9", T.plusMinutes(20), run);        // 코드표에 없는 값 → UNKNOWN
        db.history(c1, "3", T.plusMinutes(30), run);
        db.history(c1, "2", T.plusMinutes(40), run);
        db.history(c1, "2", T.plusHours(1), run);           // to는 포함하지 않는다

        String url = "/api/chargers/{id}/history";
        mvc.perform(get(url, c1).param("from", "2026-09-28T10:00:00").param("to", "2026-09-28T11:00:00")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].statusUpdatedAt",
                        contains("2026-09-28T10:00:00", "2026-09-28T10:10:00")))
                .andExpect(jsonPath("$.items[0].statusCode").value("3"))
                .andExpect(jsonPath("$.items[0].normalizedStatus").value("CHARGING"))
                .andExpect(jsonPath("$.items[0].sourceApi").value("STATUS"))
                .andExpect(jsonPath("$.nextCursor").value("2026-09-28T10:10:00"));

        mvc.perform(get(url, c1).param("from", "2026-09-28T10:00:00").param("to", "2026-09-28T11:00:00")
                        .param("size", "2").param("cursor", "2026-09-28T10:10:00"))
                .andExpect(jsonPath("$.items[*].statusUpdatedAt",
                        contains("2026-09-28T10:20:00", "2026-09-28T10:30:00")))
                .andExpect(jsonPath("$.items[0].normalizedStatus").value("UNKNOWN"))
                .andExpect(jsonPath("$.nextCursor").value("2026-09-28T10:30:00"));

        mvc.perform(get(url, c1).param("from", "2026-09-28T10:00:00").param("to", "2026-09-28T11:00:00")
                        .param("size", "2").param("cursor", "2026-09-28T10:30:00"))
                .andExpect(jsonPath("$.items[*].statusUpdatedAt", contains("2026-09-28T10:40:00")))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
    }

    @Test
    void 이력_조건_오류와_없는_충전기() throws Exception {
        String url = "/api/chargers/{id}/history";
        expectInvalid(get(url, c1).param("from", "2026-09-28T11:00:00").param("to", "2026-09-28T10:00:00"));
        expectInvalid(get(url, c1).param("from", "2026-09-20T00:00:00").param("to", "2026-09-28T00:00:00"));
        expectInvalid(get(url, c1).param("from", "2026-09-28T10:00:00").param("to", "2026-09-28T11:00:00")
                .param("size", "501"));
        expectInvalid(get(url, c1).param("from", "2026-09-28T10:00:00"));

        // 정확히 7일은 허용
        mvc.perform(get(url, c1).param("from", "2026-09-21T00:00:00").param("to", "2026-09-28T00:00:00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());

        mvc.perform(get(url, 999_999).param("from", "2026-09-28T10:00:00").param("to", "2026-09-28T11:00:00"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void OpenAPI_문서에_조회_API가_나온다() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/operators'].get").exists())
                .andExpect(jsonPath("$.paths['/api/stations'].get").exists())
                .andExpect(jsonPath("$.paths['/api/stations/{stationId}'].get").exists())
                .andExpect(jsonPath("$.paths['/api/chargers/{chargerId}/history'].get").exists());
    }

    // ---- 도우미 ----

    private MockHttpServletRequestBuilder area(MockHttpServletRequestBuilder req) {
        return req.param("minLat", AREA_MIN_LAT).param("minLng", AREA_MIN_LNG)
                .param("maxLat", AREA_MAX_LAT).param("maxLng", AREA_MAX_LNG);
    }

    private void expectInvalid(MockHttpServletRequestBuilder req) throws Exception {
        mvc.perform(req)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_QUERY"));
    }

    private Statistics statistics() {
        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        stats.clear();
        return stats;
    }

    private long id(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private long restArea(long routeId, String name, String direction) {
        return id("""
                INSERT INTO rest_area (route_id, name, direction, latitude, longitude, ev_charger_yn)
                VALUES (?, ?, ?, 36.8, 127.2, 'Y') RETURNING rest_area_id""", routeId, name, direction);
    }

    private long station(String statId, double lat, double lng, boolean deleted) {
        return id("""
                INSERT INTO station (stat_id, name, address, latitude, longitude, zcode, busi_id, org_name, del_yn,
                                     updated_at)
                VALUES (?, '테스트 충전소', '테스트 주소', ?, ?, '44', 'ME', '환경부', ?, now())
                RETURNING station_id""", statId, lat, lng, deleted ? "Y" : "N");
    }

    private long charger(long stationId, String chgerId, String type, boolean fast, boolean deleted,
            String statusCode, String normalized, LocalDateTime updatedAt) {
        return id("""
                INSERT INTO charger (station_id, chger_id, charger_type, is_fast, del_yn,
                                     current_status_code, current_normalized_status, current_status_updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING charger_id""",
                stationId, chgerId, type, fast, deleted ? "Y" : "N", statusCode, normalized, updatedAt);
    }

    private void operator(String statId, String busiId, String orgName) {
        jdbc.update("UPDATE station SET busi_id = ?, org_name = ? WHERE stat_id = ?", busiId, orgName, statId);
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
