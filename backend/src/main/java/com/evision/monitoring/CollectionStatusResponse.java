package com.evision.monitoring;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * GET /api/admin/collection-status 응답 (명세 6.5, OP-08).
 *
 * @param status             OK 또는 NO_RECORDS (기간 내 회차 기록 없음)
 * @param consecutiveFailures 2회 이상 연속으로 성공하지 못한 5분 슬롯 구간 (G1: 2회 연속 실패 0회)
 * @param missingRanges       성공 회차가 없는 5분 슬롯 구간 전체 (1회 포함)
 */
public record CollectionStatusResponse(
        LocalDateTime from,
        LocalDateTime to,
        String status,
        LastSuccess lastSuccess,
        RunSummary runs,
        RecordCounts counts,
        ApiCalls apiCallsToday,
        Availability availability,
        int maxConsecutiveFailures,
        List<SlotRange> consecutiveFailures,
        List<SlotRange> missingRanges,
        Disk disk) {

    /** 최근 성공 시각 (전체 기간 기준) */
    public record LastSuccess(LocalDateTime status, LocalDateTime info) {
    }

    public record RunSummary(int total, Map<String, Integer> byStatus) {
    }

    /** 기간 내 회차 건수 합 */
    public record RecordCounts(long fetched, long inserted, long duplicate, long unknown, long invalid) {
    }

    public record ApiCalls(long used, int limit) {
    }

    /**
     * @param slotRate   성공 슬롯 / 예정 슬롯 (경과한 슬롯만 분모). 예정 슬롯이 없으면 null
     * @param windowRate 완전한 30분 구간 / 예정 구간. 6개 관측 시점이 모두 유효해야 완전하다 (G1: 99% 이상)
     */
    public record Availability(int scheduledSlots, int successfulSlots, Double slotRate,
            int scheduledWindows, int completeWindows, Double windowRate) {
    }

    /** from ~ to는 첫 슬롯과 마지막 슬롯 (5분 격자) */
    public record SlotRange(LocalDateTime from, LocalDateTime to, int slots) {
    }

    /** 서버 디스크. usedRate가 0.8 이상이면 warning */
    public record Disk(long totalBytes, long usableBytes, double usedRate, boolean warning) {
    }
}
