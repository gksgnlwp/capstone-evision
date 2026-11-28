package com.evision.recommend;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.evision.common.error.ApiException;
import com.evision.recommend.AvailabilityPredictor.Basis;
import com.evision.recommend.AvailabilityPredictor.CurrentStatus;
import com.evision.recommend.AvailabilityPredictor.Forecast;
import com.evision.recommend.AvailabilityPredictor.HistoryStats;
import com.evision.recommend.AvailabilityPredictor.Prediction;
import com.evision.recommend.RecommendRepository.HistoryRow;
import com.evision.recommend.RecommendRepository.StationForecastRow;
import com.evision.station.domain.Station;
import com.evision.station.query.StationQueryRepository;
import com.evision.station.query.StationQueryRepository.StatusCountRow;

/**
 * 충전소 하나의 도착 시점 이용 가능 확률 (FR-07·M09). 추천과 같은 {@link AvailabilityPredictor} 로 계산한다.
 * 도착 구간의 신선한 AI 예측이 있으면 그것을, 없으면 과거 통계를 쓰고, 도착이 가까울수록 현재 상태를 더 반영한다.
 * 도착 시각부터 30분 간격 몇 구간을 같이 계산해 조금 늦게 도착하는 경우도 보여 준다.
 */
@Service
@Transactional(readOnly = true)
public class StationPredictionService {

    static final int DEFAULT_MINUTES_LATER = 30;
    static final int MAX_MINUTES_LATER = 720;
    /** 이보다 더 지난 도착 시각은 잘못된 요청으로 본다 (조금 지난 시각은 지금 도착으로 본다) */
    static final int PAST_TOLERANCE_MINUTES = 30;
    static final int TIMELINE_SIZE = 5;

    private final StationQueryRepository stations;
    private final RecommendRepository repository;
    private final AvailabilityPredictor predictor;
    private final RecommendProperties properties;
    private final Clock clock;

    public StationPredictionService(StationQueryRepository stations, RecommendRepository repository,
            AvailabilityPredictor predictor, RecommendProperties properties, Clock clock) {
        this.stations = stations;
        this.repository = repository;
        this.predictor = predictor;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * @param arrivalAt    도착 시각 (minutesLater 와 함께 줄 수 없다)
     * @param minutesLater 지금부터 몇 분 뒤 도착 (둘 다 없으면 30분)
     */
    public StationPredictionResponse predict(long stationId, LocalDateTime arrivalAt, Integer minutesLater) {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime arrival = arrival(now, arrivalAt, minutesLater);
        Station station = stations.findActiveStation(stationId)
                .orElseThrow(() -> ApiException.notFound("충전소를 찾을 수 없습니다: " + stationId));

        List<StatusCountRow> statusRows = stations.countFastChargersByStatus(List.of(stationId));
        CurrentStatus status = RecommendService.currentStatuses(statusRows)
                .getOrDefault(stationId, new CurrentStatus(0, 0, 0, 0));

        List<LocalDateTime> arrivals = new ArrayList<>(TIMELINE_SIZE);
        for (int i = 0; i < TIMELINE_SIZE; i++) {
            arrivals.add(arrival.plusMinutes((long) i * AvailabilityPredictor.SLOT_MINUTES));
        }
        Map<LocalDateTime, Forecast> forecasts = new HashMap<>();
        Map<LocalDateTime, StationForecastRow> forecastRows = new HashMap<>();
        for (StationForecastRow r : repository.findStationForecasts(stationId,
                AvailabilityPredictor.floorToWindow(arrivals.getFirst()),
                AvailabilityPredictor.floorToWindow(arrivals.getLast()), now.minus(properties.forecastMaxAge()))) {
            forecasts.put(r.targetWindowStart(), new Forecast(r.pAvailable().doubleValue(), r.modelVersion()));
            forecastRows.put(r.targetWindowStart(), r);
        }

        LocalDateTime historyFrom = now.minusWeeks(properties.historyWeeks());
        List<StationPredictionResponse.Point> timeline = new ArrayList<>(TIMELINE_SIZE);
        for (LocalDateTime eta : arrivals) {
            LocalDateTime window = AvailabilityPredictor.floorToWindow(eta);
            HistoryStats stats = HistoryStats.fromAggregates(eta, repository
                    .countHistory(Map.of(stationId, window.toLocalTime()), historyFrom, properties.comfortThreshold())
                    .stream().map(HistoryRow::count).toList());
            Prediction p = predictor.predict(now, eta, status, stats, forecasts.get(window));
            timeline.add(point(now, eta, window, p, p.basis() == Basis.AI_FORECAST ? forecastRows.get(window) : null));
        }

        return new StationPredictionResponse(station.getId(), station.getName(), now,
                properties.comfortThreshold(), timeline.getFirst(), timeline, current(status, statusRows),
                properties.forecastMaxAge().toMinutes());
    }

    static LocalDateTime arrival(LocalDateTime now, LocalDateTime arrivalAt, Integer minutesLater) {
        if (arrivalAt != null && minutesLater != null) {
            throw ApiException.invalidQuery("arrivalAt과 minutesLater는 함께 지정할 수 없습니다.");
        }
        if (arrivalAt == null) {
            int minutes = minutesLater == null ? DEFAULT_MINUTES_LATER : minutesLater;
            if (minutes < 0 || minutes > MAX_MINUTES_LATER) {
                throw ApiException.invalidQuery("minutesLater는 0~" + MAX_MINUTES_LATER + " 사이여야 합니다: " + minutes);
            }
            return now.plusMinutes(minutes);
        }
        if (arrivalAt.isBefore(now.minusMinutes(PAST_TOLERANCE_MINUTES))) {
            throw ApiException.invalidQuery("arrivalAt이 이미 지난 시각입니다: " + arrivalAt);
        }
        if (arrivalAt.isAfter(now.plusMinutes(MAX_MINUTES_LATER))) {
            throw ApiException.invalidQuery("arrivalAt은 지금부터 " + MAX_MINUTES_LATER + "분 안이어야 합니다: " + arrivalAt);
        }
        return arrivalAt.isBefore(now) ? now : arrivalAt;
    }

    private static StationPredictionResponse.Point point(LocalDateTime now, LocalDateTime eta, LocalDateTime window,
            Prediction p, StationForecastRow forecast) {
        int minutesAhead = (int) Math.max(0, Duration.between(now, eta).toMinutes());
        StationPredictionResponse.Forecast f = forecast == null ? null
                : new StationPredictionResponse.Forecast(forecast.pAvailable().doubleValue(),
                        forecast.predictedOccupancyRate() == null ? null
                                : forecast.predictedOccupancyRate().doubleValue(),
                        forecast.modelVersion(), forecast.generatedAt());
        return new StationPredictionResponse.Point(eta, window, minutesAhead, RecommendService.round(p.probability(), 3),
                p.basis(), p.basisSampleCount(), RecommendService.round(p.statistical(), 3),
                RecommendService.round(p.realtimeWeight(), 3), f);
    }

    private static StationPredictionResponse.Current current(CurrentStatus s, List<StatusCountRow> rows) {
        LocalDateTime lastUpdated = rows.stream().map(StatusCountRow::latestUpdatedAt).filter(Objects::nonNull)
                .max(Comparator.naturalOrder()).orElse(null);
        Double rate = s.occupancyRate();
        return new StationPredictionResponse.Current(RecommendService.fastCount(s), s.available(), s.charging(),
                s.unavailable(), s.unknown(), rate == null ? null : RecommendService.round(rate, 3), lastUpdated);
    }
}
