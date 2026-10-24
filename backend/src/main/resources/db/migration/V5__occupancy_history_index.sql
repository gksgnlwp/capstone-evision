-- 추천 API의 과거 가용 통계 조회용 커버링 인덱스 (측정: docs/perf/2026-10-06-recommend/README.md)
-- (station_id, window_start) 범위만 읽고 available 을 인덱스에서 바로 꺼내 Index Only Scan 이 되게 한다.
-- 30분마다 서비스 대상 충전소 수만큼 upsert 되는 테이블이라 쓰기 비용 증가는 작다.
CREATE INDEX idx_occupancy_station_window_available ON occupancy_30m (station_id, window_start) INCLUDE (available);
