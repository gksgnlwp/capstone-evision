package com.evision.collection.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 수집 회차 기록 (OP-08 모니터링의 원천).
 */
@Entity
@Table(name = "collection_run")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CollectionRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "run_id")
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "run_type", nullable = false, length = 10)
    private RunType runType;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "ended_at")
    private LocalDateTime endedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "run_status", nullable = false, length = 10)
    private RunStatus runStatus;

    @Column(name = "api_call_count", nullable = false)
    private short apiCallCount;

    @Column(name = "total_count")
    private Integer totalCount;

    @Column(name = "fetched_count", nullable = false)
    private int fetchedCount;

    @Column(name = "inserted_count", nullable = false)
    private int insertedCount;

    @Column(name = "duplicate_count", nullable = false)
    private int duplicateCount;

    /** 미등록 충전기로 건너뛴 건수 */
    @Column(name = "unknown_count", nullable = false)
    private int unknownCount;

    /** 형식 오류 건수 */
    @Column(name = "invalid_count", nullable = false)
    private int invalidCount;

    @Column(name = "retry_count", nullable = false)
    private short retryCount;

    /** API resultCode 또는 내부 오류 코드 */
    @Column(name = "error_code", length = 40)
    private String errorCode;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;
}
