package com.evision.station.query;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.evision.station.domain.AccessType;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 조회 API (명세 6.2~6.4). 오류 응답은 GlobalExceptionHandler의 공통 형식을 따른다.
 */
@RestController
@RequestMapping("/api")
@Tag(name = "조회", description = "노선 목록, 충전소 검색·상세, 충전기 상태 이력")
public class StationQueryController {

    private final StationQueryService service;
    private final StationPatternService patternService;

    public StationQueryController(StationQueryService service, StationPatternService patternService) {
        this.service = service;
        this.patternService = patternService;
    }

    /** FR-06 노선 목록 */
    @GetMapping("/routes")
    @Operation(summary = "노선 목록",
            description = "충전소가 매핑된 고속도로 노선만 노선번호 순으로 준다. directions는 검색의 direction 값으로 그대로 쓴다. "
                    + "restAreaStations·icStations는 휴게소·IC에 매핑된 충전소 수(같은 충전소가 둘 다면 양쪽에 센다).")
    public RouteListResponse routes() {
        return service.routes();
    }

    /** FR-34 운영기관 목록 (OP-107) */
    @GetMapping("/operators")
    @Operation(summary = "운영기관 목록",
            description = "서비스 대상 충전소의 운영기관을 충전소 수가 많은 순으로 준다. busiId는 검색·상세 응답의 busiId와 같다. "
                    + "선호 운영기관은 화면이 브라우저에 저장하고, 검색에서 다른 기관을 빼지 않고 강조만 한다(FR-35·36).")
    public OperatorListResponse operators() {
        return service.operators();
    }

    /** OP-01 충전소 검색 */
    @GetMapping("/stations")
    @Operation(summary = "충전소 검색",
            description = "지도 영역(4개 값 모두) 또는 routeId 중 하나는 필수. 접근지점(휴게소·IC)이 매핑되고 급속 충전기가 있는 충전소만 나온다. "
                    + "상태 대수는 급속 충전기 기준이며, 0건이면 빈 목록으로 200을 준다.")
    public StationSearchResponse search(
            @RequestParam(required = false) Double minLat,
            @RequestParam(required = false) Double minLng,
            @RequestParam(required = false) Double maxLat,
            @RequestParam(required = false) Double maxLng,
            @RequestParam(required = false) Long routeId,
            @Parameter(description = "휴게소 방향 (원천 값 그대로, 예: 상행). routeId와 함께만 쓸 수 있다. 양방향 휴게소는 어느 방향을 지정해도 나온다. IC는 방향 구분이 없어 같은 노선의 IC는 그대로 나온다(IC를 빼려면 accessType=REST_AREA).")
            @RequestParam(required = false) String direction,
            @RequestParam(required = false) AccessType accessType,
            @Parameter(description = "충전기 타입 코드 (원천 코드, 예: 04). 그 커넥터를 가진 복합형도 함께 나온다 (04 → 04·05·06·10)")
            @RequestParam(required = false) String chargerType,
            @Parameter(description = "기본 100, 최대 500")
            @RequestParam(required = false) Integer limit) {
        return service.search(StationSearchCondition.of(minLat, minLng, maxLat, maxLng, routeId, direction,
                accessType, chargerType, limit));
    }

    /** FR-37 즐겨찾기 충전소 요약 (OP-108) */
    @GetMapping("/stations/summaries")
    @Operation(summary = "즐겨찾기 충전소 요약",
            description = "충전소 ID 최대 20개의 요약과 급속 기준 현재 상태를 한 번에 준다. 요청 순서대로, 중복은 한 번만. "
                    + "운영 종료된 충전소는 status=CLOSED(삭제 안내용), 없는 ID는 NOT_FOUND. 0개이거나 20개를 넘으면 INVALID_QUERY.")
    public StationSummaryListResponse summaries(
            @Parameter(description = "충전소 ID 목록 (쉼표 구분, 예: 12,34,56)")
            @RequestParam List<Long> ids) {
        return service.summaries(ids);
    }

    /** OP-02 충전소 상세 (UC-02·03) */
    @GetMapping("/stations/{stationId}")
    @Operation(summary = "충전소 상세", description = "없거나 삭제된 충전소면 404 NOT_FOUND")
    public StationDetailResponse detail(@PathVariable long stationId) {
        return service.detail(stationId);
    }

    /** M10 시간대별 혼잡 패턴 */
    @GetMapping("/stations/{stationId}/patterns")
    @Operation(summary = "충전소 혼잡 패턴",
            description = "최근 weeks주(기본 4, 1~8)의 30분 집계를 요일·30분 칸별로 묶어 평균 사용률(급속 기준)과 "
                    + "쾌적 비율(사용률이 comfortThreshold 미만이던 구간 비율, 추천·AI 예측과 같은 기준)을 준다. "
                    + "slots는 월 00:00 ~ 일 23:30 336칸이며 표본이 없는 칸은 samples=0, 값은 null. "
                    + "사용률을 알 수 없는 구간(수집 누락, 급속 충전기가 모두 고장·점검·미확인)은 평균에서 빼고 excludedWindows로 센다. "
                    + "없거나 삭제된 충전소면 404 NOT_FOUND, weeks가 범위 밖이면 400 INVALID_QUERY.")
    public StationPatternResponse patterns(
            @PathVariable long stationId,
            @Parameter(description = "최근 몇 주 (기본 4, 1~8)")
            @RequestParam(required = false) Integer weeks) {
        return patternService.patterns(stationId, weeks);
    }

    /** OP-19 충전기 상태 이력 */
    @GetMapping("/chargers/{chargerId}/history")
    @Operation(summary = "충전기 상태 이력",
            description = "[from, to) 구간의 상태 변경을 시각순으로 준다. 기간은 최대 7일. "
                    + "다음 페이지는 응답의 nextCursor를 cursor로 넘긴다 (nextCursor가 null이면 끝).")
    public ChargerHistoryResponse history(
            @PathVariable long chargerId,
            @Parameter(description = "예: 2026-09-28T00:00:00 (KST)")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @Parameter(description = "기본 100, 최대 500")
            @RequestParam(required = false) Integer size,
            @Parameter(description = "이전 응답의 nextCursor")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime cursor) {
        return service.history(chargerId, from, to, size, cursor);
    }
}
