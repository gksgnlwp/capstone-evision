package com.evision.recommend;

import java.time.LocalDateTime;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 충전소 도착 시점 예측 API. 오류 응답은 GlobalExceptionHandler의 공통 형식을 따른다.
 */
@RestController
@RequestMapping("/api")
@Tag(name = "추천", description = "고속도로 주행 중 충전소 추천")
public class StationPredictionController {

    private final StationPredictionService service;

    public StationPredictionController(StationPredictionService service) {
        this.service = service;
    }

    @GetMapping("/stations/{stationId}/prediction")
    @Operation(summary = "충전소 도착 시점 예측",
            description = "도착 시각에 급속충전기를 쾌적하게(사용률 50% 미만) 쓸 확률. 추천과 같은 계산이라 같은 도착 시각이면 추천 화면과 같은 값이다. "
                    + "도착 구간의 AI 예측이 신선하면(forecastMaxAgeMinutes 안) basis=AI_FORECAST 이고 forecast 에 모델 버전·산출 시각이 온다. "
                    + "없으면 과거 통계(basis=SAME_DAY_SLOT 등, 이력이 없으면 PRIOR)로 대신한다. "
                    + "timeline 은 도착 시각부터 30분 간격 5개. 없거나 삭제된 충전소는 404, 시각이 범위 밖이면 400.")
    public StationPredictionResponse predict(
            @PathVariable long stationId,
            @Parameter(description = "도착 예정 시각 (KST, 예: 2026-11-28T15:20:00). 지금부터 720분 안. minutesLater 와 함께 줄 수 없다.")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime arrivalAt,
            @Parameter(description = "지금부터 몇 분 뒤 도착 (0~720). arrivalAt·minutesLater 둘 다 없으면 30")
            @RequestParam(required = false) Integer minutesLater) {
        return service.predict(stationId, arrivalAt, minutesLater);
    }
}
