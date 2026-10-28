-- 측정 대상 쿼리 (명세 9장). 조회 API는 앱이 실제로 실행하는 SQL(Hibernate 로그)을 그대로 옮겼고,
-- 집계는 OccupancyAggregationRepository의 SQL이다. 값은 run_perf.sh가 psql 변수로 넘긴다.
\pset pager off
SET statement_timeout = :'timeout';
SET max_parallel_workers_per_gather = 2;

\echo '### Q1 검색-영역: 충전소'
EXPLAIN (ANALYZE, BUFFERS)
select s1_0.station_id,s1_0.address,s1_0.address_detail,s1_0.busi_id,s1_0.del_yn,s1_0.kind,s1_0.kind_detail,s1_0.latitude,s1_0.limit_detail,s1_0.limit_yn,s1_0.longitude,s1_0.name,s1_0.operator_call,s1_0.operator_name,s1_0.org_name,s1_0.parking_free_yn,s1_0.stat_id,s1_0.updated_at,s1_0.use_time,s1_0.zcode,s1_0.zscode
from station s1_0
where s1_0.del_yn='N' and (s1_0.latitude between :min_lat and :max_lat and s1_0.longitude between :min_lng and :max_lng)
  and exists(select 1 from station_access sa1_0 where sa1_0.station_id=s1_0.station_id)
order by s1_0.station_id fetch first 101 rows only;

\echo '### Q2 검색-영역: 접근지점'
EXPLAIN (ANALYZE, BUFFERS)
select sa1_0.access_id,sa1_0.access_type,sa1_0.detour_km,i1_0.ic_id,i1_0.facility_code,i1_0.facility_type,i1_0.latitude,i1_0.longitude,i1_0.name,i1_0.route_id,r2_0.route_id,r2_0.route_name,r2_0.route_no,sa1_0.mapping_method,ra1_0.rest_area_id,ra1_0.direction,ra1_0.ev_charger_yn,ra1_0.latitude,ra1_0.longitude,ra1_0.name,ra1_0.route_id,r1_0.route_id,r1_0.route_name,r1_0.route_no,sa1_0.station_id,sa1_0.verified_at
from station_access sa1_0
left join rest_area ra1_0 on ra1_0.rest_area_id=sa1_0.rest_area_id left join route r1_0 on r1_0.route_id=ra1_0.route_id
left join interchange i1_0 on i1_0.ic_id=sa1_0.ic_id left join route r2_0 on r2_0.route_id=i1_0.route_id
where sa1_0.station_id in (:area_ids)
order by sa1_0.station_id,sa1_0.access_id;

\echo '### Q3 검색-영역: 급속 상태 집계'
EXPLAIN (ANALYZE, BUFFERS)
select c1_0.station_id,c1_0.current_normalized_status,count(c1_0.charger_id),max(c1_0.current_status_updated_at)
from charger c1_0
where c1_0.station_id in (:area_ids) and c1_0.is_fast=true and c1_0.del_yn='N'
group by c1_0.station_id,c1_0.current_normalized_status;

\echo '### Q4 검색-전국(limit 500): 충전소'
EXPLAIN (ANALYZE, BUFFERS)
select s1_0.station_id,s1_0.address,s1_0.address_detail,s1_0.busi_id,s1_0.del_yn,s1_0.kind,s1_0.kind_detail,s1_0.latitude,s1_0.limit_detail,s1_0.limit_yn,s1_0.longitude,s1_0.name,s1_0.operator_call,s1_0.operator_name,s1_0.org_name,s1_0.parking_free_yn,s1_0.stat_id,s1_0.updated_at,s1_0.use_time,s1_0.zcode,s1_0.zscode
from station s1_0
where s1_0.del_yn='N' and (s1_0.latitude between 33 and 39 and s1_0.longitude between 124 and 132)
  and exists(select 1 from station_access sa1_0 where sa1_0.station_id=s1_0.station_id)
order by s1_0.station_id fetch first 501 rows only;

\echo '### Q5 검색-전국(limit 500): 급속 상태 집계'
EXPLAIN (ANALYZE, BUFFERS)
select c1_0.station_id,c1_0.current_normalized_status,count(c1_0.charger_id),max(c1_0.current_status_updated_at)
from charger c1_0
where c1_0.station_id in (:all_ids) and c1_0.is_fast=true and c1_0.del_yn='N'
group by c1_0.station_id,c1_0.current_normalized_status;

\echo '### Q6 검색-노선+방향: 충전소'
EXPLAIN (ANALYZE, BUFFERS)
select s1_0.station_id,s1_0.address,s1_0.address_detail,s1_0.busi_id,s1_0.del_yn,s1_0.kind,s1_0.kind_detail,s1_0.latitude,s1_0.limit_detail,s1_0.limit_yn,s1_0.longitude,s1_0.name,s1_0.operator_call,s1_0.operator_name,s1_0.org_name,s1_0.parking_free_yn,s1_0.stat_id,s1_0.updated_at,s1_0.use_time,s1_0.zcode,s1_0.zscode
from station s1_0
where s1_0.del_yn='N'
  and exists(select 1 from station_access sa1_0 left join rest_area ra1_0 on ra1_0.rest_area_id=sa1_0.rest_area_id left join interchange i1_0 on i1_0.ic_id=sa1_0.ic_id
             where sa1_0.station_id=s1_0.station_id and (ra1_0.route_id=:route_id or i1_0.route_id=:route_id) and ra1_0.direction=:'direction')
