package com.evision.monitoring;

import java.io.File;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.TreeSet;

import org.springframework.stereotype.Service;

import com.evision.aggregation.OccupancyAggregationService;
import com.evision.collection.domain.RunStatus;
import com.evision.collection.domain.RunType;
import com.evision.collection.service.ApiCallBudget;
import com.evision.common.error.ApiException;
import com.evision.monitoring.CollectionStatusResponse.ApiCalls;
import com.evision.monitoring.CollectionStatusResponse.Availability;
import com.evision.monitoring.CollectionStatusResponse.Disk;
import com.evision.monitoring.CollectionStatusResponse.LastSuccess;
import com.evision.monitoring.CollectionStatusResponse.RecordCounts;
import com.evision.monitoring.CollectionStatusResponse.RunSummary;
import com.evision.monitoring.CollectionStatusResponse.SlotRange;

/**
 * SD-05 / OP-08 수집 현황 모니터링.
 *
 * <ul>
 *   <li>예정 슬롯: 기간 안의 5분 격자 중 이미 지난 것 (당일은 경과한 슬롯만 분모)</li>
 *   <li>슬롯 성공: 슬롯 시각 ±1분 안에 시작한 SUCCESS/PARTIAL STATUS 회차가 있음</li>
 *   <li>30분 구간 완전: 6개 관측 시점이 모두 유효 (집계와 같은 규칙, 직전 10분 안 성공 회차)</li>
 *   <li>연속 실패: 성공 회차가 없는 슬롯이 2개 이상 이어진 구간</li>
 * </ul>
 */
@Service
public class CollectionStatusService {

    static final Duration MAX_RANGE = Duration.ofDays(31);
    static final int SLOT_MINUTES = 5;
    static final Duration SLOT_MATCH = Duration.ofMinutes(1);
    static final double DISK_WARNING_RATE = 0.8;

    private final MonitoringRepository repository;
    private final ApiCallBudget budget;
    private final Clock clock;
    private final File diskRoot;

    public CollectionStatusService(MonitoringRepository repository, ApiCallBudget budget, Clock clock) {
        this.repository = repository;
        this.budget = budget;
        this.clock = clock;
        this.diskRoot = new File("/");
    }

    /** OP-08 getCollectionStatus */
    public CollectionStatusResponse getStatus(LocalDateTime from, LocalDateTime to) {
        if (!from.isBefore(to)) {
            throw ApiException.invalidQuery("from은 to보다 앞서야 합니다.");
        }
        if (Duration.between(from, to).compareTo(MAX_RANGE) > 0) {
            throw ApiException.invalidQuery("조회 기간은 최대 31일입니다.");
        }

        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime end = to.isAfter(now) ? now : to;

        Map<RunType, LocalDateTime> last = repository.lastSuccess();
        List<MonitoringRepository.StatusRow> rows = repository.runsByStatus(from, to);
        RunSummary runs = summarize(rows);
        RecordCounts counts = sumCounts(rows);
        ApiCalls apiCalls = new ApiCalls(budget.usedToday(), budget.dailyLimit());
        Disk disk = disk();

        NavigableSet<LocalDateTime> successRuns = new TreeSet<>(repository.successfulStatusRunTimes(
                from.minus(OccupancyAggregationService.VALIDITY_LOOKBACK), end.plus(SLOT_MATCH)));

        List<LocalDateTime> slots = elapsedSlots(from, end, now);
        List<SlotRange> missing = missingRanges(slots, successRuns);
        List<SlotRange> consecutive = missing.stream().filter(r -> r.slots() >= 2).toList();
        int maxConsecutive = missing.stream().mapToInt(SlotRange::slots).max().orElse(0);
        int successfulSlots = slots.size() - missing.stream().mapToInt(SlotRange::slots).sum();

        int[] windows = windows(from, end, now, successRuns);
        Availability availability = new Availability(slots.size(), successfulSlots, rate(successfulSlots, slots.size()),
                windows[0], windows[1], rate(windows[1], windows[0]));

        String status = runs.total() == 0 ? "NO_RECORDS" : "OK";
        return new CollectionStatusResponse(from, to, status,
                new LastSuccess(last.get(RunType.STATUS), last.get(RunType.INFO)),
                runs, counts, apiCalls, availability, maxConsecutive, consecutive, missing, disk);
    }

    /** 기간 안의 5분 격자 중 이미 지나서 회차가 끝났을 시각인 것 */
    static List<LocalDateTime> elapsedSlots(LocalDateTime from, LocalDateTime end, LocalDateTime now) {
        List<LocalDateTime> slots = new ArrayList<>();
        for (LocalDateTime s = ceilToSlot(from); s.isBefore(end) && !s.plus(SLOT_MATCH).isAfter(now);
                s = s.plusMinutes(SLOT_MINUTES)) {
            slots.add(s);
        }
        return slots;
    }

