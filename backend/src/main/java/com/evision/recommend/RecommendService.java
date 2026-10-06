package com.evision.recommend;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.evision.recommend.AvailabilityPredictor.CurrentStatus;
import com.evision.recommend.AvailabilityPredictor.Forecast;
import com.evision.recommend.AvailabilityPredictor.Prediction;
import com.evision.recommend.AvailabilityPredictor.WindowSample;
import com.evision.recommend.RecommendRepository.ForecastRow;
import com.evision.recommend.RecommendRepository.OccupancyRow;
import com.evision.recommend.RecommendRepository.OutputRow;
import com.evision.recommend.RecommendScorer.Candidate;
import com.evision.recommend.RecommendScorer.Result;
import com.evision.recommend.RecommendScorer.Scored;
import com.evision.reference.domain.Interchange;
import com.evision.reference.domain.RestArea;
import com.evision.reference.domain.Route;
import com.evision.station.domain.Station;
import com.evision.station.domain.StationAccess;
import com.evision.station.query.StationQueryRepository;
import com.evision.station.query.StationQueryRepository.StatusCountRow;

/**
 * 충전소 추천. 노선 위 후보 → 도착 시 가용 확률 예측 → 점수 정렬.
 *
 * <p>노선 이정 데이터가 없어서 거리는 직선거리 × roadDistanceFactor 로 근사한다.
 * 한 충전소가 여러 접근지점에 매핑돼 있으면 (거리 + 우회) 가 가장 짧은 접근지점 하나로 본다.
 * 도착 구간의 AI 예측(occupancy_forecast)이 있고 forecastMaxAge 안에 만든 것이면 과거 통계 대신 쓴다.
 * 쿼리 수는 후보 건수와 관계없이 5회 (후보, 상태 집계, 최대 출력, 점유율 이력, AI 예측).
 */
@Service
@Transactional(readOnly = true)
public class RecommendService {

    private final RecommendRepository repository;
    private final StationQueryRepository stationQueryRepository;
    private final AvailabilityPredictor predictor;
    private final RecommendScorer scorer;
    private final RecommendProperties properties;
    private final Clock clock;

    public RecommendService(RecommendRepository repository, StationQueryRepository stationQueryRepository,
            AvailabilityPredictor predictor, RecommendScorer scorer, RecommendProperties properties, Clock clock) {
        this.repository = repository;
        this.stationQueryRepository = stationQueryRepository;
        this.predictor = predictor;
        this.scorer = scorer;
        this.properties = properties;
        this.clock = clock;
    }

    public RecommendResponse recommend(RecommendCondition c) {
        LocalDateTime now = LocalDateTime.now(clock);
        double reachableKm = round(c.remainingRangeKm() * scorer.safetyRatio(), 1);

        Map<Long, Located> nearest = nearestAccessPerStation(c,
                repository.findCandidateAccesses(c.routeId(), c.direction(), c.chargerType()));
        if (nearest.isEmpty()) {
            return new RecommendResponse(now, reachableKm, 0, 0, List.of());
        }

        List<Long> ids = List.copyOf(nearest.keySet());
        Map<Long, CurrentStatus> statuses = currentStatuses(stationQueryRepository.countFastChargersByStatus(ids));
        Map<Long, Integer> outputs = new HashMap<>();
        for (OutputRow r : repository.maxFastOutput(ids)) {
            outputs.put(r.stationId(), r.maxOutputKw());
        }
        Map<Long, List<WindowSample>> samples = repository
                .findOccupancy(ids, now.minusWeeks(properties.historyWeeks())).stream()
                .collect(Collectors.groupingBy(OccupancyRow::stationId,
                        Collectors.mapping(r -> new WindowSample(r.windowStart(), r.available()), Collectors.toList())));

        Map<Long, LocalDateTime> etaWindows = new HashMap<>();
        for (Located l : nearest.values()) {
            etaWindows.put(l.station().getId(),
                    AvailabilityPredictor.floorToWindow(now.plusMinutes(etaMinutes(l.travelKm()))));
        }
        Map<String, Forecast> forecasts = forecasts(ids, etaWindows, now);

        Map<Long, Prediction> predictions = new HashMap<>();
        List<Candidate> candidates = new ArrayList<>();
        for (Located l : nearest.values()) {
            long id = l.station().getId();
            CurrentStatus status = statuses.getOrDefault(id, new CurrentStatus(0, 0, 0, 0));
            Prediction p = predictor.predict(now, now.plusMinutes(etaMinutes(l.travelKm())), status,
                    samples.getOrDefault(id, List.of()), forecasts.get(forecastKey(id, etaWindows.get(id))));
            predictions.put(id, p);
            candidates.add(new Candidate(id, l.distanceKm(), l.detourKm(), p.probability(), outputs.get(id),
                    fastCount(status)));
        }

        Result result = scorer.rank(candidates, c.remainingRangeKm(), c.limit());
        List<RecommendResponse.Item> items = new ArrayList<>();
        for (Scored s : result.ranked()) {
            long id = s.candidate().stationId();
            items.add(toItem(items.size() + 1, nearest.get(id), s, predictions.get(id), statuses.get(id), now));
        }
        return new RecommendResponse(now, reachableKm, candidates.size(), result.unreachable().size(), items);
    }