order by s1_0.station_id fetch first 101 rows only;

\echo '### Q7 상세: 충전소'
EXPLAIN (ANALYZE, BUFFERS)
select s1_0.station_id,s1_0.address,s1_0.address_detail,s1_0.busi_id,s1_0.del_yn,s1_0.kind,s1_0.kind_detail,s1_0.latitude,s1_0.limit_detail,s1_0.limit_yn,s1_0.longitude,s1_0.name,s1_0.operator_call,s1_0.operator_name,s1_0.org_name,s1_0.parking_free_yn,s1_0.stat_id,s1_0.updated_at,s1_0.use_time,s1_0.zcode,s1_0.zscode
from station s1_0 where s1_0.station_id=:station_id and s1_0.del_yn='N';

\echo '### Q8 상세: 접근지점'
EXPLAIN (ANALYZE, BUFFERS)
select sa1_0.access_id,sa1_0.access_type,sa1_0.detour_km,i1_0.ic_id,i1_0.facility_code,i1_0.facility_type,i1_0.latitude,i1_0.longitude,i1_0.name,i1_0.route_id,r2_0.route_id,r2_0.route_name,r2_0.route_no,sa1_0.mapping_method,ra1_0.rest_area_id,ra1_0.direction,ra1_0.ev_charger_yn,ra1_0.latitude,ra1_0.longitude,ra1_0.name,ra1_0.route_id,r1_0.route_id,r1_0.route_name,r1_0.route_no,sa1_0.station_id,sa1_0.verified_at
from station_access sa1_0
left join rest_area ra1_0 on ra1_0.rest_area_id=sa1_0.rest_area_id left join route r1_0 on r1_0.route_id=ra1_0.route_id
left join interchange i1_0 on i1_0.ic_id=sa1_0.ic_id left join route r2_0 on r2_0.route_id=i1_0.route_id
where sa1_0.station_id=:station_id
order by sa1_0.station_id,sa1_0.access_id;

\echo '### Q9 상세: 충전기 목록'
EXPLAIN (ANALYZE, BUFFERS)
select c1_0.charger_id,c1_0.charger_type,c1_0.chger_id,c1_0.current_normalized_status,c1_0.current_status_code,c1_0.current_status_updated_at,c1_0.del_yn,c1_0.is_fast,c1_0.method,c1_0.output_kw,c1_0.station_id
from charger c1_0 where c1_0.station_id=:station_id and c1_0.del_yn='N' order by c1_0.chger_id;

\echo '### Q10 이력: 7일 첫 페이지 (size 100)'
EXPLAIN (ANALYZE, BUFFERS)
select csh1_0.history_id,csh1_0.charger_id,csh1_0.collected_at,csh1_0.last_charge_end,csh1_0.last_charge_start,csh1_0.now_charge_start,csh1_0.run_id,csh1_0.source_api,csh1_0.status_code,csh1_0.status_updated_at
from charger_status_history csh1_0
where csh1_0.charger_id=:charger_id and csh1_0.status_updated_at>=:'hist_from' and csh1_0.status_updated_at<:'hist_to'
order by csh1_0.status_updated_at fetch first 101 rows only;

\echo '### Q11 집계 입력: 30분 구간 1개의 관측 시점별 상태 분포'
EXPLAIN (ANALYZE, BUFFERS)
WITH pts AS (
  SELECT CAST(:'window' AS timestamp) + make_interval(mins => n * 5) AS t
  FROM generate_series(0, 5) AS n
), tg AS (
  SELECT c.charger_id, c.station_id
  FROM charger c
  WHERE c.is_fast AND c.del_yn = 'N'
    AND EXISTS (SELECT 1 FROM station_access sa WHERE sa.station_id = c.station_id)
)
SELECT tg.station_id, pts.t,
       count(*) FILTER (WHERE st.ns = 'AVAILABLE')   AS available,
       count(*) FILTER (WHERE st.ns = 'CHARGING')    AS charging,
       count(*) FILTER (WHERE st.ns = 'UNAVAILABLE') AS unavailable,
       count(*) FILTER (WHERE st.ns = 'UNKNOWN')     AS unknown
FROM pts
CROSS JOIN tg
LEFT JOIN LATERAL (
  SELECT h.status_code FROM charger_status_history h
  WHERE h.charger_id = tg.charger_id AND h.status_updated_at <= pts.t
  ORDER BY h.status_updated_at DESC
  LIMIT 1
) latest ON true
LEFT JOIN code_dictionary cd
  ON cd.code_group = 'CHARGER_STAT' AND cd.code = CAST(latest.status_code AS text)
CROSS JOIN LATERAL (SELECT coalesce(cd.normalized_status, 'UNKNOWN') AS ns) st
GROUP BY tg.station_id, pts.t;
