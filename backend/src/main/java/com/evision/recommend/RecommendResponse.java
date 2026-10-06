package com.evision.recommend;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import com.evision.recommend.AvailabilityPredictor.Basis;
import com.evision.station.domain.AccessType;

/**
 * 충전소 추천 결과. recommendations는 점수 내림차순이다.
 *
 * @param reachableKm      남은 주행거리 × 안전비율 (이 거리 안의 후보만 추천)
 * @param candidateCount   진행 방향·노선 조건을 통과한 충전소 수
 * @param unreachableCount 그중 도달 가능 거리를 넘어 제외된 수
 */
public record RecommendResponse(
        LocalDateTime requestedAt,
        double reachableKm,
        int candidateCount,
        int unreachableCount,
        List<Item> recommendations) {

    /**
     * @param distanceKm              현재 위치 → 접근지점 추정 도로거리 (직선거리 × 보정계수)
     * @param availabilityProbability 도착 시 가용 확률
     * @param realtimeProbability     현재 상태 기준 가용 (1 / 0 / 판단 불가 null)
     * @param baselineProbability     실시간 상태를 빼고 본 도착 구간 가용 확률 (AI 예측, 없으면 과거 통계)
     * @param statisticalProbability  과거 같은 시간대 통계 기준 가용 확률 (AI 예측과 비교용)
     * @param basis                   baseline 근거 (AI_FORECAST 또는 SAME_DAY_SLOT … PRIOR)
     * @param modelVersion            AI 예측을 썼으면 모델 버전, 아니면 null
     */
    public record Item(
            int rank,
            long stationId,
            String statId,
            String name,
            double latitude,
            double longitude,
            AccessPoint access,
            double distanceKm,
            double detourKm,
            int etaMinutes,
            LocalDateTime eta,
            double availabilityProbability,
            Double realtimeProbability,
            double baselineProbability,
            double statisticalProbability,
            Basis basis,
            int basisSampleCount,
            String modelVersion,
            int fastChargerCount,
            int availableCount,
            int chargingCount,
            Integer maxOutputKw,
            double score,
            Score scoreBreakdown) {
    }

    /** 추천에 쓴 접근지점. 휴게소면 rest* 필드와 direction, IC면 ic* 필드만 값이 있다. */
    public record AccessPoint(
            AccessType accessType,
            Long restAreaId,
            String restAreaName,
            String direction,
            Long icId,
            String icName,
            Long routeId,
            String routeName,
            BigDecimal detourKm) {
    }

    public record Score(double availability, double detour, double power, double capacity, double progress) {
    }
}
