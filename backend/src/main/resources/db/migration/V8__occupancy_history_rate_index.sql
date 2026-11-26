-- 추천 API 과거 통계를 '쾌적 기준'(occupancy_rate < 기준)으로 바꾸면서 커버링 인덱스도 바꾼다.
-- V5 인덱스는 available 만 담고 있어 occupancy_rate 를 읽으려면 테이블을 다시 읽어야 한다.
-- (station_id, window_start) 범위만 읽고 occupancy_rate 를 인덱스에서 바로 꺼내 Index Only Scan 이 되게 한다.
CREATE INDEX idx_occupancy_station_window_rate ON occupancy_30m (station_id, window_start) INCLUDE (occupancy_rate);
DROP INDEX IF EXISTS idx_occupancy_station_window_available;
