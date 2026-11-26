package com.evision.recommend;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import com.evision.recommend.RecommendRepository.RoutePointRow;
import com.evision.recommend.RouteAxis.DirectionPair;
import com.evision.recommend.RouteAxis.Point;

/**
 * 노선·진행방향(상행/하행)으로 "앞쪽"이 어느 쪽인지 알려 준다. 노선 모양과 하행 방향은 기준 데이터(IC·휴게소)로
 * 계산하고 노선별로 캐시한다 (기준 데이터는 앱 시작 때만 적재된다).
 */
@Component
public class RouteHeadingResolver {

    static final String UP = "상행";
    static final String DOWN = "하행";

    private final RecommendRepository repository;
    private final Map<Long, Optional<Oriented>> cache = new ConcurrentHashMap<>();

    public RouteHeadingResolver(RecommendRepository repository) {
        this.repository = repository;
    }

    /** 진행방향 판단. 방향이 상행/하행이 아니거나 노선 방향을 정할 수 없으면 null (앞뒤를 거르지 않는다) */
    public Heading heading(long routeId, String direction) {
        if (!UP.equals(direction) && !DOWN.equals(direction)) {
            return null;
        }
        Optional<Oriented> oriented = cache.get(routeId);
        if (oriented == null) {
            List<RoutePointRow> rows = repository.findRoutePoints(routeId);
            if (rows.isEmpty()) {
                return null;                                   // 기준 데이터 적재 전: 캐시하지 않는다
            }
            oriented = Optional.ofNullable(orient(rows));
            cache.put(routeId, oriented);
        }
        return oriented.map(o -> new Heading(o.axis(), DOWN.equals(direction) ? o.downSign() : -o.downSign()))
                .orElse(null);
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
