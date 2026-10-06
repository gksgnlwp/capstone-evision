package com.evision.recommend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.evision.common.code.NormalizedStatus;
import com.evision.common.time.TimeConfig;
import com.evision.recommend.AvailabilityPredictor.Basis;
import com.evision.recommend.RecommendRepository.OccupancyRow;
import com.evision.recommend.RecommendRepository.OutputRow;
import com.evision.reference.domain.Interchange;
import com.evision.reference.domain.RestArea;
import com.evision.reference.domain.Route;
import com.evision.station.domain.AccessType;
import com.evision.station.domain.Station;
import com.evision.station.domain.StationAccess;
import com.evision.station.query.StationQueryRepository;
import com.evision.station.query.StationQueryRepository.StatusCountRow;

/**
 * DB 없이 리포지토리를 대역으로 바꿔 추천 흐름을 검증한다. 현재 위치 (36.0, 127.0), 목적지 (37.0, 127.0) 북쪽.
 *
 * <pre>
 * S1 휴게소 (36.2, 127.0) 우회 0, 같은 충전소가 IC (36.6, 127.0)에도 매핑 → 가까운 휴게소로 본다. 지금 만차
 * S2 휴게소 (35.9, 127.0) 목적지 반대쪽 → 제외
 * S3 IC    (36.5, 127.0) 우회 2km. 지금 2대 가용, 출력 200kW
 * </pre>
 * 위도 0.1도 ≈ 11.12km, 도로 보정 1.3, 평균 80km/h.
 */
class RecommendServiceTest {

