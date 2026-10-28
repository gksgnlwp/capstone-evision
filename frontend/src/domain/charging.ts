/** 분석서의 정규화 상태. UNKNOWN은 AVAILABLE 또는 0대로 변환하지 않습니다. */
export type NormalizedStatus = 'AVAILABLE' | 'CHARGING' | 'UNAVAILABLE' | 'UNKNOWN';
export type AccessType = 'REST_AREA' | 'IC';
export type CollectionRunStatus = 'RUNNING' | 'SUCCESS' | 'PARTIAL' | 'FAILED';

export const chargerStatuses: Record<NormalizedStatus, { label: string; tone: 'success' | 'info' | 'danger' | 'neutral' }> = {
  AVAILABLE: { label: '이용 가능', tone: 'success' },
  CHARGING: { label: '충전 중', tone: 'info' },
  UNAVAILABLE: { label: '이용 불가', tone: 'danger' },
  UNKNOWN: { label: '알 수 없음', tone: 'neutral' },
};
