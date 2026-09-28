package com.evision.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
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
import com.evision.collection.service.ChargerKeyCache;
import com.evision.external.evcharger.Sleeper;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

/**
 * 외부 API(MockWebServer) + PostgreSQL(Testcontainers) 통합 테스트 공통 설정.
 * 하위 클래스가 같은 설정을 쓰므로 스프링 컨텍스트와 DB 컨테이너를 공유한다.
 * 페이지 크기 2, 페이지당 최대 3회 시도(재시도 2회), 재시도 대기 없음.
 *
 * <p>서버는 컨텍스트가 캐시되는 동안 계속 쓰이므로 테스트 클래스가 끝나도 닫지 않는다.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, EvChargerApiIntegrationTest.NoSleepConfig.class})
public abstract class EvChargerApiIntegrationTest {

    protected static final MockWebServer server = new MockWebServer();

    @DynamicPropertySource
    static void evChargerProperties(DynamicPropertyRegistry registry) {
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

    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    protected ChargerKeyCache chargerKeyCache;

    protected TestDb db;
    private int requestsBefore;

    @BeforeEach
    void resetDbAndServer() {
        db = new TestDb(jdbc);
        db.clear();
        chargerKeyCache.invalidate();
        requestsBefore = server.getRequestCount();
    }

    protected int requestsSinceStart() {
        return server.getRequestCount() - requestsBefore;
    }

    /** 이번 테스트에서 보낸 요청을 모두 꺼낸다. 다음 테스트로 새지 않게 개수도 확인한다. */
    protected RecordedRequest[] takeRequests(int expected) throws InterruptedException {
        assertThat(requestsSinceStart()).isEqualTo(expected);
        RecordedRequest[] requests = new RecordedRequest[expected];
        for (int i = 0; i < expected; i++) {
            requests[i] = server.takeRequest(1, TimeUnit.SECONDS);
        }
        return requests;
    }

    protected Map<String, Object> lastRun() {
        return jdbc.queryForMap("SELECT * FROM collection_run ORDER BY run_id DESC LIMIT 1");
    }

    protected String currentCode(long chargerId) {
        return jdbc.queryForObject("SELECT current_status_code FROM charger WHERE charger_id = ?", String.class, chargerId);
    }

    protected static MockResponse page(int totalCount, String... items) {
        String body = """
                {"resultCode":"00","resultMsg":"NORMAL SERVICE.","totalCount":%d,"items":{"item":[%s]}}
                """.formatted(totalCount, String.join(",", items));
        return json(body);
    }

    protected static MockResponse json(String body) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
    }
}
