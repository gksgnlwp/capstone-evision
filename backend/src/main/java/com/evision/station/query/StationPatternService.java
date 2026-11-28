package com.evision.station.query;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.evision.common.error.ApiException;
import com.evision.recommend.RecommendProperties;
import com.evision.station.domain.Station;
import com.evision.station.query.StationPatternRepository.SlotRow;

/**
 * 충전소 혼잡 패턴 (FR-10·M10). 최근 N주 30분 집계를 요일·시간대(30분 칸)별로 묶어 평균 사용률과 쾌적 비율을 준다.
 * 사용률을 알 수 없는 구간(occupancy_rate NULL)은 평균에서 빼고 개수만 따로 알려 준다.
 * 쾌적 기준은 추천과 같은 evision.recommend.comfort-threshold 를 쓴다 (AI 예측과 같은 뜻).
 */
@Service
@Transactional(readOnly = true)
public class StationPatternService {

    static final int MIN_WEEKS = 1;
    static final int MAX_WEEKS = 8;
    static final int SLOTS_PER_DAY = 48;

    private final StationQueryRepository stations;
    private final StationPatternRepository repository;
    private final RecommendProperties recommendProperties;
    private final Clock clock;

    public StationPatternService(StationQueryRepository stations, StationPatternRepository repository,
            RecommendProperties recommendProperties, Clock clock) {
        this.stations = stations;
        this.repository = repository;
        this.recommendProperties = recommendProperties;
        this.clock = clock;
    }

    public StationPatternResponse patterns(long stationId, Integer weeksParam) {
        int weeks = weeksParam == null ? recommendProperties.historyWeeks() : weeksParam;
        if (weeks < MIN_WEEKS || weeks > MAX_WEEKS) {
            throw ApiException.invalidQuery("weeks는 " + MIN_WEEKS + "~" + MAX_WEEKS + " 사이여야 합니다: " + weeks);
        }
        Station station = stations.findActiveStation(stationId)
                .orElseThrow(() -> ApiException.notFound("충전소를 찾을 수 없습니다: " + stationId));

        LocalDateTime to = currentWindowStart(LocalDateTime.now(clock));
        LocalDateTime from = to.minusWeeks(weeks);
        double threshold = recommendProperties.comfortThreshold();

        Map<Integer, SlotRow> byKey = new HashMap<>();
        int total = 0;
        int valid = 0;
        for (SlotRow r : repository.slotStats(stationId, from, to, threshold)) {
            byKey.put(key(r.dayOfWeek(), r.slot()), r);
            total += r.total();
            valid += r.valid();
        }

        List<StationPatternResponse.Slot> slots = new ArrayList<>(7 * SLOTS_PER_DAY);
        for (int day = 1; day <= 7; day++) {
            for (int slot = 0; slot < SLOTS_PER_DAY; slot++) {
                SlotRow r = byKey.get(key(day, slot));
                String time = String.format("%02d:%02d", slot / 2, slot % 2 * 30);
                if (r == null || r.valid() == 0) {
                    slots.add(new StationPatternResponse.Slot(day, time, null, null, 0));
                } else {
                    slots.add(new StationPatternResponse.Slot(day, time, round(r.averageRate(), 4),
                            round((double) r.comfort() / r.valid(), 3), r.valid()));
                }
            }
        }
        return new StationPatternResponse(station.getId(), station.getName(), weeks, from, to, threshold,
                total, valid, total - valid, slots);
    }

    /** 지금 진행 중인 30분 구간의 시작. 이 구간은 아직 집계 전이라 뺀다. */
    static LocalDateTime currentWindowStart(LocalDateTime now) {
        LocalDateTime hour = now.truncatedTo(ChronoUnit.HOURS);
        return now.getMinute() >= 30 ? hour.plusMinutes(30) : hour;
    }

    private static int key(int dayOfWeek, int slot) {
        return dayOfWeek * 100 + slot;
    }

    private static Double round(Double value, int scale) {
        return value == null ? null : BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_UP).doubleValue();
    }
}
