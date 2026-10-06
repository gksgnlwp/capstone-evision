-- 추천 API 체험용 샘플 데이터 (로컬 DB 전용). 공공데이터 수집 없이 Swagger에서 추천을 볼 수 있게 한다.
-- 주의: 노선·휴게소·충전소·이력 테이블을 비우고 다시 채운다. 실제 수집 데이터가 있는 DB에서는 실행하지 말 것.
--
-- 시나리오: 경부선 대전 → 서울 (상행). 휴게소 좌표는 reference/rest_area.csv 실제 값, 충전소·충전기는 가짜.
--   DEMO0001 신탄진(서울)      급속 2 (지금 모두 충전중)        평소 자주 붐빔
--   DEMO0002 죽암(서울)        급속 4 200kW (지금 2대 가용)     평소 한산
--   DEMO0003 청주(서울)        급속 2 (1대 가용)                보통
--   DEMO0004 천안삼거리(서울)  급속 3 (충전중 2, 점검중 1)      평소 자주 붐빔
--   DEMO0005 입장거봉포도(서울) 급속 1 50kW (가용)               보통
--   DEMO0006 안성(서울)        급속 6 200kW (3대 가용)          보통, 주말 붐빔
--   DEMO0007 죽전(서울)        급속 2 (1대 가용)                한산
--   DEMO0008 죽암(부산), DEMO0009 옥산(부산)  하행 → direction=상행이면 제외
--   DEMO0010 옥천만남(양방향)  대전 남쪽 → 목적지를 주면 진행 반대쪽이라 제외
--   DEMO0011 남청주IC 우회 2.5km  급속 2 (가용)
--   DEMO0012 안성(서울) 완속만 → 추천 제외
-- 과거 4주 occupancy_30m: 충전소별 평소 가용 확률에 낮 시간(10~19시)·주말 혼잡을 더해 결정적으로 만든다.
-- AI 예측 occupancy_forecast (model_version = demo-v0): 지금부터 6시간, 세 곳만 넣는다 (나머지는 과거 통계로 대체되는 것을 보여 준다).
--   안성(서울) 0.20 (평소보다 붐빌 것으로 예측), 죽암(서울) 0.95, 청주(서울) 0.45
--   generated_at = 실행 시각이라 90분(evision.recommend.forecast-max-age)이 지나면 쓰이지 않는다. 다시 실행하면 갱신된다.

SET TIME ZONE 'Asia/Seoul';
BEGIN;

TRUNCATE charger_status_history, occupancy_30m, station_access, charger, station, collection_run,
         rest_area, interchange, route RESTART IDENTITY CASCADE;

INSERT INTO route (route_no, route_name) VALUES ('1', '경부선');

INSERT INTO rest_area (route_id, name, direction, latitude, longitude, ev_charger_yn)
SELECT r.route_id, v.name, v.direction, v.lat, v.lng, 'Y'
FROM route r, (VALUES
    ('신탄진(서울)',       '상행',   36.426834, 127.418438),
    ('죽암(서울)',         '상행',   36.496775, 127.430650),
    ('청주(서울)',         '상행',   36.716005, 127.349315),
    ('천안삼거리(서울)',   '상행',   36.787809, 127.173455),
    ('입장거봉포도(서울)', '상행',   36.942997, 127.192467),
    ('안성(서울)',         '상행',   37.076681, 127.132496),
    ('죽전(서울)',         '상행',   37.332371, 127.104795),
    ('죽암(부산)',         '하행',   36.486810, 127.429261),
    ('옥산(부산)',         '하행',   36.657743, 127.369817),
    ('옥천만남',           '양방향', 36.308718, 127.572308)
) AS v(name, direction, lat, lng);

INSERT INTO interchange (route_id, facility_code, name, facility_type, latitude, longitude)
SELECT route_id, '0010I00029', '남청주IC', 'IC', 36.534315, 127.432157 FROM route;

CREATE TEMP TABLE demo_station (
    stat_id text, name text, rest_area text, ic_code text, detour numeric, lat double precision,
    lng double precision, usual_available double precision) ON COMMIT DROP;
INSERT INTO demo_station VALUES
    ('DEMO0001', '신탄진휴게소(서울방향) 충전소',   '신탄진(서울)',       NULL, 0, 36.426834, 127.418438, 0.30),
    ('DEMO0002', '죽암휴게소(서울방향) 충전소',     '죽암(서울)',         NULL, 0, 36.496775, 127.430650, 0.92),
    ('DEMO0003', '청주휴게소(서울방향) 충전소',     '청주(서울)',         NULL, 0, 36.716005, 127.349315, 0.60),
    ('DEMO0004', '천안삼거리휴게소(서울방향) 충전소', '천안삼거리(서울)', NULL, 0, 36.787809, 127.173455, 0.35),
    ('DEMO0005', '입장거봉포도휴게소(서울방향) 충전소', '입장거봉포도(서울)', NULL, 0, 36.942997, 127.192467, 0.70),
    ('DEMO0006', '안성휴게소(서울방향) 충전소',     '안성(서울)',         NULL, 0, 37.076681, 127.132496, 0.60),
    ('DEMO0007', '죽전휴게소(서울방향) 충전소',     '죽전(서울)',         NULL, 0, 37.332371, 127.104795, 0.85),
    ('DEMO0008', '죽암휴게소(부산방향) 충전소',     '죽암(부산)',         NULL, 0, 36.486810, 127.429261, 0.80),
    ('DEMO0009', '옥산휴게소(부산방향) 충전소',     '옥산(부산)',         NULL, 0, 36.657743, 127.369817, 0.80),
    ('DEMO0010', '옥천만남의광장 충전소',           '옥천만남',           NULL, 0, 36.308718, 127.572308, 0.90),
    ('DEMO0011', '남청주IC 인근 충전소',            NULL, '0010I00029', 2.5, 36.540000, 127.455000, 0.85),
    ('DEMO0012', '안성휴게소(서울방향) 완속 충전소', '안성(서울)',        NULL, 0, 37.076681, 127.132496, 0.90);

