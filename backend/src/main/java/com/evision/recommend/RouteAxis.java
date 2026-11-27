package com.evision.recommend;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

/**
 * 노선 위 위치(기점부터의 거리 근사). 노선 이정 데이터가 없어서 노선의 IC·휴게소 좌표로 노선 모양을 근사한다.
 *
 * <p>좌표들을 최소 신장 트리로 잇고 그 트리에서 가장 긴 경로를 노선 본선으로 본다 (지선·JCT 좌표는 곁가지로 빠진다).
 * 임의 지점의 노선 위 위치는 본선 꺾은선에 수선을 내린 점까지의 꺾은선 길이(km)다.
 *
 * <p>하행이 위치가 커지는 쪽인지는 휴게소 상·하행 쌍으로 정한다. 우측통행이라 하행 휴게소는 하행 진행방향의 오른쪽에 있다.
 * 원천 방향 값이 섞여 있는 노선이 있어 쌍마다 투표하고, 한쪽이 반대쪽의 3배 이상일 때만 방향을 정한다.
 */
final class RouteAxis {

    record Point(double lat, double lng) {
    }

    /** 이름이 같은 휴게소의 상행·하행 위치 */
    record DirectionPair(Point up, Point down) {
    }

    private static final double KM_PER_DEGREE = 111.2;
    /** 쌍 주변 노선 방향을 구할 때 쓰는 반경 */
    private static final double TANGENT_RADIUS_KM = 12;
    /** 상·하행 휴게소가 노선 양옆으로 이만큼은 떨어져 있어야 투표에 쓴다 */
    private static final double MIN_SIDE_OFFSET_KM = 0.03;
    /** 본선 양 끝이 길이의 이 비율보다 가까우면 순환선으로 보고 방향을 정하지 않는다 */
    private static final double LOOP_RATIO = 0.25;

    private final double cosLat;
    private final double[][] line;
    private final double[] cumulative;
    private final double[][] all;

    private RouteAxis(double cosLat, double[][] line, double[] cumulative, double[][] all) {
        this.cosLat = cosLat;
        this.line = line;
        this.cumulative = cumulative;
        this.all = all;
    }

    /** 좌표가 3개 미만이면 null */
    static RouteAxis of(List<Point> points) {
        if (points.size() < 3) {
            return null;
        }
        double cosLat = Math.cos(Math.toRadians(points.stream().mapToDouble(Point::lat).average().orElseThrow()));
        double[][] p = points.stream().map(q -> xy(q, cosLat)).toArray(double[][]::new);
        List<List<Integer>> tree = minimumSpanningTree(p);
        int a = farthest(tree, p, 0)[0];
        int[] far = farthest(tree, p, a);
        List<Integer> path = new ArrayList<>();
        for (int v = far[0]; v != -1; v = far[1 + v]) {
            path.add(v);
        }
        double[][] line = path.stream().map(i -> p[i]).toArray(double[][]::new);
        double[] cum = new double[line.length];
        for (int i = 1; i < line.length; i++) {
            cum[i] = cum[i - 1] + dist(line[i - 1], line[i]);
        }
        return new RouteAxis(cosLat, line, cum, p);
    }

    /** 본선 위 위치 (km) */
    double position(double lat, double lng) {
        return position(xy(new Point(lat, lng), cosLat));
    }

    /** 본선까지의 거리 (km) */
    double offset(double lat, double lng) {
        return project(xy(new Point(lat, lng), cosLat))[1];
    }

    double length() {
        return cumulative[cumulative.length - 1];
    }

    /**
     * 하행이 위치가 커지는 쪽이면 +1, 작아지는 쪽이면 -1, 판단할 수 없으면 0 (순환선, 쌍 부족, 투표가 엇갈림).
     */
    int downstreamSign(List<DirectionPair> pairs) {
        if (length() == 0 || dist(line[0], line[line.length - 1]) < LOOP_RATIO * length()) {
            return 0;
        }
        int plus = 0;
        int minus = 0;
        for (DirectionPair pair : pairs) {
            double[] up = xy(pair.up(), cosLat);
            double[] down = xy(pair.down(), cosLat);
            double[] center = {(up[0] + down[0]) / 2, (up[1] + down[1]) / 2};
            double[] t = localTangent(center);
            if (t == null) {
                continue;
            }
            double[] ahead = {center[0] + 3 * t[0], center[1] + 3 * t[1]};
            double[] behind = {center[0] - 3 * t[0], center[1] - 3 * t[1]};
            if (position(ahead) < position(behind)) {
                t = new double[] {-t[0], -t[1]};
            }
            // t 는 위치가 커지는 방향. 그 오른쪽은 (t.y, -t.x)
            double side = (down[0] - up[0]) * t[1] - (down[1] - up[1]) * t[0];
            if (Math.abs(side) < MIN_SIDE_OFFSET_KM) {
                continue;
            }
            if (side > 0) {
                plus++;
            } else {
                minus++;
            }
        }
        int win = Math.max(plus, minus);
        int lose = Math.min(plus, minus);
        if (win == 0 || win < 3 * lose) {
            return 0;
        }
        return plus > minus ? 1 : -1;
    }

