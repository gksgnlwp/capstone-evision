import { config } from '../../config/env';
import { createHttpClient } from './httpClient';

// 제품 화면은 실제 API만 사용합니다. 테스트용 응답은 테스트 코드에서 주입합니다.
export const apiClient = createHttpClient(config);
export { ApiError } from './types';
export type { ApiClient, RequestOptions } from './types';
