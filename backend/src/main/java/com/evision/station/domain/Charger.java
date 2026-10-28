package com.evision.station.domain;

import java.time.LocalDateTime;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.evision.common.code.NormalizedStatus;
import com.evision.common.jpa.YnBooleanConverter;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
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
 * 충전기. 조회 API가 이력 테이블을 스캔하지 않도록 현재 상태(current_*)를 함께 보관한다.
 */
@Entity
@Table(name = "charger")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Charger {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "charger_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "station_id", nullable = false)
    private Station station;

    /** 원천 충전기ID (공식 명세 크기 2) */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "chger_id", nullable = false, length = 2)
    private String chgerId;

    /** 코드표 해석 대상이라 String으로 유지한다. */
    @Column(name = "charger_type", nullable = false, length = 2)
    private String chargerType;

    @Column(name = "output_kw")
    private Integer outputKw;

    @Column(name = "method", length = 20)
    private String method;

    @Column(name = "is_fast", nullable = false)
    private boolean fast;

    @Convert(converter = YnBooleanConverter.class)
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "del_yn", nullable = false, length = 1)
    private boolean deleted;

    /** 원천 stat 코드 */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "current_status_code", length = 1)
    private String currentStatusCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "current_normalized_status", length = 12)
    private NormalizedStatus currentNormalizedStatus;

    @Column(name = "current_status_updated_at")
    private LocalDateTime currentStatusUpdatedAt;
}