    private double position(double[] q) {
        return project(q)[0];
    }

    /** [본선 위 위치, 본선까지 거리] */
    private double[] project(double[] q) {
        double bestDist = Double.MAX_VALUE;
        double best = 0;
        for (int i = 0; i + 1 < line.length; i++) {
            double dx = line[i + 1][0] - line[i][0];
            double dy = line[i + 1][1] - line[i][1];
            double l2 = dx * dx + dy * dy;
            double t = l2 == 0 ? 0 : Math.max(0, Math.min(1, ((q[0] - line[i][0]) * dx + (q[1] - line[i][1]) * dy) / l2));
            double d = Math.hypot(line[i][0] + t * dx - q[0], line[i][1] + t * dy - q[1]);
            if (d < bestDist) {
                bestDist = d;
                best = cumulative[i] + t * Math.sqrt(l2);
            }
        }
        return new double[] {best, bestDist};
    }

    /** center 주변 좌표들의 주성분 방향 (단위 벡터). 주변 좌표가 3개 미만이면 null */
    private double[] localTangent(double[] center) {
        List<double[]> near = Arrays.stream(all).filter(q -> dist(q, center) < TANGENT_RADIUS_KM).toList();
        if (near.size() < 3) {
            return null;
        }
        double mx = near.stream().mapToDouble(q -> q[0]).average().orElseThrow();
        double my = near.stream().mapToDouble(q -> q[1]).average().orElseThrow();
        double sxx = 0;
        double syy = 0;
        double sxy = 0;
        for (double[] q : near) {
            sxx += (q[0] - mx) * (q[0] - mx);
            syy += (q[1] - my) * (q[1] - my);
            sxy += (q[0] - mx) * (q[1] - my);
        }
        double angle = 0.5 * Math.atan2(2 * sxy, sxx - syy);
        return new double[] {Math.cos(angle), Math.sin(angle)};
    }

    private static List<List<Integer>> minimumSpanningTree(double[][] p) {
        int n = p.length;
        List<List<Integer>> adj = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            adj.add(new ArrayList<>());
        }
        boolean[] in = new boolean[n];
        double[] best = new double[n];
        int[] parent = new int[n];
        Arrays.fill(best, Double.MAX_VALUE);
        best[0] = 0;
        for (int k = 0; k < n; k++) {
            int u = -1;
            for (int i = 0; i < n; i++) {
                if (!in[i] && (u == -1 || best[i] < best[u])) {
                    u = i;
                }
            }
            in[u] = true;
            if (k > 0) {
                adj.get(u).add(parent[u]);
                adj.get(parent[u]).add(u);
            }
            for (int v = 0; v < n; v++) {
                double d = dist(p[u], p[v]);
                if (!in[v] && d < best[v]) {
                    best[v] = d;
                    parent[v] = u;
                }
            }
        }
        return adj;
    }

    /** start 에서 트리로 가장 먼 점. 반환 [가장 먼 점, 점 0의 이전 점, 점 1의 이전 점, ...] (이전 점 없으면 -1) */
    private static int[] farthest(List<List<Integer>> tree, double[][] p, int start) {
        int n = p.length;
        double[] d = new double[n];
        int[] result = new int[n + 1];
        Arrays.fill(result, -1);
        boolean[] seen = new boolean[n];
        Deque<Integer> stack = new ArrayDeque<>();
        stack.push(start);
        seen[start] = true;
        int far = start;
        while (!stack.isEmpty()) {
            int u = stack.pop();
            if (d[u] > d[far]) {
                far = u;
            }
            for (int v : tree.get(u)) {
                if (!seen[v]) {
                    seen[v] = true;
                    d[v] = d[u] + dist(p[u], p[v]);
                    result[1 + v] = u;
                    stack.push(v);
                }
            }
        }
        result[0] = far;
        return result;
    }

    private static double[] xy(Point q, double cosLat) {
        return new double[] {q.lng() * cosLat * KM_PER_DEGREE, q.lat() * KM_PER_DEGREE};
    }

    private static double dist(double[] a, double[] b) {
        return Math.hypot(a[0] - b[0], a[1] - b[1]);
    }
}
