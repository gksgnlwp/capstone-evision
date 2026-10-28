package com.evision.collection.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;

import com.evision.support.EvChargerApiIntegrationTest;

import okhttp3.mockwebserver.RecordedRequest;

/**
 * OP-09 기본정보 갱신 전체 흐름. 급속 기준은 기본 설정(출력 50kW 이상)을 쓴다.
 */
class StationCatalogServiceTest extends EvChargerApiIntegrationTest {

    @Autowired
    StationCatalogService catalogService;
    @Autowired
    StatusCollectionService statusService;

    @Test
    void 충전소와_충전기를_적재하고_상태는_INFO_이력으로_남긴다() throws InterruptedException {
        server.enqueue(page(3,
                info("ME000001", "01", "천안호두휴게소", "100", "2", "N"),
                info("ME000001", "02", "천안호두휴게소", "7", "3", "N")));
        server.enqueue(page(3,
                info("EC000002", "01", "폐쇄된 충전소", "50", "4", "Y")));

        catalogService.refreshStationCatalog();

        assertThat(lastRun()).containsEntry("run_type", "INFO")
                .containsEntry("run_status", "SUCCESS")
                .containsEntry("api_call_count", 2)
                .containsEntry("fetched_count", 3)
                .containsEntry("inserted_count", 3)
                .containsEntry("invalid_count", 0);

        assertThat(db.count("station")).isEqualTo(2);
        assertThat(db.count("charger")).isEqualTo(3);
        assertThat(charger("ME000001", "01")).containsEntry("is_fast", true).containsEntry("output_kw", 100)
                .containsEntry("current_status_code", "2").containsEntry("current_normalized_status", "AVAILABLE");
        assertThat(charger("ME000001", "02")).containsEntry("is_fast", false).containsEntry("current_status_code", "3");
        assertThat(stationDeleted("ME000001")).isEqualTo("N");
        assertThat(stationDeleted("EC000002")).isEqualTo("Y");   // 충전기가 모두 삭제
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM charger_status_history WHERE source_api = 'INFO'", Integer.class)).isEqualTo(3);

        RecordedRequest first = takeRequests(2)[0];
        assertThat(first.getPath()).startsWith("/B552584/EvCharger/getChargerInfo?").contains("pageNo=1", "dataType=JSON");
    }

    @Test
    void 다시_실행해도_중복이_생기지_않고_변경은_반영된다() throws InterruptedException {
        server.enqueue(page(1, info("ME000001", "01", "천안호두휴게소", "50", "2", "N")));
        catalogService.refreshStationCatalog();

        server.enqueue(page(1, info("ME000001", "01", "천안호두휴게소(부산방향)", "200", "2", "N")));
        catalogService.refreshStationCatalog();

        assertThat(db.count("station")).isEqualTo(1);
        assertThat(db.count("charger")).isEqualTo(1);
        assertThat(db.count("charger_status_history")).isEqualTo(1);
        assertThat(lastRun()).containsEntry("inserted_count", 0).containsEntry("duplicate_count", 1);
        assertThat(jdbc.queryForObject("SELECT name FROM station", String.class)).isEqualTo("천안호두휴게소(부산방향)");
        assertThat(charger("ME000001", "01")).containsEntry("output_kw", 200);
        takeRequests(2);
    }

    @Test
    void 형식_오류_행은_건너뛰고_invalid로_센다() throws InterruptedException {
        String badLat = info("ME000001", "01", "천안호두휴게소", "50", "2", "N").replace("\"lat\":\"36.8\"", "\"lat\":\"abc\"");
        server.enqueue(page(2, badLat, info("ME000001", "02", "천안호두휴게소", "50", "2", "N")));

        catalogService.refreshStationCatalog();

        assertThat(lastRun()).containsEntry("run_status", "SUCCESS").containsEntry("invalid_count", 1);
        assertThat(db.count("charger")).isEqualTo(1);
        takeRequests(1);
    }

    @Test
    void 실제_응답도_적재된다() throws IOException, InterruptedException {
        String body = new ClassPathResource("fixtures/info_real_20260927.json").getContentAsString(StandardCharsets.UTF_8)
                .replaceFirst("\"totalCount\":\\d+", "\"totalCount\":10");   // 1페이지로 끝나게
        server.enqueue(json(body));

        catalogService.refreshStationCatalog();

        assertThat(lastRun()).containsEntry("run_status", "SUCCESS")
                .containsEntry("fetched_count", 10)
                .containsEntry("invalid_count", 0);
        assertThat(db.count("charger")).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT name FROM station WHERE stat_id = 'ME174013'", String.class))
                .isEqualTo("낙성대동주민센터");
        takeRequests(1);
    }

