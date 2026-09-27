package com.evision.reference.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 고속도로 노선 (한국도로공사 기준 데이터).
 */
@Entity
@Table(name = "route")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Route {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "route_id")
    private Long id;

    @Column(name = "route_no", nullable = false, unique = true, length = 10)
    private String routeNo;

    @Column(name = "route_name", nullable = false, length = 50)
    private String routeName;
}
