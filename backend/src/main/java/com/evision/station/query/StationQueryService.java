package com.evision.station.query;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.evision.collection.domain.ChargerStatusHistory;
import com.evision.collection.service.ChargerStatusCodes;
import com.evision.common.code.NormalizedStatus;
import com.evision.common.error.ApiException;
import com.evision.station.domain.Station;
import com.evision.station.query.StationQueryRepository.StatusCountRow;
import com.evision.station.query.StationSearchResponse.StationSummary;

/**
 * 조회 API (명세 6.2~6.4). 검색·상세는 이력 테이블을 보지 않고 charger.current_* 만 쓴다.
 */
@Service
@Transactional(readOnly = true)
public class StationQueryService {

    static final Duration MAX_HISTORY_RANGE = Duration.ofDays(7);
    static final int DEFAULT_HISTORY_SIZE = 100;
    static final int MAX_HISTORY_SIZE = 500;

    private final StationQueryRepository repository;
    private final ChargerStatusCodes statusCodes;

    public StationQueryService(StationQueryRepository repository, ChargerStatusCodes statusCodes) {
        this.repository = repository;
        this.statusCodes = statusCodes;
    }

    /** FR-06 노선 목록. 충전소가 매핑된 노선만, 노선번호 순. 쿼리 2회. */
    public RouteListResponse routes() {
        Map<Long, RouteBuilder> byRoute = new HashMap<>();
        for (StationQueryRepository.RouteDirectionRow r : repository.countRestAreaStationsByRoute()) {
            RouteBuilder b = byRoute.computeIfAbsent(r.routeId(), id -> new RouteBuilder(id, r.routeNo(), r.routeName()));
            b.directions.add(r.direction());
            b.restAreaStations += r.stations();
        }
        for (StationQueryRepository.RouteIcRow r : repository.countIcStationsByRoute()) {
            byRoute.computeIfAbsent(r.routeId(), id -> new RouteBuilder(id, r.routeNo(), r.routeName()))
                    .icStations += r.stations();
        }
        List<RouteListResponse.RouteItem> routes = byRoute.values().stream()
                .sorted(Comparator.comparingInt((RouteBuilder b) -> routeNoOrder(b.routeNo))
                        .thenComparing(b -> b.routeNo))
                .map(RouteBuilder::build)
                .toList();
        return new RouteListResponse(routes);
    }