    /** 충전소별 도착 구간의 신선한 AI 예측. 키는 forecastKey(충전소, 구간) */
    private Map<String, Forecast> forecasts(List<Long> ids, Map<Long, LocalDateTime> etaWindows, LocalDateTime now) {
        LocalDateTime from = etaWindows.values().stream().min(Comparator.naturalOrder()).orElseThrow();
        LocalDateTime to = etaWindows.values().stream().max(Comparator.naturalOrder()).orElseThrow();
        Map<String, Forecast> result = new HashMap<>();
        for (ForecastRow r : repository.findForecasts(ids, from, to, now.minus(properties.forecastMaxAge()))) {
            if (r.targetWindowStart().equals(etaWindows.get(r.stationId()))) {
                result.put(forecastKey(r.stationId(), r.targetWindowStart()),
                        new Forecast(r.pAvailable().doubleValue(), r.modelVersion()));
            }
        }
        return result;
    }

    private static String forecastKey(long stationId, LocalDateTime window) {
        return stationId + "@" + window;
    }

    /** 위치가 계산된 접근지점 */
    record Located(Station station, StationAccess access, double distanceKm, double detourKm) {

        double travelKm() {
            return distanceKm + detourKm;
        }
    }

    /** 목적지가 있으면 진행 방향(목적지에 더 가까워지는 쪽) 접근지점만 남기고, 충전소별로 가장 가까운 하나를 고른다. */
    Map<Long, Located> nearestAccessPerStation(RecommendCondition c, List<StationAccess> accesses) {
        Map<Long, Located> nearest = new LinkedHashMap<>();
        Double currentToDestination = c.hasDestination()
                ? GeoDistance.km(c.latitude(), c.longitude(), c.destinationLatitude(), c.destinationLongitude())
                : null;
        for (StationAccess a : accesses) {
            double[] point = coordinates(a);
            if (point == null) {
                continue;
            }
            if (currentToDestination != null && GeoDistance.km(point[0], point[1], c.destinationLatitude(),
                    c.destinationLongitude()) >= currentToDestination) {
                continue;
            }
            double distance = GeoDistance.km(c.latitude(), c.longitude(), point[0], point[1])
                    * properties.roadDistanceFactor();
            double detour = a.getDetourKm() == null ? 0 : a.getDetourKm().doubleValue();
            Located l = new Located(a.getStation(), a, distance, detour);
            nearest.merge(a.getStation().getId(), l,
                    (old, neu) -> Comparator.comparingDouble(Located::travelKm).compare(neu, old) < 0 ? neu : old);
        }
        return nearest;
    }

    private static double[] coordinates(StationAccess a) {
        RestArea r = a.getRestArea();
        if (r != null) {
            return new double[] {r.getLatitude(), r.getLongitude()};
        }
        Interchange ic = a.getInterchange();
        return ic == null ? null : new double[] {ic.getLatitude(), ic.getLongitude()};
    }

    private long etaMinutes(double travelKm) {
        return Math.round(travelKm / properties.averageSpeedKmh() * 60);
    }

    private static Map<Long, CurrentStatus> currentStatuses(List<StatusCountRow> rows) {
        Map<Long, int[]> counts = new HashMap<>();
        for (StatusCountRow r : rows) {
            int[] c = counts.computeIfAbsent(r.stationId(), k -> new int[4]);
            int n = r.count().intValue();
            if (r.status() == null) {
                c[3] += n;
                continue;
            }
            switch (r.status()) {
                case AVAILABLE -> c[0] += n;
                case CHARGING -> c[1] += n;
                case UNAVAILABLE -> c[2] += n;
                case UNKNOWN -> c[3] += n;
            }
        }
        Map<Long, CurrentStatus> result = new HashMap<>();
        counts.forEach((id, c) -> result.put(id, new CurrentStatus(c[0], c[1], c[2], c[3])));
        return result;
    }

    private static int fastCount(CurrentStatus s) {
        return s.available() + s.charging() + s.unavailable() + s.unknown();
    }

    private RecommendResponse.Item toItem(int rank, Located l, Scored s, Prediction p, CurrentStatus status,
            LocalDateTime now) {
        Station st = l.station();
        CurrentStatus cs = status == null ? new CurrentStatus(0, 0, 0, 0) : status;
        long eta = etaMinutes(l.travelKm());
        RecommendScorer.Breakdown b = s.breakdown();
        return new RecommendResponse.Item(rank, st.getId(), st.getStatId(), st.getName(), st.getLatitude(),
                st.getLongitude(), accessPoint(l.access()), round(l.distanceKm(), 1), round(l.detourKm(), 2),
                (int) eta, now.plusMinutes(eta), round(p.probability(), 3), p.realtime(),
                round(p.baseline(), 3), round(p.statistical(), 3), p.basis(), p.basisSampleCount(),
                p.modelVersion(), fastCount(cs), cs.available(),
                cs.charging(), s.candidate().maxOutputKw(), round(s.score(), 3),
                new RecommendResponse.Score(round(b.availability(), 3), round(b.detour(), 3), round(b.power(), 3),
                        round(b.capacity(), 3), round(b.progress(), 3)));
    }

    private static RecommendResponse.AccessPoint accessPoint(StationAccess a) {
        RestArea r = a.getRestArea();
        Interchange ic = a.getInterchange();
        Route route = r != null ? r.getRoute() : ic != null ? ic.getRoute() : null;
        return new RecommendResponse.AccessPoint(a.getAccessType(),
                r == null ? null : r.getId(), r == null ? null : r.getName(), r == null ? null : r.getDirection(),
                ic == null ? null : ic.getId(), ic == null ? null : ic.getName(),
                route == null ? null : route.getId(), route == null ? null : route.getRouteName(),
                a.getDetourKm());
    }

    private static double round(double v, int scale) {
        return BigDecimal.valueOf(v).setScale(scale, RoundingMode.HALF_UP).doubleValue();
    }
}
