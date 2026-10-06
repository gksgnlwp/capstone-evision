package com.evision.recommend;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 충전소 추천 API. 오류 응답은 GlobalExceptionHandler의 공통 형식을 따른다.
 */
@RestController
@RequestMapping("/api")
@Tag(name = "추천", description = "고속도로 주행 중 충전소 추천")
public class RecommendController {

    private final RecommendService service;

    public RecommendController(RecommendService service) {
        this.service = service;
    }

    @GetMapping("/recommendations")
    @Operation(summary = "충전소 추천",
            description = "노선 위 접근지점(휴게소·IC)에 매핑된 급속 충전소 중 남은 주행거리 × 안전비율 안에 있는 곳을 "
                    + "도착 시 가용 확률·우회 거리·출력·대수로 점수 매겨 높은 순으로 준다. "
                    + "후보가 없으면 빈 목록으로 200을 준다.")
    public RecommendResponse recommend(
            @Parameter(description = "현재 위도") @RequestParam double lat,
            @Parameter(description = "현재 경도") @RequestParam double lng,
            @RequestParam long routeId,
            @Parameter(description = "휴게소 방향 (원천 값 그대로, 예: 상행). 지정하면 그 방향·양방향 휴게소와 IC만 본다.")
            @RequestParam(required = false) String direction,
            @Parameter(description = "남은 주행 가능 거리 (km)")
            @RequestParam double remainingRangeKm,
            @Parameter(description = "목적지 위도. 지정하면 목적지 쪽(진행 방향) 접근지점만 본다.")
            @RequestParam(required = false) Double destLat,
            @RequestParam(required = false) Double destLng,
            @Parameter(description = "충전기 타입 코드 (원천 코드 그대로, 예: 04)")
            @RequestParam(required = false) String chargerType,
            @Parameter(description = "기본 5, 최대 20")
            @RequestParam(required = false) Integer limit) {
        return service.recommend(RecommendCondition.of(lat, lng, routeId, direction, remainingRangeKm, destLat,
                destLng, chargerType, limit));
    }
}
