package com.evision.external.evcharger;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import com.evision.external.evcharger.dto.EvChargerPage;
import com.evision.external.evcharger.dto.StatusItem;

/**
 * 한국환경공단 EvCharger API 클라이언트. 페이지 순회와 재시도를 담당한다.
 *
 * <p>인증키(Decoding 키)는 직접 URL 인코딩해서 {@link URI}로 넘긴다.
 * RestClient에 URI 객체를 주면 다시 인코딩하지 않으므로 이중 인코딩이 생기지 않는다.
 * 요청 URL은 인증키가 들어 있으므로 로그에 남기지 않는다.
 */
@Component
public class EvChargerClient {

    private static final Logger log = LoggerFactory.getLogger(EvChargerClient.class);

    public static final String BUDGET_EXCEEDED = "BUDGET_EXCEEDED";
    public static final String CONFIG_MISSING = "CONFIG_MISSING";

    private final RestClient restClient;
    private final EvChargerProperties properties;
    private final EvChargerResponseParser parser;
    private final Sleeper sleeper;
    private final Clock clock;

    public EvChargerClient(RestClient.Builder restClientBuilder, EvChargerProperties properties,
            EvChargerResponseParser parser, Sleeper sleeper, Clock clock) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());

        this.restClient = restClientBuilder.requestFactory(requestFactory).build();
        this.properties = properties;
        this.parser = parser;
        this.sleeper = sleeper;
        this.clock = clock;
    }

    /**
     * OP-10 전국 충전기 상태를 모든 페이지에 걸쳐 조회한다.
     *
     * <ul>
     *   <li>페이지는 {@code 수신 건수 >= totalCount}가 될 때까지 순회한다.</li>
     *   <li>페이지마다 최대 maxRetries번 지수 백오프로 재시도한다. 전체 소요는 statusTimeBudget을 넘기지 않는다.</li>
     *   <li>호출 수가 callAllowance에 도달하면 중단한다 (일일 한도 가드).</li>
     * </ul>
     *
     * @param callAllowance 이번 회차에 쓸 수 있는 최대 호출 수
     */
    public StatusFetchResult fetchAllStatus(int callAllowance) {
        if (!properties.hasServiceKey()) {
            return new StatusFetchResult(List.of(), null, 0, 0, 0, false,
                    CONFIG_MISSING, "DATA_GO_KR_SERVICE_KEY가 설정되지 않았습니다.");
        }

        Instant deadline = clock.instant().plus(properties.statusTimeBudget());
        List<StatusItem> items = new ArrayList<>();
        Integer totalCount = null;
        int calls = 0;
        int retries = 0;
        int pages = 0;
        int pageNo = 1;

        while (true) {
            EvChargerPage<StatusItem> page = null;
            EvChargerApiException lastError = null;

            for (int attempt = 0; attempt <= properties.maxRetries(); attempt++) {
                if (attempt > 0) {
                    Duration backoff = properties.retryInitialBackoff().multipliedBy(1L << (attempt - 1));
                    if (clock.instant().plus(backoff).isAfter(deadline)) {
                        lastError = timeBudgetExceeded(lastError);
                        break;
                    }
                    if (!sleep(backoff)) {
                        lastError = new EvChargerApiException(EvChargerApiException.EXTERNAL_API_FAILED, "재시도 대기 중 인터럽트", false);
                        break;
                    }
                    retries++;
                }
                if (calls >= callAllowance) {
                    return new StatusFetchResult(items, totalCount, calls, retries, pages, false,
                            BUDGET_EXCEEDED, "일일 호출 한도에 도달해 " + pageNo + "페이지부터 받지 못했습니다.");
                }

                calls++;
                try {
                    page = fetchStatusPage(pageNo);
                    break;
                } catch (EvChargerApiException e) {
                    lastError = e;
                    log.warn("getChargerStatus {}페이지 실패 (시도 {}/{}): {}", pageNo, attempt + 1,
                            properties.maxRetries() + 1, e.getMessage());
                    if (!e.retryable()) {
                        break;
                    }
                }
            }

            if (page == null) {
                return new StatusFetchResult(items, totalCount, calls, retries, pages, false,
                        lastError.errorCode(), pageNo + "페이지 수신 실패: " + lastError.getMessage());
            }

            pages++;
            totalCount = page.totalCount();
            items.addAll(page.items());
            if (page.items().isEmpty() || items.size() >= totalCount) {
                return new StatusFetchResult(items, totalCount, calls, retries, pages, true, null, null);
            }
            pageNo++;
        }
    }

    private EvChargerPage<StatusItem> fetchStatusPage(int pageNo) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("pageNo", String.valueOf(pageNo));
        params.put("numOfRows", String.valueOf(properties.pageSize()));
        params.put("period", String.valueOf(properties.statusPeriodMinutes()));
        params.put("dataType", "JSON");
        return parser.parse(get("getChargerStatus", params), StatusItem.class);
    }

    private String get(String operation, Map<String, String> params) {
        URI uri = buildUri(operation, params);
        try {
            return restClient.get().uri(uri).retrieve().body(String.class);
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            boolean retryable = e.getStatusCode().is5xxServerError() || status == HttpStatus.TOO_MANY_REQUESTS.value();
            throw new EvChargerApiException(EvChargerApiException.EXTERNAL_API_FAILED, "HTTP " + status, retryable);
        } catch (ResourceAccessException e) {
            Throwable cause = e.getMostSpecificCause();
            throw new EvChargerApiException(EvChargerApiException.EXTERNAL_API_FAILED,
                    "I/O 오류: " + cause.getClass().getSimpleName() + " " + cause.getMessage(), true);
        }
    }

    URI buildUri(String operation, Map<String, String> params) {
        StringBuilder sb = new StringBuilder(properties.baseUrl())
                .append('/').append(operation)
                .append("?serviceKey=").append(encode(properties.serviceKey()));
        params.forEach((key, value) -> sb.append('&').append(key).append('=').append(encode(value)));
        return URI.create(sb.toString());
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private boolean sleep(Duration duration) {
        try {
            sleeper.sleep(duration);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static EvChargerApiException timeBudgetExceeded(EvChargerApiException lastError) {
        String cause = lastError == null ? "" : " (마지막 오류: " + lastError.getMessage() + ")";
        String code = lastError == null ? EvChargerApiException.EXTERNAL_API_FAILED : lastError.errorCode();
        return new EvChargerApiException(code, "수집 시간 상한을 넘겨 재시도를 중단했습니다" + cause, false);
    }
}
