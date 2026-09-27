package com.evision.reference.domain;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.evision.common.jpa.YnBooleanConverter;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
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
 * 고속도로 휴게소 (전국휴게소정보표준데이터).
 */
@Entity
@Table(name = "rest_area")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RestArea {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "rest_area_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "route_id", nullable = false)
    private Route route;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    /** 원천 노선방향 값 그대로 */
    @Column(name = "direction", nullable = false, length = 20)
    private String direction;

    @Column(name = "latitude", nullable = false)
    private Double latitude;

    @Column(name = "longitude", nullable = false)
    private Double longitude;

    @Convert(converter = YnBooleanConverter.class)
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "ev_charger_yn", nullable = false, length = 1)
    private Boolean evCharger;
}
