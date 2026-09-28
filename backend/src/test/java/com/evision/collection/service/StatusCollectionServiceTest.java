package com.evision.collection.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.evision.support.EvChargerApiIntegrationTest;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;

/**
 * OP-10 상태 수집 전체 흐름.
 */
class StatusCollectionServiceTest extends EvChargerApiIntegrationTest {

    @Autowired
    StatusCollectionService service;

    long chargerA;
    long chargerB;

    @BeforeEach
    void setUp() {
        long stationId = db.station("ME000001");
        chargerA = db.charger(stationId, "01");
        chargerB = db.charger(stationId, "02");
    }

    @Test
    void 모든_페이지를_받아_저장하면_SUCCESS() throws InterruptedException {
        server.enqueue(page(3,
                item("ME000001", "01", "2", "20260927101500"),
                item("ME000001", "02", "3", "20260927101700")));
        server.enqueue(page(3,
                item("EC999999", "01", "2", "20260927101500")));   // 미등록 충전기

        service.collectChargerStates();

        assertThat(lastRun()).containsEntry("run_type", "STATUS")
                .containsEntry("run_status", "SUCCESS")
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
        assertThat(requestsSinceStart()).isZero();
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

    static String item(String statId, String chgerId, String stat, String statUpdDt) {
        return """
                {"busiId":"ME","statId":"%s","chgerId":"%s","stat":"%s","statUpdDt":"%s"}
                """.formatted(statId, chgerId, stat, statUpdDt);
    }
}
