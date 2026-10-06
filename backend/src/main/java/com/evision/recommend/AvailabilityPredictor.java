package com.evision.recommend;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

/**
 * 도착 시점 충전소 가용 확률 예측. DB와 무관한 순수 계산이다.
 *
 * <pre>
 * P(도착 시 가용) = α·P_now + (1-α)·P_hist,   α = exp(-도착까지 분 / realtimeTauMinutes)
 *
 * P_now  : 현재 급속충전기 상태. AVAILABLE 1기 이상 → 1, 관측 완전(UNKNOWN 0기) + AVAILABLE 0기 → 0, 그 외 NULL
 *          (OccupancyWindowCalculator.PointObservation.availability()와 같은 규칙). NULL이면 α = 0
 * P_hist : occupancy_30m.available 의 계층 베타 평활 (구체적 → 일반 순서로 상위 값을 사전값으로 쓴다)
 *            L4 충전소 전체       p4 = (s4 + k·priorMean) / (n4 + k)
 *            L3 같은 30분 슬롯     p3 = (s3 + k·p4)        / (n3 + k)
 *            L2 + 같은 요일 유형   p2 = (s2 + k·p3)        / (n2 + k)     (평일 / 주말)
 *            L1 + 같은 요일        p1 = (s1 + k·p2)        / (n1 + k)     → P_hist
 *          n = available이 NULL이 아닌 구간 수, s = 그중 true 수, k = priorStrength
 * </pre>
 * 이력이 없으면 P_hist = priorMean 이 되므로 수집 초기(콜드스타트)에도 값이 나온다.
 * 조회 기간(최근 N주)은 호출 쪽이 정해서 samples 로 넘긴다.
 */
public final class AvailabilityPredictor {

    public static final int SLOT_MINUTES = 30;

    /**
     * @param priorMean          이력이 전혀 없을 때 쓰는 가용 확률
     * @param priorStrength      사전값을 관측 몇 건만큼 믿을지 (k). 클수록 이력이 적을 때 사전값에 붙는다
     * @param realtimeTauMinutes 실시간 상태 가중치가 1/e 로 줄어드는 도착까지 시간(분)
     */
    public record Settings(double priorMean, double priorStrength, double realtimeTauMinutes) {

        public Settings {
            if (priorMean < 0 || priorMean > 1) {
                throw new IllegalArgumentException("priorMean은 0~1 사이여야 합니다: " + priorMean);
            }
            if (priorStrength <= 0 || realtimeTauMinutes <= 0) {
                throw new IllegalArgumentException("priorStrength, realtimeTauMinutes는 양수여야 합니다.");
            }
        }

        /** TODO(확인 필요): 수집 데이터가 쌓이면 백테스트로 조정 */
        public static Settings defaults() {
            return new Settings(0.7, 4.0, 30.0);
        }
    }

    /** 현재 급속충전기 상태별 대수 (charger.current_normalized_status 집계) */
    public record CurrentStatus(int available, int charging, int unavailable, int unknown) {

        /** 1.0 / 0.0 / null (판단 불가) */
        public Double probability() {
            if (available >= 1) {
                return 1.0;
            }
            return unknown == 0 && charging + unavailable > 0 ? 0.0 : null;
        }
    }

    /** occupancy_30m 한 행. available이 NULL이면 계산에서 빠진다 */
    public record WindowSample(LocalDateTime windowStart, Boolean available) {
    }

    /** 이력 근거 수준 (설명용). 표본이 있는 가장 구체적인 수준 */
    public enum Basis {
        SAME_DAY_SLOT, SAME_DAY_TYPE_SLOT, SAME_SLOT, STATION_ALL, PRIOR
    }

    /**
     * @param probability      최종 가용 확률 (0~1)
     * @param realtime         P_now (판단 불가면 null)
     * @param historical       P_hist
     * @param realtimeWeight   α
     * @param basis            P_hist 근거 수준
     * @param basisSampleCount 근거 수준의 표본 구간 수
     */
    public record Prediction(double probability, Double realtime, double historical, double realtimeWeight,
            Basis basis, int basisSampleCount) {
    }

