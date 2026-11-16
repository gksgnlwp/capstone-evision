-- 충전기 상태 코드 9(상태미확인) 추가.
-- 원천이 실제로 보내는 값인데 코드표에 없어, 지금까지는 "표에 없는 코드는 미확인"이라는 기본 처리에 기대고 있었다.
-- 정규화 결과는 그대로 UNKNOWN이다.
INSERT INTO code_dictionary (code_group, code, code_name, normalized_status, source_doc) VALUES
  ('CHARGER_STAT', '9', '상태미확인', 'UNKNOWN', '한국환경공단 API 활용가이드(stat 코드) · 수집 데이터 확인(2026-11-16)');
