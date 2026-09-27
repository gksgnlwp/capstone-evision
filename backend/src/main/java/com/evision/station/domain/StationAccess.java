package com.evision.station.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.evision.reference.domain.Interchange;
import com.evision.reference.domain.RestArea;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * 충전소 접근지점 (휴게소 또는 IC). 이 매핑이 있는 충전소만 서비스·집계 대상이다.
 * 추천 API(한휘제)와 공유하는 테이블이다.
 */
@Entity
@Table(name = "station_access")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StationAccess {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "access_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "station_id", nullable = false)
    private Station station;

    @Enumerated(EnumType.STRING)
    @Column(name = "access_type", nullable = false, length = 10)
    private AccessType accessType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "rest_area_id")
    private RestArea restArea;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ic_id")
    private Interchange interchange;

    @Column(name = "detour_km", nullable = false, precision = 5, scale = 2)
    private BigDecimal detourKm;

    @Enumerated(EnumType.STRING)
    @Column(name = "mapping_method", nullable = false, length = 10)
    private MappingMethod mappingMethod;

    @Column(name = "verified_at")
    private LocalDateTime verifiedAt;
}
