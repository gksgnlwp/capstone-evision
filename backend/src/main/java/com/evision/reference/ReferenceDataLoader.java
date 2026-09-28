package com.evision.reference;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 명세 4.7 기준 데이터 적재. 모든 적재는 upsert라 다시 실행해도 결과가 같다.
 *
 * <ul>
 *   <li>route.csv: 노선번호별 대표 노선명 (두 원천에서 뽑아 사람이 검토하는 파일)</li>
 *   <li>rest_area.csv: 전국휴게소정보표준데이터 (공공데이터포털 15025446). 고속국도만 적재</li>
 *   <li>interchange.csv: 고속도로 출입시설 위치정보 (15112762). 노선코드 = 노선번호 × 10 + 구간번호</li>
 *   <li>station_access.csv: 사람이 검증한 충전소-접근지점 매핑. 이 파일이 MANUAL 매핑의 기준이다</li>
 * </ul>
 */
@Component
public class ReferenceDataLoader {

    private static final Logger log = LoggerFactory.getLogger(ReferenceDataLoader.class);

    static final String UPSERT_ROUTE = """
            INSERT INTO route (route_no, route_name) VALUES (?, ?)
            ON CONFLICT (route_no) DO UPDATE SET route_name = EXCLUDED.route_name
            """;

    static final String UPSERT_REST_AREA = """
            INSERT INTO rest_area (route_id, name, direction, latitude, longitude, ev_charger_yn)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (route_id, name, direction) DO UPDATE SET
              latitude = EXCLUDED.latitude, longitude = EXCLUDED.longitude, ev_charger_yn = EXCLUDED.ev_charger_yn
            """;

    static final String UPSERT_INTERCHANGE = """
            INSERT INTO interchange (route_id, facility_code, name, facility_type, latitude, longitude)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (facility_code) DO UPDATE SET
              route_id = EXCLUDED.route_id, name = EXCLUDED.name, facility_type = EXCLUDED.facility_type,
              latitude = EXCLUDED.latitude, longitude = EXCLUDED.longitude
            """;

    private final JdbcTemplate jdbc;
    private final ResourceLoader resourceLoader;
    private final ReferenceProperties properties;

    public ReferenceDataLoader(JdbcTemplate jdbc, ResourceLoader resourceLoader, ReferenceProperties properties) {
        this.jdbc = jdbc;
        this.resourceLoader = resourceLoader;
        this.properties = properties;
    }

    public record LoadReport(int routes, int restAreas, int restAreasSkipped, int interchanges,
            int interchangesSkipped, int accessUpserted, int accessRemoved) {
    }

    /** 설정된 위치의 CSV 네 개를 한 트랜잭션으로 적재한다. 매핑 파일에 오류가 하나라도 있으면 전체를 롤백한다. */
    @Transactional
    public LoadReport loadAll() {
        int routes = loadRoutes(readCsv("route.csv"));
        int[] restAreas = loadRestAreas(readCsv("rest_area.csv"));
        int[] interchanges = loadInterchanges(readCsv("interchange.csv"));
        int[] access = loadStationAccess(readCsv("station_access.csv"));
        LoadReport report = new LoadReport(routes, restAreas[0], restAreas[1], interchanges[0], interchanges[1],
                access[0], access[1]);
        log.info("기준 데이터 적재 완료: {}", report);
        return report;
    }

    int loadRoutes(List<Map<String, String>> rows) {
        List<Object[]> args = new ArrayList<>();
        for (Map<String, String> row : rows) {
            String routeNo = normalizeRouteNo(row.get("route_no"));
            String name = row.get("route_name");
            if (routeNo == null || name == null || name.isBlank()) {
                throw new ReferenceDataException("route.csv: route_no, route_name은 필수입니다: " + row);
            }
            args.add(new Object[] {routeNo, name});
        }
        jdbc.batchUpdate(UPSERT_ROUTE, args);
        return args.size();
    }

