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
     * @param historicalProbability   과거 같은 시간대 기준 가용 확률
     * @param basis                   과거 확률의 근거 수준 (SAME_DAY_SLOT … PRIOR)
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
            double historicalProbability,
            Basis basis,
            int basisSampleCount,
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
