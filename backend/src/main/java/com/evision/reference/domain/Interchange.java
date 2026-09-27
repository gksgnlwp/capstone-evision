package com.evision.reference.domain;

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
 * 고속도로 출입시설 IC·JCT (고속도로 출입시설 위치정보).
 */
@Entity
@Table(name = "interchange")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Interchange {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ic_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "route_id", nullable = false)
    private Route route;

    @Column(name = "facility_code", nullable = false, unique = true, length = 20)
    private String facilityCode;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    /** IC / JCT */
    @Column(name = "facility_type", nullable = false, length = 5)
    private String facilityType;

    @Column(name = "latitude", nullable = false)
    private Double latitude;

    @Column(name = "longitude", nullable = false)
    private Double longitude;
}
