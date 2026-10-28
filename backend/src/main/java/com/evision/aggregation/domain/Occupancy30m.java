package com.evision.aggregation.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.evision.station.domain.Station;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 30분 단위 충전소 점유율 (OP-13, OP-18). 예측 모델(박상현)의 입력 테이블이다.
 */
@Entity
@Table(name = "occupancy_30m")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Occupancy30m {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "occupancy_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "station_id", nullable = false)
    private Station station;

    /** 00분 또는 30분 정각 */
    @Column(name = "window_start", nullable = false)
    private LocalDateTime windowStart;

    @Column(name = "fast_charger_count", nullable = false)
    private short fastChargerCount;

    /** 분모 0이면 NULL */
    @Column(name = "occupancy_rate", precision = 5, scale = 4)
    private BigDecimal occupancyRate;

    /** 미확인이면 NULL */
    @Column(name = "available")
    private Boolean available;

    @Column(name = "full_minutes", nullable = false)
    private short fullMinutes;

    @Column(name = "valid_minutes", nullable = false)
    private short validMinutes;

    /** 유효 관측 시점 수 (최대 6) */
    @Column(name = "observed_points", nullable = false)
    private short observedPoints;

    @Column(name = "computed_at", nullable = false)
    private LocalDateTime computedAt;
}
