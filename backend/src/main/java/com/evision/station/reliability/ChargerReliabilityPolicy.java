package com.evision.station.reliability;

import java.time.LocalDateTime;

import org.springframework.stereotype.Component;

import com.evision.common.code.NormalizedStatus;
import com.evision.station.domain.QCharger;
import com.querydsl.core.types.dsl.BooleanExpression;

/**
 * 충전기 현재 상태(charger.current_*)로 {@link ChargerReliability}를 판정한다. DB에 저장하지 않고 조회할 때 계산한다.
 */
@Component
public class ChargerReliabilityPolicy {

    private final ReliabilityProperties properties;

    public ChargerReliabilityPolicy(ReliabilityProperties properties) {
        this.properties = properties;
    }

    /**
     * @param status    정규화 상태. 아직 상태 기록이 없으면 null
     * @param updatedAt 원천 상태갱신일시(상태가 마지막으로 바뀐 시각)
     */
    public ChargerReliability classify(NormalizedStatus status, LocalDateTime updatedAt, LocalDateTime now) {
        if (status == null || status == NormalizedStatus.UNKNOWN) {
            return ChargerReliability.UNKNOWN;
        }
        return switch (status) {
            case UNAVAILABLE -> ChargerReliability.FAULT;
            case CHARGING -> isBefore(updatedAt, chargingSince(now)) ? ChargerReliability.SUSPECT : ChargerReliability.NORMAL;
            case AVAILABLE -> isBefore(updatedAt, availableSince(now)) ? ChargerReliability.SUSPECT : ChargerReliability.NORMAL;
            case UNKNOWN -> ChargerReliability.UNKNOWN;
        };
    }

    /** {@link #classify}가 NORMAL인 충전기 조건 (Querydsl). 추천 후보에서 정상 급속이 없는 충전소를 뺄 때 쓴다. */
    public BooleanExpression isNormal(QCharger c, LocalDateTime now) {
        return c.currentNormalizedStatus.eq(NormalizedStatus.AVAILABLE)
                .and(c.currentStatusUpdatedAt.goe(availableSince(now)))
                .or(c.currentNormalizedStatus.eq(NormalizedStatus.CHARGING)
                        .and(c.currentStatusUpdatedAt.goe(chargingSince(now))));
    }

    private LocalDateTime chargingSince(LocalDateTime now) {
        return now.minusHours(properties.suspectChargingHours());
    }

    private LocalDateTime availableSince(LocalDateTime now) {
        return now.minusDays(properties.suspectAvailableDays());
    }

    /** 갱신 시각을 모르면 오래됐는지 확인할 수 없으므로 의심으로 본다. */
    private static boolean isBefore(LocalDateTime updatedAt, LocalDateTime since) {
        return updatedAt == null || updatedAt.isBefore(since);
    }
}
