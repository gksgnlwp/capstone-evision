import { config } from '../../config/env';
import { createHttpClient } from './httpClient';
import { createMockClient } from './mockClient';

// 실제 엔드포인트와 DTO는 백엔드 계약 확인 후 기능별 어댑터에 추가합니다.
// api 모드에서 실패한 요청을 mock 데이터로 대체하지 않습니다.
export const apiClient = config.dataMode === 'api' ? createHttpClient(config) : createMockClient();
export { ApiError } from './types';
export type { ApiClient, RequestOptions } from './types';
