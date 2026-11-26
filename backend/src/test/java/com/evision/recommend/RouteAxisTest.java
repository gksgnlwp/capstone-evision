package com.evision.recommend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.evision.recommend.RecommendRepository.RoutePointRow;
import com.evision.recommend.RouteHeadingResolver.Heading;

/**
 * 노선 위 위치와 하행 방향 판단.
 * 시험 노선: 경도 127.0 을 따라 위도 35.0~37.5 남북으로 뻗은 직선. 하행은 남쪽으로 달린다.
 * 우측통행이라 남쪽으로 달릴 때 오른쪽(서쪽)에 하행 휴게소, 동쪽에 상행 휴게소가 있다.
 */
class RouteAxisTest {

    static List<RoutePointRow> northSouthRoute() {
        List<RoutePointRow> rows = new ArrayList<>();
        for (int i = 0; i <= 25; i++) {
            rows.add(new RoutePointRow(null, null, 35.0 + i * 0.1, 127.0));
        }
        for (double lat : new double[] {35.55, 36.25, 36.95}) {
            String name = "휴게소" + lat;
            rows.add(new RoutePointRow(name + "(부산)", "하행", lat, 126.996));
            rows.add(new RoutePointRow(name + "(서울)", "상행", lat + 0.004, 127.004));
        }
        return rows;
    }

    @Test
    void 노선_위_위치는_본선을_따라_잰_거리다() {
        RouteAxis axis = RouteAxis.of(northSouthRoute().stream()
                .map(r -> new RouteAxis.Point(r.latitude(), r.longitude())).toList());

        assertThat(axis.length()).isCloseTo(278, within(3.0));   // 휴게소 좌표를 지나며 조금 길어진다
        // 노선 옆으로 벗어난 지점도 수선을 내린 곳의 위치로 본다
        double a = axis.position(36.0, 127.0);
        double b = axis.position(36.0, 127.05);
        assertThat(b).isCloseTo(a, within(0.5));
    }

    @Test
    void 휴게소_상하행_쌍으로_하행_방향을_정한다() {
        RouteHeadingResolver.Oriented o = RouteHeadingResolver.orient(northSouthRoute());
        Heading down = new Heading(o.axis(), o.downSign());

        assertThat(down.aheadKm(36.0, 127.0, 35.5, 127.0)).isCloseTo(55.6, within(0.5));   // 남쪽은 앞
        assertThat(down.aheadKm(36.0, 127.0, 36.4, 127.0)).isCloseTo(-44.5, within(0.5));  // 북쪽은 뒤
    }

    @Test
    void 상하행_쌍이_엇갈리면_방향을_정하지_않는다() {
        List<RoutePointRow> rows = new ArrayList<>(northSouthRoute());
        rows.add(new RoutePointRow("반대(부산)", "하행", 35.25, 127.004));     // 원천 방향 값이 뒤바뀐 쌍
        rows.add(new RoutePointRow("반대(서울)", "상행", 35.254, 126.996));
        assertThat(RouteHeadingResolver.orient(rows)).isNotNull();             // 3:1 은 다수 쪽으로 정한다

        rows.add(new RoutePointRow("반대2(부산)", "하행", 36.65, 127.004));
        rows.add(new RoutePointRow("반대2(서울)", "상행", 36.654, 126.996));
        assertThat(RouteHeadingResolver.orient(rows)).isNull();                // 3:2 는 정하지 않는다
    }

    @Test
    void 순환선은_방향을_정하지_않는다() {
        List<RoutePointRow> rows = new ArrayList<>();
        for (int i = 0; i < 36; i++) {
            double t = Math.toRadians(i * 10);
            rows.add(new RoutePointRow(null, null, 37.5 + 0.2 * Math.sin(t), 127.0 + 0.25 * Math.cos(t)));
        }
        rows.add(new RoutePointRow("A(일산)", "하행", 37.7, 127.0));
        rows.add(new RoutePointRow("A(판교)", "상행", 37.69, 127.0));

        assertThat(RouteHeadingResolver.orient(rows)).isNull();
    }

    @Test
    void 이름이_같은_상행_하행_한_쌍만_쓴다() {
        List<RoutePointRow> rows = List.of(
                new RoutePointRow("죽암(부산)", "하행", 36.48, 127.43),
                new RoutePointRow("죽암(서울)", "상행", 36.49, 127.43),
                new RoutePointRow("옥천만남", "양방향", 36.3, 127.57),
                new RoutePointRow("안성(부산)", "하행", 37.0, 127.1));

        assertThat(RouteHeadingResolver.pairs(rows)).hasSize(1);
    }
}
