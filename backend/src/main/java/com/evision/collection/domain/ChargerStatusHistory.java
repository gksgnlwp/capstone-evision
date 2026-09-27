package com.evision.collection.domain;

import java.time.LocalDateTime;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.evision.station.domain.Charger;

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
 * 충전기 상태 이력. 대량 적재는 JPA가 아니라 JdbcTemplate.batchUpdate로 한다 (OP-12).
 * 이 엔티티는 조회(OP-19)와 스키마 검증용이다.
 */
@Entity
@Table(name = "charger_status_history")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChargerStatusHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "history_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "charger_id", nullable = false)
    private Charger charger;

    /** 원천 stat 코드 그대로 */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "status_code", nullable = false, length = 1)
    private String statusCode;

    @Column(name = "status_updated_at", nullable = false)
    private LocalDateTime statusUpdatedAt;

    /** INFO 수집분에만 존재 */
    @Column(name = "last_charge_start")
    private LocalDateTime lastChargeStart;

    @Column(name = "last_charge_end")
    private LocalDateTime lastChargeEnd;

    @Column(name = "now_charge_start")
    private LocalDateTime nowChargeStart;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_api", nullable = false, length = 10)
    private SourceApi sourceApi;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "run_id", nullable = false)
    private CollectionRun run;

    @Column(name = "collected_at", nullable = false)
    private LocalDateTime collectedAt;
}
