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

    private static final int MAX_ERROR_MESSAGE_LENGTH = 2000;

    public static CollectionRun start(RunType runType, LocalDateTime startedAt) {
        CollectionRun run = new CollectionRun();
        run.runType = runType;
        run.startedAt = startedAt;
        run.runStatus = RunStatus.RUNNING;
        return run;
    }

    public void finish(RunStatus status, LocalDateTime endedAt, RunCounts counts, String errorCode, String errorMessage) {
        if (status == RunStatus.RUNNING) {
            throw new IllegalArgumentException("종료 상태로 RUNNING을 쓸 수 없습니다.");
        }
        this.runStatus = status;
        this.endedAt = endedAt;
        this.apiCallCount = (short) Math.min(counts.apiCallCount(), Short.MAX_VALUE);
        this.totalCount = counts.totalCount();
        this.fetchedCount = counts.fetchedCount();
        this.insertedCount = counts.insertedCount();
        this.duplicateCount = counts.duplicateCount();
        this.unknownCount = counts.unknownCount();
        this.invalidCount = counts.invalidCount();
        this.retryCount = (short) Math.min(counts.retryCount(), Short.MAX_VALUE);
        this.errorCode = errorCode;
        this.errorMessage = truncate(errorMessage);
    }

    /** 앱 재시작 시 RUNNING으로 남은 회차를 FAILED(ABORTED)로 정리한다. */
    public void abort(LocalDateTime endedAt) {
        this.runStatus = RunStatus.FAILED;
        this.endedAt = endedAt;
        this.errorCode = "ABORTED";
        this.errorMessage = "앱 재시작 시 RUNNING 상태로 남아 있던 회차";
    }

    private static String truncate(String message) {
        if (message == null || message.length() <= MAX_ERROR_MESSAGE_LENGTH) {
            return message;
        }
        return message.substring(0, MAX_ERROR_MESSAGE_LENGTH);
    }
}