    /** @return [적재 건수, 건너뛴 건수] */
    int[] loadRestAreas(List<Map<String, String>> rows) {
        Map<String, Long> routeIds = routeIds();
        List<Object[]> args = new ArrayList<>();
        int skipped = 0;
        for (Map<String, String> row : rows) {
            if (!"고속국도".equals(row.get("ROAD_KND"))) {
                skipped++;
                continue;
            }
            Long routeId = routeIds.get(normalizeRouteNo(row.get("ROAD_ROUTE_NO")));
            Double lat = parseDouble(row.get("LATITUDE"));
            Double lng = parseDouble(row.get("LONGITUDE"));
            String name = blankToNull(row.get("ENTRPS_NM"));
            String direction = blankToNull(row.get("ROAD_ROUTE_DRC"));
            if (routeId == null || lat == null || lng == null || name == null || direction == null) {
                log.warn("휴게소 행을 건너뜁니다 (노선 미등록 또는 필수값 오류): {}", row);
                skipped++;
                continue;
            }
            String evYn = "Y".equalsIgnoreCase(row.get("ELCTY_YN")) ? "Y" : "N";
            args.add(new Object[] {routeId, name, direction, lat, lng, evYn});
        }
        jdbc.batchUpdate(UPSERT_REST_AREA, args);
        return new int[] {args.size(), skipped};
    }

    /** @return [적재 건수, 건너뛴 건수] */
    int[] loadInterchanges(List<Map<String, String>> rows) {
        Map<String, Long> routeIds = routeIds();
        List<Object[]> args = new ArrayList<>();
        int skipped = 0;
        for (Map<String, String> row : rows) {
            String code = blankToNull(row.get("IC/JC코드"));
            String name = blankToNull(row.get("IC/JC명"));
            Long routeId = routeIds.get(routeNoFromRouteCode(row.get("노선코드")));
            Double lng = parseDouble(row.get("X좌표값"));
            Double lat = parseDouble(row.get("Y좌표값"));
            if (code == null || name == null || routeId == null || lat == null || lng == null) {
                log.warn("출입시설 행을 건너뜁니다 (노선 미등록 또는 필수값 오류): {}", row);
                skipped++;
                continue;
            }
            args.add(new Object[] {routeId, code, name, facilityType(name), lat, lng});
        }
        jdbc.batchUpdate(UPSERT_INTERCHANGE, args);
        return new int[] {args.size(), skipped};
    }

    /**
     * 사람이 검증한 매핑을 적재한다. 파일이 MANUAL 매핑의 기준이므로, 파일에 없는 MANUAL 매핑은 지운다.
     * 한 행이라도 오류가 있으면 아무것도 바꾸지 않고 실패한다 (오류 목록을 모두 보여준다).
     *
     * @return [추가·갱신 건수, 삭제 건수]
     */
    int[] loadStationAccess(List<Map<String, String>> rows) {
        List<String> errors = new ArrayList<>();
        List<AccessRow> resolved = new ArrayList<>();
        Map<String, Long> stationIds = new HashMap<>();
        Map<String, Long> restAreaIds = idMap("SELECT name, rest_area_id FROM rest_area");
        Map<String, Long> icIds = idMap("SELECT facility_code, ic_id FROM interchange");

        int line = 1;
        for (Map<String, String> row : rows) {
            line++;
            String statId = blankToNull(row.get("stat_id"));
            String type = blankToNull(row.get("access_type"));
            String target = blankToNull(row.get("target_key"));
            Long stationId = statId == null ? null : stationIds.computeIfAbsent(statId, this::findStationId);
            Long restAreaId = null;
            Long icId = null;
            if ("REST_AREA".equals(type)) {
                restAreaId = target == null ? null : restAreaIds.get(target);
            } else if ("IC".equals(type)) {
                icId = target == null ? null : icIds.get(target);
            }
            BigDecimal detour = parseDecimal(row.get("detour_km"));
            LocalDateTime verifiedAt = parseDate(row.get("verified_at"));

            if (stationId == null) {
                errors.add(line + "행: 충전소를 찾을 수 없습니다 (stat_id=" + statId + ")");
            } else if (!"REST_AREA".equals(type) && !"IC".equals(type)) {
                errors.add(line + "행: access_type은 REST_AREA 또는 IC여야 합니다 (" + type + ")");
            } else if (restAreaId == null && icId == null) {
                errors.add(line + "행: 접근지점을 찾을 수 없습니다 (" + type + " " + target + ")");
            } else if (detour == null) {
                errors.add(line + "행: detour_km가 숫자가 아닙니다 (" + row.get("detour_km") + ")");
            } else if (verifiedAt == null) {
                errors.add(line + "행: verified_at(yyyy-MM-dd)이 필요합니다. 사람이 검증한 매핑만 적재합니다");
            } else {
                resolved.add(new AccessRow(stationId, type, restAreaId, icId, detour, verifiedAt));
            }
        }
        if (!errors.isEmpty()) {
            throw new ReferenceDataException("station_access.csv 오류 " + errors.size() + "건:\n  " + String.join("\n  ", errors));
        }

        Set<List<Object>> keep = new HashSet<>();
        for (AccessRow r : resolved) {
            keep.add(r.key());
            int updated = jdbc.update("""
                    UPDATE station_access SET detour_km = ?, verified_at = ?, mapping_method = 'MANUAL'
                    WHERE station_id = ? AND access_type = ?
                      AND rest_area_id IS NOT DISTINCT FROM ? AND ic_id IS NOT DISTINCT FROM ?""",
                    r.detourKm(), r.verifiedAt(), r.stationId(), r.accessType(), r.restAreaId(), r.icId());
            if (updated == 0) {
                jdbc.update("""
                        INSERT INTO station_access (station_id, access_type, rest_area_id, ic_id, detour_km, mapping_method, verified_at)
                        VALUES (?, ?, ?, ?, ?, 'MANUAL', ?)""",
                        r.stationId(), r.accessType(), r.restAreaId(), r.icId(), r.detourKm(), r.verifiedAt());
            }
        }

        List<Long> stale = new ArrayList<>();
        jdbc.query("SELECT access_id, station_id, access_type, rest_area_id, ic_id FROM station_access WHERE mapping_method = 'MANUAL'",
                rs -> {
                    List<Object> key = List.of(rs.getLong(2), rs.getString(3),
                            rs.getObject(4) == null ? "" : rs.getLong(4), rs.getObject(5) == null ? "" : rs.getLong(5));
                    if (!keep.contains(key)) {
                        stale.add(rs.getLong(1));
                    }
                });
        stale.forEach(id -> jdbc.update("DELETE FROM station_access WHERE access_id = ?", id));
        return new int[] {resolved.size(), stale.size()};
    }

