-- station_access 중복 방지 (요구사항분석서 8.4)
-- V1의 UNIQUE (station_id, rest_area_id, ic_id)는 휴게소 행의 ic_id, IC 행의 rest_area_id가 항상 NULL이라
-- NULL끼리는 서로 다른 값으로 취급되어 같은 매핑이 두 번 들어가도 막지 못한다.
-- 접근유형별 부분 유일 인덱스로 바꾼다. 어느 쪽이 채워지는지는 V1의 CHECK 제약이 이미 보장한다.

ALTER TABLE station_access DROP CONSTRAINT station_access_station_id_rest_area_id_ic_id_key;

CREATE UNIQUE INDEX uk_access_rest_area ON station_access (station_id, rest_area_id) WHERE access_type = 'REST_AREA';
CREATE UNIQUE INDEX uk_access_ic        ON station_access (station_id, ic_id)        WHERE access_type = 'IC';