    @Test
    void 중간_페이지_적재에_실패하면_FAILED_앞선_페이지와_호출수는_남는다() throws InterruptedException {
        jdbc.execute("ALTER TABLE charger ADD CONSTRAINT test_reject_output CHECK (output_kw < 1000)");
        try {
            server.enqueue(page(3,
                    info("ME000001", "01", "천안호두휴게소", "50", "2", "N"),
                    info("ME000001", "02", "천안호두휴게소", "50", "2", "N")));
            server.enqueue(page(3, info("EC000002", "01", "이상한 충전기", "5000", "2", "N")));

            catalogService.refreshStationCatalog();
        } finally {
            jdbc.execute("ALTER TABLE charger DROP CONSTRAINT test_reject_output");
        }

        assertThat(lastRun()).containsEntry("run_status", "FAILED")
                .containsEntry("error_code", "DB_WRITE_FAILED")
                .containsEntry("api_call_count", 2);
        assertThat(db.count("charger")).isEqualTo(2);   // 1페이지는 커밋됨
        assertThat(jdbc.queryForObject("SELECT count(*) FROM station WHERE stat_id = 'EC000002'", Integer.class)).isZero();
        takeRequests(2);
    }

    @Test
    void 기본정보_갱신_뒤_상태_수집이_새_충전기를_인식한다() throws InterruptedException {
        // 1) 충전기가 없을 때 상태 수집: 미등록으로 건너뛰고, 빈 키 캐시가 만들어진다
        server.enqueue(page(1, StatusCollectionServiceTest.item("ME000001", "01", "3", "20260927115500")));
        statusService.collectChargerStates();
        assertThat(lastRun()).containsEntry("unknown_count", 1);

        // 2) 기본정보 갱신: 충전기가 등록되고 키 캐시가 무효화된다
        server.enqueue(page(1, info("ME000001", "01", "천안호두휴게소", "50", "2", "N")));
        catalogService.refreshStationCatalog();

        // 3) 다시 상태 수집: 새 충전기를 인식해 저장한다
        server.enqueue(page(1, StatusCollectionServiceTest.item("ME000001", "01", "3", "20260927120000")));
        statusService.collectChargerStates();

        assertThat(lastRun()).containsEntry("run_type", "STATUS")
                .containsEntry("inserted_count", 1)
                .containsEntry("unknown_count", 0);
        assertThat(charger("ME000001", "01")).containsEntry("current_status_code", "3");
        takeRequests(3);
    }

    private Map<String, Object> charger(String statId, String chgerId) {
        return jdbc.queryForMap("""
                SELECT c.* FROM charger c JOIN station s ON s.station_id = c.station_id
                WHERE s.stat_id = ? AND c.chger_id = ?""", statId, chgerId);
    }

    private String stationDeleted(String statId) {
        return jdbc.queryForObject("SELECT del_yn FROM station WHERE stat_id = ?", String.class, statId);
    }

    private static String info(String statId, String chgerId, String name, String output, String stat, String delYn) {
        return """
                {"statNm":"%s","statId":"%s","chgerId":"%s","chgerType":"04","addr":"충남 천안시 동남구",
                 "addrDetail":"","location":"","lat":"36.8","lng":"127.2","useTime":"24시간 이용가능",
                 "busiId":"%s","bnm":"기관","busiNm":"운영기관","busiCall":"1661-9408",
                 "stat":"%s","statUpdDt":"20260927100000","lastTsdt":"","lastTedt":"","nowTsdt":"",
                 "powerType":"","output":"%s","method":"단독","zcode":"44","zscode":"44131","kind":"A0","kindDetail":"C001",
                 "parkingFree":"Y","note":"","limitYn":"N","limitDetail":"","delYn":"%s","delDetail":"",
                 "trafficYn":"N","year":"2020","floorNum":"1","floorType":"F","maker":"테스트"}
                """.formatted(name, statId, chgerId, statId.substring(0, 2), stat, output, delYn);
    }
}
