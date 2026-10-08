package com.evision.reference;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * IC 인근 충전소 자동 매핑 (FR-46, 요구사항분석서 8.3.6).
 *
 * <p>휴게소에 매핑되지 않은 충전소 중 공용(이용 제한 없음, 공동주택 제외)·24시간·급속충전기 보유 충전소를
 * IC(분기점 JCT 제외)마다 직선거리가 가까운 순으로 최대 N곳 매핑한다. 우회거리는 직선거리의 2배로 근사한다.
 * 기존 AUTO 매핑만 지우고 다시 만든다. 사람이 넣은 MANUAL 매핑은 건드리지 않는다.
 */
@Component
public class IcAutoMapper {

    static final String DELETE_AUTO = "DELETE FROM station_access WHERE access_type = 'IC' AND mapping_method = 'AUTO'";

    /** 위경도 상자로 먼저 좁힌 뒤 하버사인 거리로 거른다. 같은 (충전소, IC)의 MANUAL 매핑이 있으면 건너뛴다. */
    static final String INSERT_AUTO = """
            INSERT INTO station_access (station_id, access_type, ic_id, detour_km, mapping_method)
            SELECT station_id, 'IC', ic_id, ROUND((km * 2)::numeric, 2), 'AUTO'
            FROM (
                SELECT i.ic_id, s.station_id, d.km,
                       ROW_NUMBER() OVER (PARTITION BY i.ic_id ORDER BY d.km, s.station_id) AS rn
                FROM interchange i
                JOIN station s
                  ON s.latitude  BETWEEN i.latitude  - ? / 111.0 AND i.latitude  + ? / 111.0
                 AND s.longitude BETWEEN i.longitude - ? / (111.0 * COS(RADIANS(i.latitude)))
                                     AND i.longitude + ? / (111.0 * COS(RADIANS(i.latitude)))
                CROSS JOIN LATERAL (
                    SELECT 6371 * 2 * ASIN(SQRT(
                        POWER(SIN(RADIANS(s.latitude - i.latitude) / 2), 2)
                        + COS(RADIANS(i.latitude)) * COS(RADIANS(s.latitude))
                          * POWER(SIN(RADIANS(s.longitude - i.longitude) / 2), 2))) AS km
                ) d
                WHERE i.facility_type = 'IC'
                  AND s.del_yn = 'N'
                  AND COALESCE(s.limit_yn, 'N') = 'N'
                  AND s.name !~* ?
                  AND s.use_time LIKE '%24%'
                  AND EXISTS (SELECT 1 FROM charger c
                              WHERE c.station_id = s.station_id AND c.is_fast AND c.del_yn = 'N')
                  AND NOT EXISTS (SELECT 1 FROM station_access a
                                  WHERE a.station_id = s.station_id AND a.access_type = 'REST_AREA')
                  AND d.km <= ?
            ) candidate
            WHERE rn <= ?
            ON CONFLICT (station_id, ic_id) WHERE access_type = 'IC' DO NOTHING
            """;

    private final JdbcTemplate jdbc;
    private final ReferenceProperties properties;

    public IcAutoMapper(JdbcTemplate jdbc, ReferenceProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    public record Result(int removed, int mapped) {
    }

    /** 호출하는 쪽의 트랜잭션 안에서 실행된다 (적재 전체가 한 트랜잭션). 꺼져 있으면 기존 AUTO 매핑을 그대로 둔다. */
    public Result rebuild() {
        if (!properties.icAutoMapping()) {
            return new Result(0, 0);
        }
        double r = properties.icRadiusKm();
        int removed = jdbc.update(DELETE_AUTO);
        int mapped = jdbc.update(INSERT_AUTO, r, r, r, r, properties.icExcludeNamePattern(), r,
                properties.icMaxPerIc());
        return new Result(removed, mapped);
    }
}
