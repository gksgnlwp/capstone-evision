export interface ScreenDefinition {
  id: string;
  path: string;
  title: string;
  description: string;
  phase: 2 | 3 | 4 | 5 | 6;
  audience: 'mobile' | 'admin';
  useCases: string[];
}

export const screens: ScreenDefinition[] = [
  { id: 'M01', path: '/', title: '홈 · 목적지 설정', description: '최근 목적지 선택과 노선·방향 탐색의 시작 화면', phase: 2, audience: 'mobile', useCases: ['U02', 'U13'] },
  { id: 'M02', path: '/route', title: '경로 위 충전소', description: '노선·방향별 휴게소와 IC 인근 충전소 목록', phase: 2, audience: 'mobile', useCases: ['U02'] },
  { id: 'M03', path: '/map', title: '내 주변 지도', description: '지도 영역 조회, 충전소 필터와 마커 요약', phase: 2, audience: 'mobile', useCases: ['U01', 'U13'] },
  { id: 'M04', path: '/settings/operators', title: '선호 운영기관', description: '선호 기관 선택과 브라우저 저장', phase: 5, audience: 'mobile', useCases: ['U03'] },
  { id: 'M05', path: '/recommendations/arrival', title: '도착 예정시각 변경', description: 'M06 추천 결과에서 선택적으로 여는 시각 설정 화면', phase: 3, audience: 'mobile', useCases: ['U12'] },
  { id: 'M06', path: '/recommendations', title: '충전소 추천', description: '추천 상위 5곳과 사유, 도착 예정시각 변경', phase: 3, audience: 'mobile', useCases: ['U09', 'U10'] },
  { id: 'M07', path: '/compare', title: '추천 충전소 비교', description: '거리·우회거리·이용 가능 확률·대기시간 비교', phase: 3, audience: 'mobile', useCases: ['U10'] },
  { id: 'M08', path: '/stations/:stationId', title: '충전소 상세', description: '운영·접근 정보와 충전기별 실시간 상태', phase: 4, audience: 'mobile', useCases: ['U04', 'U05'] },
  { id: 'M09', path: '/stations/:stationId/prediction', title: '도착 시점 예측', description: '예측값·산출 시각·현재 상태를 구분해 표시', phase: 4, audience: 'mobile', useCases: ['U07'] },
  { id: 'M10', path: '/stations/:stationId/patterns', title: '시간대별 혼잡 패턴', description: '요일과 시간대별 점유율·만차 비율', phase: 4, audience: 'mobile', useCases: ['U08'] },
  { id: 'M11', path: '/stations/:stationId/chargers/:chargerId/history', title: '충전기 상태 이력', description: '기본 24시간, 최대 7일의 변경 이력과 페이지 조회', phase: 4, audience: 'mobile', useCases: ['U06'] },
  { id: 'M12', path: '/favorites', title: '즐겨찾기', description: '브라우저에 최대 20개 저장하고 현재 상태 일괄 조회', phase: 5, audience: 'mobile', useCases: ['U11'] },
  { id: 'M13', path: '/location', title: '위치 권한 안내', description: '권한 재요청, 목적지 또는 지도 탐색으로 이동', phase: 2, audience: 'mobile', useCases: ['U13'] },
  { id: 'M14', path: '/stations/:stationId/prediction-unavailable', title: '예측 미제공 안내', description: '예측 없음·노후화 안내와 현재 상태 기반 대체', phase: 4, audience: 'mobile', useCases: ['U07'] },
  { id: 'D00', path: '/admin/login', title: '운영자 로그인', description: '인증과 로그인 실패 제한, 세션 만료 처리', phase: 6, audience: 'admin', useCases: ['A01'] },
  { id: 'D01', path: '/admin/collection', title: '수집 모니터링', description: '수집 회차, 연속 실패와 30분 구간 가용률', phase: 6, audience: 'admin', useCases: ['A02', 'S01', 'S02', 'S03', 'S04'] },
  { id: 'D02', path: '/admin/statistics', title: '거점별 운영 통계', description: '노선·휴게소·IC별 점유율, 만차 비율과 CSV', phase: 6, audience: 'admin', useCases: ['A03'] },
  { id: 'D03', path: '/admin/accuracy', title: '예측 정확도', description: '모델별 MAE·정밀도·재현율·F1', phase: 6, audience: 'admin', useCases: ['A04'] },
  { id: 'D04', path: '/admin/mappings', title: '기준정보 · 매핑', description: '기준정보·검증된 휴게소 매핑 적재와 IC 자동 매핑', phase: 6, audience: 'admin', useCases: ['A05'] },
  { id: 'D05', path: '/admin/models', title: '모델 학습 · 반영', description: '학습·평가 결과 확인과 승인 후 모델 반영', phase: 6, audience: 'admin', useCases: ['A06'] },
];

export const settingsScreen: ScreenDefinition = {
  id: 'SETTINGS', path: '/settings', title: '설정', description: '선호 기관과 브라우저 저장 설정 관리',
  phase: 5, audience: 'mobile', useCases: ['U03', 'U11'],
};

export function previewPath(screen: ScreenDefinition) {
  return screen.path.replace(':stationId', 'preview-station').replace(':chargerId', 'preview-charger');
}