    static List<SlotRange> missingRanges(List<LocalDateTime> slots, NavigableSet<LocalDateTime> successRuns) {
        List<SlotRange> ranges = new ArrayList<>();
        LocalDateTime start = null;
        LocalDateTime prev = null;
        int count = 0;
        for (LocalDateTime slot : slots) {
            if (slotSucceeded(slot, successRuns)) {
                if (start != null) {
                    ranges.add(new SlotRange(start, prev, count));
                    start = null;
                    count = 0;
                }
            } else {
                if (start == null) {
                    start = slot;
                }
                prev = slot;
                count++;
            }
        }
        if (start != null) {
            ranges.add(new SlotRange(start, prev, count));
        }
        return ranges;
    }

    static boolean slotSucceeded(LocalDateTime slot, NavigableSet<LocalDateTime> successRuns) {
        LocalDateTime first = successRuns.ceiling(slot.minus(SLOT_MATCH));
        return first != null && first.isBefore(slot.plus(SLOT_MATCH));
    }

    /** 관측 시점 유효성: (t - 10분, t + 1분] 안에 성공 회차가 있음. 집계의 isValid와 같은 규칙이다. */
    static boolean pointValid(LocalDateTime t, NavigableSet<LocalDateTime> successRuns) {
        LocalDateTime first = successRuns.higher(t.minus(OccupancyAggregationService.VALIDITY_LOOKBACK));
        return first != null && !first.isAfter(t.plus(OccupancyAggregationService.VALIDITY_TOLERANCE));
    }

    /** @return [예정 구간 수, 완전한 구간 수]. 기간 안에 완전히 들어가고 이미 끝난 30분 구간만 센다 */
    static int[] windows(LocalDateTime from, LocalDateTime end, LocalDateTime now,
            NavigableSet<LocalDateTime> successRuns) {
        int scheduled = 0;
        int complete = 0;
        LocalDateTime w = OccupancyAggregationService.floorToWindow(from);
        if (w.isBefore(from)) {
            w = w.plusMinutes(OccupancyAggregationService.WINDOW_MINUTES);
        }
        for (; !w.plusMinutes(OccupancyAggregationService.WINDOW_MINUTES).isAfter(end);
                w = w.plusMinutes(OccupancyAggregationService.WINDOW_MINUTES)) {
            List<LocalDateTime> points = OccupancyAggregationService.pointsOf(w);
            if (points.get(points.size() - 1).plus(SLOT_MATCH).isAfter(now)) {
                break;
            }
            scheduled++;
            if (points.stream().allMatch(t -> pointValid(t, successRuns))) {
                complete++;
            }
        }
        return new int[] {scheduled, complete};
    }

    static LocalDateTime ceilToSlot(LocalDateTime t) {
        LocalDateTime minute = t.truncatedTo(ChronoUnit.MINUTES);
        int remainder = minute.getMinute() % SLOT_MINUTES;
        LocalDateTime floor = minute.minusMinutes(remainder);
        return floor.equals(t) ? floor : floor.plusMinutes(SLOT_MINUTES);
    }

    private static RunSummary summarize(List<MonitoringRepository.StatusRow> rows) {
        Map<String, Integer> byStatus = new LinkedHashMap<>();
        for (RunStatus s : RunStatus.values()) {
            byStatus.put(s.name(), 0);
        }
        int total = 0;
        for (MonitoringRepository.StatusRow r : rows) {
            byStatus.put(r.runStatus(), r.runs());
            total += r.runs();
        }
        return new RunSummary(total, byStatus);
    }

    private static RecordCounts sumCounts(List<MonitoringRepository.StatusRow> rows) {
        long fetched = 0, inserted = 0, duplicate = 0, unknown = 0, invalid = 0;
        for (MonitoringRepository.StatusRow r : rows) {
            fetched += r.fetched();
            inserted += r.inserted();
            duplicate += r.duplicate();
            unknown += r.unknown();
            invalid += r.invalid();
        }
        return new RecordCounts(fetched, inserted, duplicate, unknown, invalid);
    }

    private Disk disk() {
        long total = diskRoot.getTotalSpace();
        long usable = diskRoot.getUsableSpace();
        double used = total == 0 ? 0 : (double) (total - usable) / total;
        return new Disk(total, usable, Math.round(used * 1000) / 1000.0, used >= DISK_WARNING_RATE);
    }

    private static Double rate(int numerator, int denominator) {
        return denominator == 0 ? null : Math.round((double) numerator / denominator * 10000) / 10000.0;
    }
}
