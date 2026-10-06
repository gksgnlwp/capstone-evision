-- 추천 API(GET /api/recommendations) 측정 쿼리. 앱이 실제로 실행하는 SQL(Hibernate 로그)을 그대로 옮겼다.
-- 값은 run_recommend_perf.sh가 psql 변수로 넘긴다.
\pset pager off
SET statement_timeout = :'timeout';
SET max_parallel_workers_per_gather = 2;

\echo '### R1 후보: 노선 위 접근지점 + 충전소'
EXPLAIN (ANALYZE, BUFFERS)
select sa1_0.access_id,sa1_0.access_type,sa1_0.detour_km,i1_0.ic_id,i1_0.facility_code,i1_0.facility_type,i1_0.latitude,i1_0.longitude,i1_0.name,i1_0.route_id,r2_0.route_id,r2_0.route_name,r2_0.route_no,sa1_0.mapping_method,ra1_0.rest_area_id,ra1_0.direction,ra1_0.ev_charger_yn,ra1_0.latitude,ra1_0.longitude,ra1_0.name,ra1_0.route_id,r1_0.route_id,r1_0.route_name,r1_0.route_no,sa1_0.station_id,s1_0.station_id,s1_0.address,s1_0.address_detail,s1_0.busi_id,s1_0.del_yn,s1_0.kind,s1_0.kind_detail,s1_0.latitude,s1_0.limit_detail,s1_0.limit_yn,s1_0.longitude,s1_0.name,s1_0.operator_call,s1_0.operator_name,s1_0.org_name,s1_0.parking_free_yn,s1_0.stat_id,s1_0.updated_at,s1_0.use_time,s1_0.zcode,s1_0.zscode,sa1_0.verified_at
from station_access sa1_0 join station s1_0 on s1_0.station_id=sa1_0.station_id
left join rest_area ra1_0 on ra1_0.rest_area_id=sa1_0.rest_area_id left join route r1_0 on r1_0.route_id=ra1_0.route_id
left join interchange i1_0 on i1_0.ic_id=sa1_0.ic_id left join route r2_0 on r2_0.route_id=i1_0.route_id
where s1_0.del_yn='N' and (r1_0.route_id=:route_id or r2_0.route_id=:route_id)
  and exists(select 1 from charger c1_0 where c1_0.station_id=s1_0.station_id and c1_0.del_yn='N' and c1_0.is_fast=true)
order by s1_0.station_id,sa1_0.access_id;

\echo '### R2 후보 충전소 급속 상태 집계'
EXPLAIN (ANALYZE, BUFFERS)
select c1_0.station_id,c1_0.current_normalized_status,count(c1_0.charger_id),max(c1_0.current_status_updated_at)
from charger c1_0 where c1_0.station_id in (:ids) and c1_0.is_fast=true and c1_0.del_yn='N'
group by c1_0.station_id,c1_0.current_normalized_status;

\echo '### R3 후보 충전소 급속 최대 출력'
EXPLAIN (ANALYZE, BUFFERS)
select c1_0.station_id,max(c1_0.output_kw)
from charger c1_0 where c1_0.station_id in (:ids) and c1_0.is_fast=true and c1_0.del_yn='N'
group by c1_0.station_id;

\echo '### R4 점유율 이력 (최근 4주)'
EXPLAIN (ANALYZE, BUFFERS)
select o1_0.station_id,o1_0.window_start,o1_0.available
from occupancy_30m o1_0 where o1_0.station_id in (:ids) and o1_0.window_start>=:'history_from';

\echo '### R5 AI 예측 (도착 구간)'
EXPLAIN (ANALYZE, BUFFERS)
select of1_0.station_id,of1_0.target_window_start,of1_0.p_available,of1_0.model_version
from occupancy_forecast of1_0
where of1_0.station_id in (:ids) and of1_0.target_window_start between :'window_from' and :'window_to'
  and of1_0.generated_at>=:'generated_after';

\echo '### R4b 과거 통계 집계 (개선: DB에서 요일·도착 슬롯 단위로 센다)'
EXPLAIN (ANALYZE, BUFFERS)
WITH target AS (
  SELECT * FROM unnest(CAST(ARRAY[:ids] AS bigint[]),
                       array_fill(CAST(:'slot' AS text), ARRAY[cardinality(ARRAY[:ids])])) AS t(station_id, slot)
)
SELECT o.station_id,
       CAST(extract(isodow FROM o.window_start) AS int)    AS dow,
       CAST(o.window_start AS time) = CAST(t.slot AS time) AS same_slot,
       count(o.available)                                  AS n,
       count(*) FILTER (WHERE o.available)                 AS hits
FROM target t
JOIN occupancy_30m o ON o.station_id = t.station_id
WHERE o.window_start >= :'history_from'
GROUP BY o.station_id, dow, same_slot;
