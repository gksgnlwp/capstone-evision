-- 충전소로 접근지점 매핑을 찾는 인덱스.
-- V3의 유일 인덱스는 접근유형별 부분 인덱스라 "이 충전소에 매핑이 있는가"를 찾을 때 쓰이지 않아,
-- 검색 쿼리가 충전소마다 매핑 표 전체를 읽는 계획을 고를 수 있었다.
CREATE INDEX idx_access_station ON station_access (station_id);
