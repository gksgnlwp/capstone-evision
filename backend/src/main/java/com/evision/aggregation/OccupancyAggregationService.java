package com.evision.aggregation;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.evision.aggregation.OccupancyWindowCalculator.PointObservation;
import com.evision.aggregation.OccupancyWindowCalculator.WindowResult;
import com.evision.collection.service.SingleRunGuard;

/**
 * SD-08 / OP-13·18 30분 단위 점유율 집계. 서비스 대상(station_access 매핑) 충전소만 집계한다.
 *
 * <p>관측 시점 t의 유효성: t 직전 10분 안(허용 오차로 t 이후 1분까지)에 SUCCESS 또는 PARTIAL STATUS 회차가 있으면 유효하다.
 * 수집 회차는 5분 격자 정각에 시작하지만 수백 ms 앞뒤로 흔들릴 수 있어 허용 오차를 둔다.
 * 같은 구간을 다시 집계해도 결과가 같다 (upsert, 멱등).
 */
@Service
public class OccupancyAggregationService {

    private static final Logger log = LoggerFactory.getLogger(OccupancyAggregationService.class);

    static final Duration VALIDITY_LOOKBACK = Duration.ofMinutes(10);
    static final Duration VALIDITY_TOLERANCE = Duration.ofMinutes(1);
    static final int WINDOW_MINUTES = 30;

    private final SingleRunGuard guard = new SingleRunGuard();

    private final OccupancyAggregationRepository repository;
    private final AggregationProperties properties;
    private final Clock clock;

    public OccupancyAggregationService(OccupancyAggregationRepository repository, AggregationProperties properties,
            Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
        if (properties.interpolate()) {
            log.warn("evision.aggregation.interpolate=true는 아직 지원하지 않아 보간 없이 집계합니다.");
        }
    }

    /** 스케줄러용: 직전에 닫힌 30분 구간을 집계한다 (예: 10:32 실행 → [10:00, 10:30)) */
    public void aggregateLastClosedWindow() {
        LocalDateTime windowStart = floorToWindow(LocalDateTime.now(clock).minusMinutes(2)).minusMinutes(WINDOW_MINUTES);
        boolean ran = guard.runExclusively(() -> aggregateWindow(windowStart));
        if (!ran) {
            log.warn("이전 집계가 아직 실행 중이라 {} 구간 집계를 건너뜁니다.", windowStart);
        }
    }

    /** [from, to) 사이의 모든 닫힌 구간을 다시 집계한다. 매핑을 새로 적재한 뒤 과거 구간을 채울 때 쓴다. */
    public int backfill(LocalDateTime from, LocalDateTime to) {
        LocalDateTime lastClosed = floorToWindow(LocalDateTime.now(clock)).minusMinutes(WINDOW_MINUTES);
        LocalDateTime end = to.isAfter(lastClosed) ? lastClosed : to;
        int windows = 0;
        for (LocalDateTime w = floorToWindow(from); !w.isAfter(end); w = w.plusMinutes(WINDOW_MINUTES)) {
            aggregateWindow(w);
            windows++;
        }
        log.info("점유율 재집계 완료: {}개 구간 ({} ~ {})", windows, floorToWindow(from), end);
        return windows;
    }

    /** OP-13 aggregateOccupancy: 한 구간을 집계해 upsert한다. @return 저장한 충전소 수 */
    @Transactional
    public int aggregateWindow(LocalDateTime windowStart) {
        if (!windowStart.equals(floorToWindow(windowStart))) {
            throw new IllegalArgumentException("구간 시작은 00분 또는 30분 정각이어야 합니다: " + windowStart);
        }
        Map<Long, Integer> targets = repository.targetStations();
        if (targets.isEmpty()) {
            log.debug("집계 대상 충전소가 없습니다 (station_access 매핑 없음). 구간={}", windowStart);
            return 0;
        }

        List<LocalDateTime> points = pointsOf(windowStart);
        List<LocalDateTime> runs = repository.successfulStatusRunTimes(
                points.get(0).minus(VALIDITY_LOOKBACK), points.get(points.size() - 1).plus(VALIDITY_TOLERANCE));
        Map<Long, Map<LocalDateTime, PointObservation>> counts = repository.pointCounts(windowStart);

        List<OccupancyAggregationRepository.Row> rows = new ArrayList<>(targets.size());
        targets.forEach((stationId, fastCount) -> {
            Map<LocalDateTime, PointObservation> byTime = counts.getOrDefault(stationId, Map.of());
            List<PointObservation> observations = new ArrayList<>(points.size());
            for (LocalDateTime t : points) {
                PointObservation c = byTime.getOrDefault(t, new PointObservation(t, true, 0, 0, 0, 0));
                observations.add(new PointObservation(t, isValid(t, runs),
                        c.available(), c.charging(), c.unavailable(), c.unknown()));
            }
            WindowResult r = OccupancyWindowCalculator.calculate(observations, properties.minValidPoints());
            rows.add(new OccupancyAggregationRepository.Row(stationId, windowStart, fastCount,
                    r.occupancyRate(), r.available(), r.fullMinutes(), r.validMinutes(), r.observedPoints()));
        });

        repository.upsert(rows, LocalDateTime.now(clock));
        log.info("점유율 집계 구간={} 충전소={}", windowStart, rows.size());
        return rows.size();
    }

    static boolean isValid(LocalDateTime t, List<LocalDateTime> successfulRuns) {
        LocalDateTime from = t.minus(VALIDITY_LOOKBACK);
        LocalDateTime to = t.plus(VALIDITY_TOLERANCE);
        return successfulRuns.stream().anyMatch(r -> r.isAfter(from) && !r.isAfter(to));
    }

    static List<LocalDateTime> pointsOf(LocalDateTime windowStart) {
        List<LocalDateTime> points = new ArrayList<>(OccupancyWindowCalculator.POINTS_PER_WINDOW);
        for (int i = 0; i < OccupancyWindowCalculator.POINTS_PER_WINDOW; i++) {
            points.add(windowStart.plusMinutes((long) i * OccupancyWindowCalculator.POINT_INTERVAL_MINUTES));
        }
        return points;
    }

    static LocalDateTime floorToWindow(LocalDateTime time) {
        LocalDateTime hour = time.truncatedTo(ChronoUnit.HOURS);
        return time.getMinute() < WINDOW_MINUTES ? hour : hour.plusMinutes(WINDOW_MINUTES);
    }
}
