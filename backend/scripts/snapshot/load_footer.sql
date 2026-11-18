
\echo 기존 데이터 비우고 스냅샷으로 채우는 중
TRUNCATE occupancy_forecast, occupancy_30m, charger_status_history, collection_run,
         station_access, charger, station, interchange, rest_area, route RESTART IDENTITY CASCADE;

INSERT INTO route SELECT * FROM stg_route;
INSERT INTO rest_area SELECT * FROM stg_rest_area;
INSERT INTO interchange SELECT * FROM stg_interchange;

INSERT INTO station (station_id, stat_id, name, address, address_detail, latitude, longitude, zcode, zscode, kind,
                     kind_detail, busi_id, org_name, operator_name, operator_call, use_time, parking_free_yn,
                     limit_yn, limit_detail, del_yn, updated_at)
SELECT station_id, stat_id, name, address, address_detail, latitude, longitude, zcode, zscode, kind,
       kind_detail, busi_id, org_name, operator_name, operator_call, use_time, parking_free_yn,
       limit_yn, limit_detail, del_yn, updated_at + :'shift'::interval
FROM stg_station;

INSERT INTO charger (charger_id, station_id, chger_id, charger_type, output_kw, method, is_fast, del_yn,
                     current_status_code, current_normalized_status, current_status_updated_at)
SELECT charger_id, station_id, chger_id, charger_type, output_kw, method, is_fast, del_yn,
       current_status_code, current_normalized_status, current_status_updated_at + :'shift'::interval
FROM stg_charger;

INSERT INTO station_access (access_id, station_id, access_type, rest_area_id, ic_id, detour_km, mapping_method, verified_at)
SELECT access_id, station_id, access_type, rest_area_id, ic_id, detour_km, mapping_method, verified_at
FROM stg_station_access;

INSERT INTO collection_run (run_id, run_type, started_at, ended_at, run_status, api_call_count, total_count,
                            fetched_count, inserted_count, duplicate_count, unknown_count, invalid_count,
                            retry_count, error_code, error_message)
SELECT run_id, run_type, started_at + :'shift'::interval, ended_at + :'shift'::interval, run_status, api_call_count,
       total_count, fetched_count, inserted_count, duplicate_count, unknown_count, invalid_count,
       retry_count, error_code, error_message
FROM stg_collection_run;

INSERT INTO charger_status_history (history_id, charger_id, status_code, status_updated_at, last_charge_start,
                                    last_charge_end, now_charge_start, source_api, run_id, collected_at)
SELECT history_id, charger_id, status_code, status_updated_at + :'shift'::interval,
       last_charge_start + :'shift'::interval, last_charge_end + :'shift'::interval,
       now_charge_start + :'shift'::interval, source_api, run_id, collected_at + :'shift'::interval
FROM stg_charger_status_history;

INSERT INTO occupancy_30m (occupancy_id, station_id, window_start, fast_charger_count, occupancy_rate, available,
                           full_minutes, valid_minutes, observed_points, computed_at)
SELECT occupancy_id, station_id, window_start + :'wshift'::interval, fast_charger_count, occupancy_rate, available,
       full_minutes, valid_minutes, observed_points, computed_at + :'shift'::interval
FROM stg_occupancy_30m;

INSERT INTO occupancy_forecast (forecast_id, station_id, target_window_start, p_available, predicted_occupancy_rate,
                                model_version, generated_at)
SELECT forecast_id, station_id, target_window_start + :'wshift'::interval, p_available, predicted_occupancy_rate,
       model_version, generated_at + :'shift'::interval
FROM stg_occupancy_forecast;

-- 다음에 앱이 새 행을 넣어도 번호가 겹치지 않게
SELECT setval(pg_get_serial_sequence('route', 'route_id'), coalesce(max(route_id), 0) + 1, false) FROM route;
SELECT setval(pg_get_serial_sequence('rest_area', 'rest_area_id'), coalesce(max(rest_area_id), 0) + 1, false) FROM rest_area;
SELECT setval(pg_get_serial_sequence('interchange', 'ic_id'), coalesce(max(ic_id), 0) + 1, false) FROM interchange;
SELECT setval(pg_get_serial_sequence('station', 'station_id'), coalesce(max(station_id), 0) + 1, false) FROM station;
SELECT setval(pg_get_serial_sequence('charger', 'charger_id'), coalesce(max(charger_id), 0) + 1, false) FROM charger;
SELECT setval(pg_get_serial_sequence('station_access', 'access_id'), coalesce(max(access_id), 0) + 1, false) FROM station_access;
SELECT setval(pg_get_serial_sequence('collection_run', 'run_id'), coalesce(max(run_id), 0) + 1, false) FROM collection_run;
SELECT setval(pg_get_serial_sequence('charger_status_history', 'history_id'), coalesce(max(history_id), 0) + 1, false) FROM charger_status_history;
SELECT setval(pg_get_serial_sequence('occupancy_30m', 'occupancy_id'), coalesce(max(occupancy_id), 0) + 1, false) FROM occupancy_30m;
SELECT setval(pg_get_serial_sequence('occupancy_forecast', 'forecast_id'), coalesce(max(forecast_id), 0) + 1, false) FROM occupancy_forecast;

COMMIT;
ANALYZE;

\echo 적재 결과
SELECT 'station' AS tbl, count(*) FROM station
UNION ALL SELECT 'charger', count(*) FROM charger
UNION ALL SELECT 'station_access', count(*) FROM station_access
UNION ALL SELECT 'charger_status_history', count(*) FROM charger_status_history
UNION ALL SELECT 'occupancy_30m', count(*) FROM occupancy_30m
UNION ALL SELECT 'occupancy_forecast', count(*) FROM occupancy_forecast
UNION ALL SELECT 'collection_run', count(*) FROM collection_run;
SELECT max(ended_at) AS last_collected_at, (SELECT max(window_start) FROM occupancy_30m) AS last_window FROM collection_run;