    private final Settings settings;

    public AvailabilityPredictor(Settings settings) {
        this.settings = settings;
    }

    /**
     * @param now     현재 시각 (KST)
     * @param eta     도착 예정 시각. now 이전이면 now 로 본다
     * @param current 현재 상태 (없으면 null)
     * @param samples 이 충전소의 과거 30분 구간들
     */
    public Prediction predict(LocalDateTime now, LocalDateTime eta, CurrentStatus current, List<WindowSample> samples) {
        LocalDateTime arrival = eta.isBefore(now) ? now : eta;
        double minutesAhead = Duration.between(now, arrival).toSeconds() / 60.0;

        Historical h = historical(arrival, samples);

        Double realtime = current == null ? null : current.probability();
        double alpha = realtime == null ? 0.0 : Math.exp(-minutesAhead / settings.realtimeTauMinutes());
        double p = realtime == null ? h.probability : alpha * realtime + (1 - alpha) * h.probability;

        return new Prediction(clamp(p), realtime, h.probability, alpha, h.basis, h.basisCount);
    }

    private record Historical(double probability, Basis basis, int basisCount) {
    }

    private Historical historical(LocalDateTime arrival, List<WindowSample> samples) {
        LocalTime slot = floorToSlot(arrival.toLocalTime());
        DayOfWeek day = arrival.getDayOfWeek();
        boolean weekend = isWeekend(day);

        Counter all = new Counter();
        Counter sameSlot = new Counter();
        Counter sameDayType = new Counter();
        Counter sameDay = new Counter();
        for (WindowSample s : samples) {
            if (s.available() == null) {
                continue;
            }
            boolean hit = s.available();
            all.add(hit);
            if (!floorToSlot(s.windowStart().toLocalTime()).equals(slot)) {
                continue;
            }
            sameSlot.add(hit);
            DayOfWeek d = s.windowStart().getDayOfWeek();
            if (isWeekend(d) != weekend) {
                continue;
            }
            sameDayType.add(hit);
            if (d == day) {
                sameDay.add(hit);
            }
        }

        double k = settings.priorStrength();
        double p4 = all.smooth(settings.priorMean(), k);
        double p3 = sameSlot.smooth(p4, k);
        double p2 = sameDayType.smooth(p3, k);
        double p1 = sameDay.smooth(p2, k);

        if (sameDay.n > 0) {
            return new Historical(p1, Basis.SAME_DAY_SLOT, sameDay.n);
        }
        if (sameDayType.n > 0) {
            return new Historical(p1, Basis.SAME_DAY_TYPE_SLOT, sameDayType.n);
        }
        if (sameSlot.n > 0) {
            return new Historical(p1, Basis.SAME_SLOT, sameSlot.n);
        }
        if (all.n > 0) {
            return new Historical(p1, Basis.STATION_ALL, all.n);
        }
        return new Historical(p1, Basis.PRIOR, 0);
    }

    /** TODO(확인 필요): 공휴일·명절은 아직 평일로 본다 (공휴일 데이터 필요) */
    static boolean isWeekend(DayOfWeek d) {
        return d == DayOfWeek.SATURDAY || d == DayOfWeek.SUNDAY;
    }

    static LocalTime floorToSlot(LocalTime t) {
        int minuteOfDay = t.getHour() * 60 + t.getMinute();
        int floored = minuteOfDay - minuteOfDay % SLOT_MINUTES;
        return LocalTime.of(floored / 60, floored % 60);
    }

    private static double clamp(double p) {
        return Math.max(0.0, Math.min(1.0, p));
    }

    private static final class Counter {
        int n;
        int hits;

        void add(boolean hit) {
            n++;
            if (hit) {
                hits++;
            }
        }

        double smooth(double prior, double strength) {
            return (hits + strength * prior) / (n + strength);
        }
    }
}