INSERT INTO station (stat_id, name, address, latitude, longitude, zcode, busi_id, updated_at)
SELECT stat_id, name, '샘플 데이터', lat, lng, '43', 'DM', localtimestamp FROM demo_station;

INSERT INTO station_access (station_id, access_type, rest_area_id, detour_km, mapping_method, verified_at)
SELECT s.station_id, 'REST_AREA', ra.rest_area_id, 0, 'MANUAL', localtimestamp
FROM demo_station d JOIN station s USING (stat_id) JOIN rest_area ra ON ra.name = d.rest_area;

INSERT INTO station_access (station_id, access_type, ic_id, detour_km, mapping_method, verified_at)
SELECT s.station_id, 'IC', ic.ic_id, d.detour, 'MANUAL', localtimestamp
FROM demo_station d JOIN station s USING (stat_id) JOIN interchange ic ON ic.facility_code = d.ic_code;

-- 상태 코드: 2 사용가능, 3 충전중, 5 점검중
INSERT INTO charger (station_id, chger_id, charger_type, output_kw, is_fast,
                     current_status_code, current_normalized_status, current_status_updated_at)
SELECT s.station_id, v.chger_id, CASE WHEN v.fast THEN '04' ELSE '02' END, v.kw, v.fast,
       v.stat, cd.normalized_status, localtimestamp - interval '3 minutes'
FROM (VALUES
    ('DEMO0001', '01', 100, true, '3'), ('DEMO0001', '02', 100, true, '3'),
    ('DEMO0002', '01', 200, true, '2'), ('DEMO0002', '02', 200, true, '2'),
    ('DEMO0002', '03', 200, true, '3'), ('DEMO0002', '04', 200, true, '3'),
    ('DEMO0003', '01', 100, true, '2'), ('DEMO0003', '02', 100, true, '3'),
    ('DEMO0004', '01', 100, true, '3'), ('DEMO0004', '02', 100, true, '3'), ('DEMO0004', '03', 100, true, '5'),
    ('DEMO0005', '01',  50, true, '2'),
    ('DEMO0006', '01', 200, true, '2'), ('DEMO0006', '02', 200, true, '2'), ('DEMO0006', '03', 200, true, '2'),
    ('DEMO0006', '04', 200, true, '3'), ('DEMO0006', '05', 200, true, '3'), ('DEMO0006', '06', 200, true, '3'),
    ('DEMO0007', '01', 100, true, '2'), ('DEMO0007', '02', 100, true, '3'),
    ('DEMO0008', '01', 100, true, '2'),
    ('DEMO0009', '01', 100, true, '2'),
    ('DEMO0010', '01', 100, true, '2'),
    ('DEMO0011', '01', 100, true, '2'), ('DEMO0011', '02', 100, true, '2'),
    ('DEMO0012', '01',   7, false, '2')
) AS v(stat_id, chger_id, kw, fast, stat)
JOIN station s ON s.stat_id = v.stat_id
JOIN code_dictionary cd ON cd.code_group = 'CHARGER_STAT' AND cd.code = v.stat;

INSERT INTO occupancy_30m (station_id, window_start, fast_charger_count, occupancy_rate, available,
                           full_minutes, valid_minutes, observed_points, computed_at)
SELECT s.station_id, ws, fc.n,
       CASE WHEN a.available THEN 0.5 ELSE 1.0 END, a.available,
       CASE WHEN a.available THEN 0 ELSE 30 END, 30, 6, localtimestamp
FROM demo_station d
JOIN station s USING (stat_id)
CROSS JOIN LATERAL (SELECT count(*) AS n FROM charger c WHERE c.station_id = s.station_id AND c.is_fast) fc
CROSS JOIN generate_series(date_trunc('hour', localtimestamp) - interval '28 days',
                           date_trunc('hour', localtimestamp) - interval '30 minutes',
                           interval '30 minutes') AS ws
CROSS JOIN LATERAL (SELECT abs(hashtext(d.stat_id || ws::text)) % 100 < 100 * greatest(0.05, least(0.98,
           d.usual_available
           - CASE WHEN extract(hour FROM ws) BETWEEN 10 AND 19 THEN 0.15 ELSE 0 END
           - CASE WHEN extract(isodow FROM ws) >= 6 THEN 0.15 ELSE 0 END)) AS available) a
WHERE fc.n > 0;

INSERT INTO occupancy_forecast (station_id, target_window_start, p_available, model_version, generated_at)
SELECT s.station_id, w, v.p, 'demo-v0', localtimestamp
FROM (VALUES ('DEMO0006', 0.20), ('DEMO0002', 0.95), ('DEMO0003', 0.45)) AS v(stat_id, p)
JOIN station s USING (stat_id)
CROSS JOIN generate_series(date_trunc('hour', localtimestamp),
                           date_trunc('hour', localtimestamp) + interval '6 hours',
                           interval '30 minutes') AS w;

COMMIT;

SELECT (SELECT count(*) FROM station) AS stations, (SELECT count(*) FROM charger) AS chargers,
       (SELECT count(*) FROM occupancy_30m) AS occupancy_rows,
       (SELECT count(*) FROM occupancy_forecast) AS forecast_rows;
