-- 코드 초기 데이터
-- TODO(확인 필요): 활용가이드 v1.25 코드표로 확정. 현재 값은 2차 자료 기준이다.
-- TODO(확인 필요): CHARGER_TYPE 코드는 활용가이드 확인 후 새 버전 마이그레이션으로 추가한다.

INSERT INTO code_dictionary (code_group, code, code_name, normalized_status, source_doc) VALUES
  ('CHARGER_STAT', '0', '알수없음',          'UNKNOWN',     '2차 자료(확인 필요)'),
  ('CHARGER_STAT', '1', '통신이상',          'UNAVAILABLE', '2차 자료(확인 필요)'),
  ('CHARGER_STAT', '2', '사용가능(충전대기)', 'AVAILABLE',   '2차 자료(확인 필요)'),
  ('CHARGER_STAT', '3', '충전중',            'CHARGING',    '2차 자료(확인 필요)'),
  ('CHARGER_STAT', '4', '운영중지',          'UNAVAILABLE', '2차 자료(확인 필요)'),
  ('CHARGER_STAT', '5', '점검중',            'UNAVAILABLE', '2차 자료(확인 필요)'),
  ('KIND_DETAIL',  'C001', '고속도로 휴게소', NULL,          '2차 자료(확인 필요)');
