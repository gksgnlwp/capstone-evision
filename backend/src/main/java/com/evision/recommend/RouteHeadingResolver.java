package com.evision.recommend;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.evision.recommend.RecommendRepository.RoutePointRow;
import com.evision.recommend.RecommendRepository.RouteRow;
import com.evision.recommend.RouteAxis.DirectionPair;
import com.evision.recommend.RouteAxis.Point;

/**
 * 노선 모양·방향 정보. 노선 모양과 하행 방향은 기준 데이터(IC·휴게소)로 계산하고 한 번만 만든다
 * (기준 데이터는 앱 시작 때만 적재된다).
 *
 * <ul>
 * <li>{@link #heading}: 노선·진행방향(상행/하행)으로 "앞쪽"이 어느 쪽인지</li>
 * <li>{@link #choose}: 출발지·목적지로 이용할 노선과 진행방향 자동 선택</li>
 * </ul>
 */
@Component
public class RouteHeadingResolver {

    static final String UP = "상행";
    static final String DOWN = "하행";

    /** 출발지·목적지가 노선에서 이 거리 안이어야 그 노선을 탈 수 있다고 본다 */
    static final double MAX_ACCESS_KM = 30;
    /** 노선 위로 이 거리 이상 움직여야 그 노선을 이용하는 경로로 본다 */
    static final double MIN_TRIP_KM = 5;

    private final RecommendRepository repository;
    private volatile Map<Long, RouteInfo> routes;

    public RouteHeadingResolver(RecommendRepository repository) {
        this.repository = repository;
    }

    /** 진행방향 판단. 방향이 상행/하행이 아니거나 노선 방향을 정할 수 없으면 null (앞뒤를 거르지 않는다) */
    public Heading heading(long routeId, String direction) {
        if (!UP.equals(direction) && !DOWN.equals(direction)) {
            return null;
        }
        RouteInfo info = routes().get(routeId);
        if (info == null || info.oriented() == null) {
            return null;
        }
        Oriented o = info.oriented();
        return new Heading(o.axis(), DOWN.equals(direction) ? o.downSign() : -o.downSign());
    }

    /** 노선 이름 (없으면 null) */
    public RouteRow route(long routeId) {
        RouteInfo info = routes().get(routeId);
        return info == null ? null : info.route();
    }

    /**
     * 출발지·목적지를 둘 다 지나는 노선 중 두 지점이 노선에 가장 가까운 것을 고르고,
     * 목적지가 앞쪽이 되는 방향을 정한다. 마땅한 노선이 없으면 null.
     * 노선을 갈아타는 경로는 다루지 않는다.
     */
    public Choice choose(double fromLat, double fromLng, double toLat, double toLng) {
        Choice best = null;
        for (RouteInfo info : routes().values()) {
            Oriented o = info.oriented();
            if (o == null) {
                continue;
            }
            double fromOffset = o.axis().offset(fromLat, fromLng);
            double toOffset = o.axis().offset(toLat, toLng);
            double move = o.axis().position(toLat, toLng) - o.axis().position(fromLat, fromLng);
            if (fromOffset > MAX_ACCESS_KM || toOffset > MAX_ACCESS_KM || Math.abs(move) < MIN_TRIP_KM) {
                continue;
            }
            Choice c = new Choice(info.route(), move * o.downSign() > 0 ? DOWN : UP, fromOffset + toOffset,
                    Math.abs(move));
            if (best == null || c.accessKm() < best.accessKm()) {
                best = c;
            }
        }
        return best;
    }

    /** 출발지·목적지로 고른 노선. accessKm = 두 지점에서 노선까지 거리의 합, tripKm = 노선 위 이동 거리 */
    public record Choice(RouteRow route, String direction, double accessKm, double tripKm) {
    }

    private Map<Long, RouteInfo> routes() {
        Map<Long, RouteInfo> loaded = routes;
        if (loaded == null) {
            synchronized (this) {
                loaded = routes;
                if (loaded == null) {
                    loaded = load();
                    if (!loaded.isEmpty()) {
                        routes = loaded;               // 기준 데이터 적재 전이면 캐시하지 않는다
                    }
                }
            }
        }
        return loaded;
    }

    private Map<Long, RouteInfo> load() {
        Map<Long, List<RoutePointRow>> points = repository.findAllRoutePoints().stream()
                .collect(Collectors.groupingBy(RoutePointRow::routeId));
        Map<Long, RouteInfo> result = new LinkedHashMap<>();
        repository.findRoutes().stream().sorted(Comparator.comparingLong(RouteRow::routeId)).forEach(r ->
                result.put(r.routeId(), new RouteInfo(r, orient(points.getOrDefault(r.routeId(), List.of())))));
        return result;
    }

    /** 하행 방향을 정할 수 없으면 null */
    static Oriented orient(List<RoutePointRow> rows) {
        RouteAxis axis = RouteAxis.of(rows.stream().map(r -> new Point(r.latitude(), r.longitude())).toList());
        if (axis == null) {
            return null;
        }
        int sign = axis.downstreamSign(pairs(rows));
        return sign == 0 ? null : new Oriented(axis, sign);
    }

    /** 이름(괄호 앞)이 같은 휴게소가 상행·하행 하나씩 있으면 한 쌍 */
    static List<DirectionPair> pairs(List<RoutePointRow> rows) {
        Map<String, Point[]> byName = new HashMap<>();
        Map<String, int[]> counts = new HashMap<>();
        for (RoutePointRow r : rows) {
            int side = UP.equals(r.direction()) ? 0 : DOWN.equals(r.direction()) ? 1 : -1;
            if (side < 0 || r.name() == null) {
                continue;
            }
            String base = r.name().replaceAll("\\(.*\\)", "").trim();
            byName.computeIfAbsent(base, k -> new Point[2])[side] = new Point(r.latitude(), r.longitude());
            counts.computeIfAbsent(base, k -> new int[2])[side]++;
        }
        List<DirectionPair> result = new ArrayList<>();
        byName.forEach((name, p) -> {
            int[] c = counts.get(name);
            if (c[0] == 1 && c[1] == 1) {
                result.add(new DirectionPair(p[0], p[1]));
            }
        });
        return result;
    }

    record RouteInfo(RouteRow route, Oriented oriented) {
    }

    record Oriented(RouteAxis axis, int downSign) {
    }

    /** 진행방향. sign 이 +1 이면 노선 위 위치가 커지는 쪽으로 달린다 */
    public record Heading(RouteAxis axis, int sign) {

        /** from 에서 to 까지 진행방향으로 얼마나 앞인지 (km, 노선 위 거리). 음수면 뒤 */
        public double aheadKm(double fromLat, double fromLng, double toLat, double toLng) {
            return sign * (axis.position(toLat, toLng) - axis.position(fromLat, fromLng));
        }
    }
}