    /** 노선번호는 문자열이라 숫자로 비교한다 (1, 10, 15, 100 …). 숫자가 아니면 뒤로 보낸다. */
    private static int routeNoOrder(String routeNo) {
        try {
            return Integer.parseInt(routeNo);
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    private static final class RouteBuilder {
        final long routeId;
        final String routeNo;
        final String routeName;
        final TreeSet<String> directions = new TreeSet<>();
        long restAreaStations;
        long icStations;

        RouteBuilder(long routeId, String routeNo, String routeName) {
            this.routeId = routeId;
            this.routeNo = routeNo;
            this.routeName = routeName;
        }

        RouteListResponse.RouteItem build() {
            return new RouteListResponse.RouteItem(routeId, routeNo, routeName, List.copyOf(directions),
                    restAreaStations, icStations);
        }
    }

    /** FR-34 운영기관 목록. 충전소 수가 많은 순, 같으면 코드 순. 쿼리 1회. */
    public OperatorListResponse operators() {
        List<OperatorListResponse.OperatorItem> operators = repository.countStationsByOperator().stream()
                .map(r -> new OperatorListResponse.OperatorItem(r.busiId(),
                        r.orgName() == null ? r.busiId() : r.orgName(), r.stations()))
                .sorted(Comparator.comparingLong(OperatorListResponse.OperatorItem::stationCount).reversed()
                        .thenComparing(OperatorListResponse.OperatorItem::busiId))
                .toList();
        return new OperatorListResponse(operators);
    }

    /** OP-01 충전소 검색. 쿼리 수는 결과 건수와 관계없이 3회 (충전소, 접근지점, 상태 집계). */
    public StationSearchResponse search(StationSearchCondition c) {
        List<Station> found = repository.searchStations(c, c.limit() + 1);
        boolean truncated = found.size() > c.limit();
        List<Station> stations = truncated ? found.subList(0, c.limit()) : found;
        if (stations.isEmpty()) {
            return new StationSearchResponse(0, c.limit(), false, List.of());
        }

        List<Long> ids = stations.stream().map(Station::getId).toList();
        Map<Long, List<AccessPointView>> accesses = repository
                .findAccesses(ids, c.routeId(), c.direction(), c.accessType()).stream()
                .collect(Collectors.groupingBy(a -> a.getStation().getId(),
                        Collectors.mapping(AccessPointView::from, Collectors.toList())));
        Map<Long, List<StatusCountRow>> counts = repository.countFastChargersByStatus(ids).stream()
                .collect(Collectors.groupingBy(StatusCountRow::stationId));

        List<StationSummary> summaries = stations.stream()
                .map(s -> summarize(s, accesses.getOrDefault(s.getId(), List.of()),
                        counts.getOrDefault(s.getId(), List.of())))
                .toList();
        return new StationSearchResponse(summaries.size(), c.limit(), truncated, summaries);
    }

    /** OP-02 충전소 상세 (UC-02·03). 쿼리 3회 (충전소, 접근지점, 충전기). */
    public StationDetailResponse detail(long stationId) {
        Station s = repository.findActiveStation(stationId)
                .orElseThrow(() -> ApiException.notFound("충전소를 찾을 수 없습니다: " + stationId));
        List<AccessPointView> accesses = repository.findAccesses(List.of(stationId), null, null, null).stream()
                .map(AccessPointView::from).toList();
        List<StationDetailResponse.ChargerView> chargers = repository.findActiveChargers(stationId).stream()
                .map(StationDetailResponse.ChargerView::from).toList();
        return new StationDetailResponse(s.getId(), s.getStatId(), s.getName(), s.getAddress(),
                s.getAddressDetail(), s.getLatitude(), s.getLongitude(), s.getBusiId(), s.getOrgName(),
                s.getOperatorName(), s.getOperatorCall(), s.getUseTime(), s.getParkingFree(), s.getLimited(),
                s.getLimitDetail(), s.getUpdatedAt(), accesses, chargers);
    }

    /** OP-19 충전기 상태 이력. offset 없이 keyset(cursor = 마지막 status_updated_at)으로 넘긴다. */
    public ChargerHistoryResponse history(long chargerId, LocalDateTime from, LocalDateTime to,
            Integer size, LocalDateTime cursor) {
        if (!from.isBefore(to)) {
            throw ApiException.invalidQuery("from은 to보다 앞이어야 합니다.");
        }
        if (Duration.between(from, to).compareTo(MAX_HISTORY_RANGE) > 0) {
            throw ApiException.invalidQuery("조회 기간은 최대 7일입니다.");
        }
        int pageSize = size == null ? DEFAULT_HISTORY_SIZE : size;
        if (pageSize < 1 || pageSize > MAX_HISTORY_SIZE) {
            throw ApiException.invalidQuery("size는 1~" + MAX_HISTORY_SIZE + " 사이여야 합니다.");
        }
        if (!repository.chargerExists(chargerId)) {
            throw ApiException.notFound("충전기를 찾을 수 없습니다: " + chargerId);
        }

        List<ChargerStatusHistory> rows = repository.findHistory(chargerId, from, to, cursor, pageSize + 1);
        boolean hasNext = rows.size() > pageSize;
        List<ChargerStatusHistory> page = hasNext ? rows.subList(0, pageSize) : rows;
        Map<String, NormalizedStatus> codes = statusCodes.load();
        List<ChargerHistoryResponse.Item> items = page.stream()
                .map(h -> new ChargerHistoryResponse.Item(h.getStatusCode(),
                        codes.getOrDefault(h.getStatusCode().trim(), NormalizedStatus.UNKNOWN),
                        h.getStatusUpdatedAt(), h.getLastChargeStart(), h.getLastChargeEnd(),
                        h.getNowChargeStart(), h.getSourceApi(), h.getCollectedAt()))
                .toList();
        LocalDateTime next = hasNext ? page.get(page.size() - 1).getStatusUpdatedAt() : null;
        return new ChargerHistoryResponse(chargerId, from, to, pageSize, items, next);
    }

    private static StationSummary summarize(Station s, List<AccessPointView> accesses, List<StatusCountRow> rows) {
        int available = 0;
        int charging = 0;
        int unavailable = 0;
        int unknown = 0;
        LocalDateTime latest = null;
        for (StatusCountRow r : rows) {
            int n = r.count().intValue();
            NormalizedStatus status = r.status() == null ? NormalizedStatus.UNKNOWN : r.status();
            switch (status) {
                case AVAILABLE -> available += n;
                case CHARGING -> charging += n;
                case UNAVAILABLE -> unavailable += n;
                case UNKNOWN -> unknown += n;
            }
            if (r.latestUpdatedAt() != null && (latest == null || r.latestUpdatedAt().isAfter(latest))) {
                latest = r.latestUpdatedAt();
            }
        }
        return new StationSummary(s.getId(), s.getStatId(), s.getName(), s.getBusiId(), s.getLatitude(),
                s.getLongitude(), accesses, available + charging + unavailable + unknown, available, charging,
                unavailable, unknown, latest);
    }
}