    static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 5, 13, 0);

    RecommendRepository repository = mock(RecommendRepository.class);
    StationQueryRepository stationQueryRepository = mock(StationQueryRepository.class);
    RecommendService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW.atZone(TimeConfig.KST).toInstant(), TimeConfig.KST);
        service = new RecommendService(repository, stationQueryRepository,
                new AvailabilityPredictor(AvailabilityPredictor.Settings.defaults()),
                new RecommendScorer(RecommendScorer.Settings.defaults()),
                new RecommendProperties(80, 1.3, 4), clock);

        Route route = mock(Route.class);
        when(route.getId()).thenReturn(1L);
        when(route.getRouteName()).thenReturn("경부선");

        Station s1 = station(1, "ST000001");
        Station s2 = station(2, "ST000002");
        Station s3 = station(3, "ST000003");
        List<StationAccess> accesses = List.of(
                restAreaAccess(s1, route, 36.2, 127.0, "0"),
                icAccess(s1, route, 36.6, 127.0, "0"),
                restAreaAccess(s2, route, 35.9, 127.0, "0"),
                icAccess(s3, route, 36.5, 127.0, "2"));
        when(repository.findCandidateAccesses(anyLong(), any(), any())).thenReturn(accesses);
        when(stationQueryRepository.countFastChargersByStatus(anyCollection())).thenReturn(List.of(
                new StatusCountRow(1L, NormalizedStatus.CHARGING, 2L, NOW),
                new StatusCountRow(3L, NormalizedStatus.AVAILABLE, 2L, NOW)));
        when(repository.maxFastOutput(anyCollection())).thenReturn(List.of(
                new OutputRow(1L, 100), new OutputRow(3L, 200)));
        when(repository.findOccupancy(anyCollection(), any())).thenReturn(List.of());
    }

    @Test
    void 진행_방향_후보를_도착_시_가용_확률로_정렬한다() {
        RecommendResponse r = service.recommend(condition(100, 37.0));

        assertThat(r.reachableKm()).isEqualTo(80.0);
        assertThat(r.candidateCount()).isEqualTo(2);   // S2는 목적지 반대쪽
        assertThat(r.unreachableCount()).isZero();
        assertThat(r.recommendations()).extracting(RecommendResponse.Item::statId)
                .containsExactly("ST000003", "ST000001");

        RecommendResponse.Item s3 = r.recommendations().get(0);
        assertThat(s3.rank()).isEqualTo(1);
        assertThat(s3.access().accessType()).isEqualTo(AccessType.IC);
        assertThat(s3.distanceKm()).isEqualTo(72.3);          // 55.6km × 1.3
        assertThat(s3.etaMinutes()).isEqualTo(56);            // (72.3 + 2) / 80 × 60
        assertThat(s3.eta()).isEqualTo(NOW.plusMinutes(56));
        assertThat(s3.realtimeProbability()).isEqualTo(1.0);
        assertThat(s3.basis()).isEqualTo(Basis.PRIOR);
        assertThat(s3.availableCount()).isEqualTo(2);
        assertThat(s3.maxOutputKw()).isEqualTo(200);

        RecommendResponse.Item s1 = r.recommendations().get(1);
        assertThat(s1.access().accessType()).isEqualTo(AccessType.REST_AREA);   // IC보다 가까운 휴게소
        assertThat(s1.access().restAreaName()).isEqualTo("휴게소1");
        assertThat(s1.distanceKm()).isEqualTo(28.9);
        assertThat(s1.realtimeProbability()).isEqualTo(0.0);
        assertThat(s1.availabilityProbability()).isLessThan(s3.availabilityProbability());
    }

    @Test
    void 목적지가_없으면_뒤쪽_후보도_본다() {
        RecommendResponse r = service.recommend(condition(100, null));

        assertThat(r.candidateCount()).isEqualTo(3);
    }

    @Test
    void 도달_거리를_넘는_후보는_제외하고_개수만_알려준다() {
        RecommendResponse r = service.recommend(condition(50, 37.0));   // 도달 가능 40km

        assertThat(r.recommendations()).extracting(RecommendResponse.Item::statId).containsExactly("ST000001");
        assertThat(r.unreachableCount()).isEqualTo(1);
    }

    @Test
    void 과거_같은_시간대_만차_이력이_있으면_확률이_내려간다() {
        LocalDateTime lastMonday = LocalDateTime.of(2026, 9, 28, 13, 30);   // S3 도착 13:56 → 13:30 슬롯
        when(repository.findOccupancy(anyCollection(), any())).thenReturn(List.of(
                new OccupancyRow(3L, lastMonday, false),
                new OccupancyRow(3L, lastMonday.minusWeeks(1), false),
                new OccupancyRow(3L, lastMonday.minusWeeks(2), false)));

        RecommendResponse.Item s3 = service.recommend(condition(100, 37.0)).recommendations().stream()
                .filter(i -> i.statId().equals("ST000003")).findFirst().orElseThrow();

        assertThat(s3.basis()).isEqualTo(Basis.SAME_DAY_SLOT);
        assertThat(s3.basisSampleCount()).isEqualTo(3);
        assertThat(s3.historicalProbability()).isLessThan(0.7);
    }

    @Test
    void 후보가_없으면_빈_목록() {
        when(repository.findCandidateAccesses(anyLong(), any(), any())).thenReturn(List.of());

        RecommendResponse r = service.recommend(condition(100, null));

        assertThat(r.recommendations()).isEmpty();
        assertThat(r.candidateCount()).isZero();
    }

    private static RecommendCondition condition(double rangeKm, Double destLat) {
        return RecommendCondition.of(36.0, 127.0, 1, null, rangeKm, destLat, destLat == null ? null : 127.0,
                null, null);
    }

    private static Station station(long id, String statId) {
        Station s = mock(Station.class);
        when(s.getId()).thenReturn(id);
        when(s.getStatId()).thenReturn(statId);
        when(s.getName()).thenReturn("충전소" + id);
        when(s.getLatitude()).thenReturn(36.0);
        when(s.getLongitude()).thenReturn(127.0);
        return s;
    }

    private static StationAccess restAreaAccess(Station s, Route route, double lat, double lng, String detour) {
        long id = s.getId();
        RestArea r = mock(RestArea.class);
        when(r.getId()).thenReturn(id);
        when(r.getName()).thenReturn("휴게소" + id);
        when(r.getDirection()).thenReturn("상행");
        when(r.getLatitude()).thenReturn(lat);
        when(r.getLongitude()).thenReturn(lng);
        when(r.getRoute()).thenReturn(route);
        StationAccess a = mock(StationAccess.class);
        when(a.getStation()).thenReturn(s);
        when(a.getAccessType()).thenReturn(AccessType.REST_AREA);
        when(a.getRestArea()).thenReturn(r);
        when(a.getDetourKm()).thenReturn(new BigDecimal(detour));
        return a;
    }

    private static StationAccess icAccess(Station s, Route route, double lat, double lng, String detour) {
        long id = s.getId();
        Interchange ic = mock(Interchange.class);
        when(ic.getId()).thenReturn(id);
        when(ic.getName()).thenReturn("IC" + id);
        when(ic.getLatitude()).thenReturn(lat);
        when(ic.getLongitude()).thenReturn(lng);
        when(ic.getRoute()).thenReturn(route);
        StationAccess a = mock(StationAccess.class);
        when(a.getStation()).thenReturn(s);
        when(a.getAccessType()).thenReturn(AccessType.IC);
        when(a.getInterchange()).thenReturn(ic);
        when(a.getDetourKm()).thenReturn(new BigDecimal(detour));
        return a;
    }
}
