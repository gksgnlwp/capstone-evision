package com.evision.forecast.domain;

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
 * 30분 구간 가용 확률 AI 예측 (예측 모델 박상현 → 추천 한휘제). 예측 배치가 쓰고 백엔드는 읽기만 한다.
 * 연동 규격은 docs/forecast/README.md.
 */
@Entity
@Table(name = "occupancy_forecast")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OccupancyForecast {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "forecast_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "station_id", nullable = false)
    private Station station;

    /** 00분 또는 30분 정각 (KST) */
    @Column(name = "target_window_start", nullable = false)
    private LocalDateTime targetWindowStart;

    /** 구간 안에 급속충전기 1기 이상 가용일 확률 */
    @Column(name = "p_available", nullable = false, precision = 5, scale = 4)
    private BigDecimal pAvailable;

    @Column(name = "predicted_occupancy_rate", precision = 5, scale = 4)
    private BigDecimal predictedOccupancyRate;

    @Column(name = "model_version", nullable = false, length = 40)
    private String modelVersion;

    @Column(name = "generated_at", nullable = false)
    private LocalDateTime generatedAt;
}
