package com.evision.collection.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.evision.TestcontainersConfiguration;
import com.evision.external.evcharger.Sleeper;
import com.evision.support.TestDb;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

/**
 * 상태 수집 전체 흐름: MockWebServer(외부 API) + Testcontainers(PostgreSQL).
 * 페이지 크기 2, 페이지당 최대 3회 시도(재시도 2회)로 설정한다.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, StatusCollectionServiceTest.NoSleepConfig.class})
class StatusCollectionServiceTest {

    static final MockWebServer server = new MockWebServer();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("evision.evcharger.base-url", () -> server.url("/B552584/EvCharger").toString());
        registry.add("evision.evcharger.service-key", () -> "test+key/=");
        registry.add("evision.evcharger.page-size", () -> "2");
        registry.add("evision.evcharger.max-retries", () -> "2");
    }

    @TestConfiguration
    static class NoSleepConfig {
        @Bean
        @Primary
        Sleeper noSleep() {
            return duration -> { };
        }
    }

    @AfterAll
    static void shutdown() throws IOException {
        server.shutdown();
    }

    @Autowired
    StatusCollectionService service;
    @Autowired
    ChargerKeyCache chargerKeyCache;
    @Autowired
    JdbcTemplate jdbc;

    TestDb db;
    long chargerA;
    long chargerB;
    int requestsBefore;

    @BeforeEach
    void setUp() {
        db = new TestDb(jdbc);
        db.clear();
        long stationId = db.station("ME000001");
        chargerA = db.charger(stationId, "01");
        chargerB = db.charger(stationId, "02");
        chargerKeyCache.invalidate();
        requestsBefore = server.getRequestCount();
    }

    @Test
    void 모든_페이지를_받아_저장하면_SUCCESS() throws InterruptedException {
        server.enqueue(page(3,
                item("ME000001", "01", "2", "20260927101500"),
                item("ME000001", "02", "3", "20260927101700")));
        server.enqueue(page(3,
                item("EC999999", "01", "2", "20260927101500")));   // 미등록 충전기

        service.collectChargerStates();

        assertThat(lastRun()).containsEntry("run_status", "SUCCESS")
                .containsEntry("api_call_count", 2)
                .containsEntry("total_count", 3)
                .containsEntry("fetched_count", 3)
                .containsEntry("inserted_count", 2)
                .containsEntry("unknown_count", 1)
                .containsEntry("invalid_count", 0);
        assertThat(currentCode(chargerA)).isEqualTo("2");
        assertThat(currentCode(chargerB)).isEqualTo("3");

        RecordedRequest first = takeRequests(2)[0];
        assertThat(first.getPath())
                .startsWith("/B552584/EvCharger/getChargerStatus?")
                .contains("serviceKey=test%2Bkey%2F%3D")   // Decoding 키를 한 번만 인코딩
                .contains("pageNo=1", "numOfRows=2", "period=10", "dataType=JSON");
    }

    @Test
    void 일시_오류는_재시도해서_성공하면_SUCCESS() throws InterruptedException {
        server.enqueue(new MockResponse().setResponseCode(500));
        server.enqueue(page(1, item("ME000001", "01", "2", "20260927101500")));

        service.collectChargerStates();

        assertThat(lastRun()).containsEntry("run_status", "SUCCESS")
                .containsEntry("api_call_count", 2)
                .containsEntry("retry_count", 1)
                .containsEntry("inserted_count", 1);
        takeRequests(2);
    }

    @Test
    void 일부_페이지를_받지_못하면_받은_분만_저장하고_PARTIAL() throws InterruptedException {
        server.enqueue(page(4,
                item("ME000001", "01", "2", "20260927101500"),
                item("ME000001", "02", "3", "20260927101700")));
        for (int i = 0; i < 3; i++) {
            server.enqueue(new MockResponse().setResponseCode(503));
        }

        service.collectChargerStates();

        assertThat(lastRun()).containsEntry("run_status", "PARTIAL")
                .containsEntry("api_call_count", 4)
                .containsEntry("inserted_count", 2)
                .containsEntry("error_code", "EXTERNAL_API_FAILED");
        assertThat(db.count("charger_status_history")).isEqualTo(2);
        takeRequests(4);
    }

    @Test
    void 인증_오류는_재시도하지_않고_FAILED() throws InterruptedException {
        server.enqueue(new MockResponse().setBody("""
                <OpenAPI_ServiceResponse><cmmMsgHeader>
                <returnAuthMsg>SERVICE_KEY_IS_NOT_REGISTERED_ERROR</returnAuthMsg>
                <returnReasonCode>30</returnReasonCode>
                </cmmMsgHeader></OpenAPI_ServiceResponse>"""));

        service.collectChargerStates();

        Map<String, Object> run = lastRun();
        assertThat(run).containsEntry("run_status", "FAILED")
                .containsEntry("api_call_count", 1)
                .containsEntry("error_code", "EXTERNAL_API_FAILED");
        assertThat((String) run.get("error_message")).contains("returnReasonCode=30").doesNotContain("test+key");
        takeRequests(1);
    }

    @Test
    void 저장에_실패하면_롤백하고_FAILED_기록은_남긴다() throws InterruptedException {
        jdbc.execute("ALTER TABLE charger_status_history ADD CONSTRAINT test_reject_charging CHECK (status_code <> '3')");
        try {
            server.enqueue(page(2,
                    item("ME000001", "01", "2", "20260927101500"),
                    item("ME000001", "02", "3", "20260927101700")));

            service.collectChargerStates();
        } finally {
            jdbc.execute("ALTER TABLE charger_status_history DROP CONSTRAINT test_reject_charging");
        }

        assertThat(lastRun()).containsEntry("run_status", "FAILED")
                .containsEntry("error_code", "DB_WRITE_FAILED")
                .containsEntry("fetched_count", 2)
                .containsEntry("inserted_count", 0);
        assertThat(db.count("charger_status_history")).isZero();
        assertThat(currentCode(chargerA)).isNull();
        takeRequests(1);
    }

    @Test
    void 일일_한도에_도달하면_호출하지_않고_FAILED() {
        db.finishedRunToday(950);

        service.collectChargerStates();

        assertThat(lastRun()).containsEntry("run_status", "FAILED")
                .containsEntry("error_code", "BUDGET_EXCEEDED")
                .containsEntry("api_call_count", 0);
        assertThat(server.getRequestCount()).isEqualTo(requestsBefore);
    }

    @Test
    void 남은_한도만큼만_호출하고_중단하면_PARTIAL() throws InterruptedException {
        db.finishedRunToday(949);   // 남은 호출 1회
        server.enqueue(page(4,
                item("ME000001", "01", "2", "20260927101500"),
                item("ME000001", "02", "3", "20260927101700")));

        service.collectChargerStates();

        assertThat(lastRun()).containsEntry("run_status", "PARTIAL")
                .containsEntry("api_call_count", 1)
                .containsEntry("error_code", "BUDGET_EXCEEDED")
                .containsEntry("inserted_count", 2);
        takeRequests(1);
    }

    private Map<String, Object> lastRun() {
        return jdbc.queryForMap("SELECT * FROM collection_run ORDER BY run_id DESC LIMIT 1");
    }

    private String currentCode(long chargerId) {
        return jdbc.queryForObject("SELECT current_status_code FROM charger WHERE charger_id = ?", String.class, chargerId);
    }

    /** 이번 테스트에서 보낸 요청을 모두 꺼낸다. 다음 테스트로 새지 않게 개수도 확인한다. */
    private RecordedRequest[] takeRequests(int expected) throws InterruptedException {
        assertThat(server.getRequestCount() - requestsBefore).isEqualTo(expected);
        RecordedRequest[] requests = new RecordedRequest[expected];
        for (int i = 0; i < expected; i++) {
            requests[i] = server.takeRequest(1, TimeUnit.SECONDS);
        }
        return requests;
    }

    private static MockResponse page(int totalCount, String... items) {
        String body = """
                {"resultCode":"00","resultMsg":"NORMAL SERVICE.","totalCount":%d,"items":{"item":[%s]}}
                """.formatted(totalCount, String.join(",", items));
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
    }

    private static String item(String statId, String chgerId, String stat, String statUpdDt) {
        return """
                {"busiId":"ME","statId":"%s","chgerId":"%s","stat":"%s","statUpdDt":"%s"}
                """.formatted(statId, chgerId, stat, statUpdDt);
    }
}