    private record AccessRow(long stationId, String accessType, Long restAreaId, Long icId, BigDecimal detourKm,
            LocalDateTime verifiedAt) {
        List<Object> key() {
            return List.of(stationId, accessType, restAreaId == null ? "" : restAreaId, icId == null ? "" : icId);
        }
    }

    /** IC 노선코드(예: 0010, 0207, 4510) → 노선번호(1, 20, 451). 마지막 자리는 구간번호다. */
    static String routeNoFromRouteCode(String routeCode) {
        String code = blankToNull(routeCode);
        if (code == null) {
            return null;
        }
        try {
            return String.valueOf(Integer.parseInt(code) / 10);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 명칭이 JCT로 끝나면 JCT, 아니면 IC */
    static String facilityType(String name) {
        return name.toUpperCase().endsWith("JCT") ? "JCT" : "IC";
    }

    /** "0010" 같은 앞자리 0을 없앤다 */
    static String normalizeRouteNo(String routeNo) {
        String v = blankToNull(routeNo);
        if (v == null) {
            return null;
        }
        try {
            return String.valueOf(Integer.parseInt(v));
        } catch (NumberFormatException e) {
            return v;
        }
    }

    private Map<String, Long> routeIds() {
        return idMap("SELECT route_no, route_id FROM route");
    }

    private Map<String, Long> idMap(String sql) {
        Map<String, Long> map = new HashMap<>();
        jdbc.query(sql, rs -> {
            map.put(rs.getString(1).trim(), rs.getLong(2));
        });
        return map;
    }

    private Long findStationId(String statId) {
        List<Long> ids = jdbc.queryForList("SELECT station_id FROM station WHERE stat_id = ?", Long.class, statId);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private List<Map<String, String>> readCsv(String fileName) {
        Resource resource = resourceLoader.getResource(properties.location() + fileName);
        if (!resource.exists()) {
            log.warn("기준 데이터 파일이 없어 건너뜁니다: {}", fileName);
            return List.of();
        }
        try (InputStream in = resource.getInputStream()) {
            return CsvReader.read(in);
        } catch (IOException e) {
            throw new ReferenceDataException(fileName + " 읽기 실패: " + e.getMessage());
        }
    }

    private static Double parseDouble(String value) {
        String v = blankToNull(value);
        if (v == null) {
            return null;
        }
        try {
            double d = Double.parseDouble(v);
            return Double.isFinite(d) ? d : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static BigDecimal parseDecimal(String value) {
        String v = blankToNull(value);
        if (v == null) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static LocalDateTime parseDate(String value) {
        String v = blankToNull(value);
        if (v == null) {
            return null;
        }
        try {
            return v.length() <= 10 ? LocalDate.parse(v).atStartOfDay() : LocalDateTime.parse(v);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
