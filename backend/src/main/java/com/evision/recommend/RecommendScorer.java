package com.evision.recommend;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 추천 후보 점수 계산·정렬. DB와 무관한 순수 계산이다.
 *
 * <pre>
 * 도달 가능: distanceKm + detourKm ≤ remainingRangeKm × safetyRatio   (아니면 제외)
 *
 * 항목 점수 (모두 0~1, 클수록 좋음):
 *   availability = 도착 시 가용 확률 (AvailabilityPredictor)
 *   detour       = 1 - min(detourKm / maxDetourKm, 1)
 *   power        = min(maxOutputKw / referenceOutputKw, 1)     출력 미상이면 0
 *   capacity     = n / (n + capacityHalfCount)                  급속 대수 n
 *   progress     = (distanceKm + detourKm) / 도달 가능 거리       남은 주행거리를 너무 일찍 끊지 않게
 *
 * score = Σ wᵢ·항목ᵢ / Σ wᵢ   (0~1)
 * </pre>
 * 동점이면 가까운 순, 그다음 stationId 순으로 정렬해 결과가 항상 같다.
 */
public final class RecommendScorer {

    /**
     * @param safetyRatio       남은 주행거리 중 실제로 쓸 비율 (배터리 여유)
     * @param maxDetourKm       이 이상 우회하면 detour 점수 0
     * @param referenceOutputKw 이 출력 이상이면 power 점수 1
     * @param capacityHalfCount 급속 대수가 이만큼일 때 capacity 점수 0.5
     */
    public record Settings(double safetyRatio, double maxDetourKm, double referenceOutputKw,
            double capacityHalfCount, Weights weights) {

        public Settings {
            if (safetyRatio <= 0 || safetyRatio > 1) {
                throw new IllegalArgumentException("safetyRatio는 0 초과 1 이하여야 합니다: " + safetyRatio);
            }
            if (maxDetourKm <= 0 || referenceOutputKw <= 0 || capacityHalfCount <= 0) {
                throw new IllegalArgumentException("maxDetourKm, referenceOutputKw, capacityHalfCount는 양수여야 합니다.");
            }
        }

        /** TODO(확인 필요): 팀 합의 및 백테스트로 조정 */
        public static Settings defaults() {
            return new Settings(0.8, 5.0, 200.0, 2.0, Weights.defaults());
        }
    }

    public record Weights(double availability, double detour, double power, double capacity, double progress) {

        public Weights {
            if (availability < 0 || detour < 0 || power < 0 || capacity < 0 || progress < 0) {
                throw new IllegalArgumentException("가중치는 음수일 수 없습니다.");
            }
            if (availability + detour + power + capacity + progress == 0) {
                throw new IllegalArgumentException("가중치 합이 0입니다.");
            }
        }

        public static Weights defaults() {
            return new Weights(0.55, 0.15, 0.1, 0.1, 0.1);
        }
    }

    /**
     * @param distanceKm           현재 위치에서 접근지점까지 도로 거리
     * @param detourKm             접근지점에서 충전소까지 우회 거리 (station_access.detour_km)
     * @param availability         도착 시 가용 확률
     * @param maxOutputKw          급속충전기 최대 출력 (미상이면 null)
     * @param fastChargerCount     급속충전기 대수
     */
    public record Candidate(long stationId, double distanceKm, double detourKm, double availability,
            Integer maxOutputKw, int fastChargerCount) {
    }

    public record Breakdown(double availability, double detour, double power, double capacity, double progress) {
    }

    public record Scored(Candidate candidate, double score, Breakdown breakdown) {
    }

    public record Result(List<Scored> ranked, List<Candidate> unreachable) {
    }

    private final Settings settings;

    public RecommendScorer(Settings settings) {
        this.settings = settings;
    }

    public double safetyRatio() {
        return settings.safetyRatio();
    }

    /**
     * @param remainingRangeKm 현재 남은 주행 가능 거리
     * @param limit            반환할 최대 후보 수
     */
    public Result rank(List<Candidate> candidates, double remainingRangeKm, int limit) {
        double reachableKm = remainingRangeKm * settings.safetyRatio();
        List<Scored> scored = new ArrayList<>();
        List<Candidate> unreachable = new ArrayList<>();
        for (Candidate c : candidates) {
            double travelKm = c.distanceKm() + c.detourKm();
            if (reachableKm <= 0 || travelKm > reachableKm) {
                unreachable.add(c);
                continue;
            }
            Breakdown b = breakdown(c, travelKm / reachableKm);
            scored.add(new Scored(c, weighted(b), b));
        }
        scored.sort(Comparator.comparingDouble(Scored::score).reversed()
                .thenComparingDouble(s -> s.candidate().distanceKm())
                .thenComparingLong(s -> s.candidate().stationId()));
        List<Scored> top = scored.size() > limit ? scored.subList(0, limit) : scored;
        return new Result(List.copyOf(top), List.copyOf(unreachable));
    }

    private Breakdown breakdown(Candidate c, double progress) {
        double availability = clamp(c.availability());
        double detour = 1 - Math.min(Math.max(c.detourKm(), 0) / settings.maxDetourKm(), 1);
        double power = c.maxOutputKw() == null ? 0 : Math.min(c.maxOutputKw() / settings.referenceOutputKw(), 1);
        int n = Math.max(c.fastChargerCount(), 0);
        double capacity = n / (n + settings.capacityHalfCount());
        return new Breakdown(availability, detour, power, capacity, clamp(progress));
    }

    private double weighted(Breakdown b) {
        Weights w = settings.weights();
        double sum = w.availability() * b.availability() + w.detour() * b.detour() + w.power() * b.power()
                + w.capacity() * b.capacity() + w.progress() * b.progress();
        double total = w.availability() + w.detour() + w.power() + w.capacity() + w.progress();
        return sum / total;
    }

    private static double clamp(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}
