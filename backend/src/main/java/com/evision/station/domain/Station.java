package com.evision.station.domain;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.evision.common.jpa.YnBooleanConverter;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 충전소 (한국환경공단 getChargerInfo 기준, 일 단위 갱신 OP-09).
 */
@Entity
@Table(name = "station")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Station {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "station_id")
    private Long id;

    /** 원천 충전소ID (공식 명세 크기 8) */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "stat_id", nullable = false, unique = true, length = 8)
    private String statId;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "address", nullable = false, length = 255)
    private String address;

    @Column(name = "address_detail", length = 255)
    private String addressDetail;

    @Column(name = "latitude", nullable = false)
    private Double latitude;

    @Column(name = "longitude", nullable = false)
    private Double longitude;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "zcode", nullable = false, length = 2)
    private String zcode;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "zscode", length = 5)
    private String zscode;

    @Column(name = "kind", length = 2)
    private String kind;

    @Column(name = "kind_detail", length = 4)
    private String kindDetail;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "busi_id", nullable = false, length = 2)
    private String busiId;

    @Column(name = "org_name", length = 100)
    private String orgName;

    @Column(name = "operator_name", length = 100)
    private String operatorName;

    @Column(name = "operator_call", length = 20)
    private String operatorCall;

    @Column(name = "use_time", length = 100)
    private String useTime;

    @Convert(converter = YnBooleanConverter.class)
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "parking_free_yn", length = 1)
    private Boolean parkingFree;

    @Convert(converter = YnBooleanConverter.class)
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "limit_yn", length = 1)
    private Boolean limited;

    @Column(name = "limit_detail", length = 255)
    private String limitDetail;

    @Convert(converter = YnBooleanConverter.class)
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "del_yn", nullable = false, length = 1)
    private boolean deleted;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** 양방향은 Station → chargers만 허용한다. */
    @OneToMany(mappedBy = "station")
    private List<Charger> chargers = new ArrayList<>();
}
