# EVISION 프론트엔드

고속도로 휴게소·IC 인근 충전소를 위한 모바일 우선 웹과 운영 대시보드입니다.

## 실행

Node.js 22.12 이상(짝수 LTS 권장), npm을 사용합니다.

```powershell
cd frontend
npm ci
Copy-Item .env.example .env.local
npm run dev
```

터미널에 표시된 주소를 엽니다. 기본 주소는 `http://127.0.0.1:5173`입니다. `npm run dev`는 `.env.development`의 DEV 설정으로 실행됩니다.

```powershell
npm run lint
npm test
npm run build
npx playwright install chromium
npm run test:e2e
```

## 1번 작업에 포함된 것

- React + TypeScript + Vite, 엄격 타입 검사와 ESLint
- Figma v2 색상·여백·폰트 토큰, 로컬 번들 Noto Sans KR
- 모바일 앱바·하단 메뉴, 반응형 운영 사이드바
- M01~M14, D00~D05의 URL과 준비 화면, 별도 설정 경로
- 버튼, 카드, 칩, 상태 배지, 입력, 로딩·오류·빈 화면, 키보드 지원 대화상자
- HTTP 요청·취소·타임아웃·오류 처리, mock/api 전환과 비동기 조회 훅
- 단위 검사와 브라우저 경로·레이아웃 검사

첫 화면은 Figma M01의 **목적지 설정 홈**입니다. 아직 구현하지 않은 화면에는 간단한 준비 안내를 표시하며, 화면 ID·작업 단계·화면 목록 같은 개발용 정보를 제품 UI에 노출하지 않습니다. 운영 메뉴는 레이아웃 미리보기이며 인증·인가가 구현된 관리 기능이 아닙니다. Git 커밋은 사용자 승인 후에만 수행합니다.

## 2번 작업에 포함된 것

- M01 목적지 입력과 최근 목적지 최대 5개 브라우저 저장
- M02 노선·원천 방향 선택, 휴게소·IC·충전기 타입 필터, 중복 제거, 상태 요약, 다시 조회
- M03 카카오맵 SDK 기반 지도 이동·확대, 영역 재검색, 묶음 마커·상세 진입 코드와 미설정 안내
- M13 사용자 동작으로만 위치 확인, 거부·시간 초과·미지원 처리와 목적지·지도 대안
- 실제 백엔드 `StationQueryController`의 `GET /api/routes`, `GET /api/stations` 규격에 맞춘 어댑터와 Zod 응답 검증

`VITE_APP_ENV=DEV`이면 **휴게소·IC 목록, 좌표, 기관, 충전 상태와 갱신 시각은 모두 UI 테스트용 데이터**입니다. 실제 휴게소와 이름이 같더라도 서버 조회값이 아닙니다. 우측 상단에 `DEV`만 표시하며 안내 배너는 표시하지 않습니다. `VITE_APP_ENV=PRD`이면 백엔드 API를 조회하고 DEV 표시를 숨깁니다. PRD의 요청 실패를 더미 데이터로 대체하지 않습니다. 백엔드 소스 기반 규격 검사와 HTTP 모의 응답 브라우저 검사를 수행하며, 운영 서버·실제 DB와의 통합 검증은 별도입니다.

환경 설정은 `.env.development`의 `DEV`와 `.env.production`의 `PRD`로 구분합니다. `npm run build`는 운영 설정을 읽습니다. 두 파일에는 환경 구분만 넣고, 실제 주소·키 등 로컬 설정은 Git에서 제외된 `.env.local` 또는 환경별 `.env.development.local`·`.env.production.local`에 넣습니다. 실행 명령에 주입된 환경변수가 파일보다 우선합니다. 설정 변경 후에는 개발 서버를 재시작하거나 다시 빌드해야 합니다.

목적지로 경로를 자동 계산하는 백엔드 API는 현재 없습니다. 입력한 목적지를 유지하면서 이용할 노선·방향을 직접 선택합니다. 현재 위치에서 거리를 표시할 때도 **직선거리**로 명시하며 운전 거리나 도착 시간을 만들지 않습니다. 상세·추천은 해당 후속 화면으로 이동하지만 실제 내용은 3·4번 작업 범위입니다.

위치는 React 메모리에만 유지하고 새로고침하면 폐기합니다. API 지도 검색에서는 지도 영역 좌표를 전달합니다. 지도를 표시하면 배경 타일 제공자에게 현재 지도 영역에 해당하는 타일 요청이 전송됩니다.

지도는 **카카오맵 JavaScript SDK**를 사용합니다. Leaflet·OpenStreetMap 의존성과 사용 코드는 제거했습니다. JavaScript 키가 없으면 지도 대신 준비 안내를 표시하고, DEV에서는 더미 충전소 목록을 별도로 확인할 수 있습니다. 키 없는 상태에서 실제 SDK 지도 동작이 검증된 것은 아닙니다.

`.env.local`에 `VITE_KAKAO_MAP_APP_KEY=카카오_JavaScript_키`를 넣고 개발 서버를 재시작합니다. Kakao Developers에서 해당 키의 SDK 도메인에 `http://127.0.0.1:5173`과 운영 도메인을 등록해야 합니다. 실제 키를 Git에 커밋하지 않습니다. 지도 키는 백엔드 주소와 별개의 설정입니다.

### 로컬 백엔드 연결

`.env.local`에 다음을 설정하고 개발 서버를 재시작합니다. 백엔드 프로세스와 DB는 별도로 실행되어 있어야 합니다.

```dotenv
VITE_APP_ENV=PRD
VITE_API_BASE_URL=/api/
API_PROXY_TARGET=http://localhost:8080
```

Vite가 `/api`를 백엔드로 전달하므로 로컬 브라우저의 CORS 충돌을 줄입니다. 공개 조회는 현재 백엔드 CORS 규격에 맞춰 `credentials: omit`으로 요청합니다. 운영 배포에는 별도의 역방향 프록시/CORS 구성이 필요합니다.

로컬 개발 중 PRD를 확인하려면 위 값을 `.env.development.local`에 넣습니다. 일반 `.env.local`의 환경 구분은 `.env.development`보다 우선하지 않으므로 환경별 local 파일을 사용합니다.

## 6개 작업 단위

| 번호 | 범위 | 상태 |
|---|---|---|
| 1 | 개발 환경·공통 UI·라우팅·API 기반 | 구현 |
| 2 | M01·M02·M03·M13 탐색 | 구현, 운영 서버 통합 검증 예정 |
| 3 | M05·M06·M07 추천·비교 | 예정 |
| 4 | M08·M09·M10·M11·M14 상세·예측 | 예정 |
| 5 | M04·M12·설정 개인화 | 예정 |
| 6 | D00~D05 운영자 화면 | 예정 |

**각 커밋은 사용자 허락을 받은 뒤에만 수행합니다.**

## 폴더 구조

```text
src/
  app/          # 라우트와 20개 화면 정의
  components/   # 공통 UI와 오류 경계
  config/       # 환경 설정 검증
  domain/       # 분석서 기반 공통 상태 타입
  features/search/ # 탐색 화면·DTO·예시 데이터·위치 상태
  hooks/        # 비동기 조회와 취소
  layouts/      # 모바일·운영 레이아웃
  pages/        # 현재 준비 화면과 개발용 UI 갤러리
  services/api/ # HTTP·mock 클라이언트
  styles/       # 디자인 토큰과 공통 스타일
  test/         # 테스트 초기화
docs/           # 화면·백엔드 연결 기준
e2e/            # 브라우저 검사
design-reference/ # 로컬 Figma 참고 스냅샷, 앱·Git 추적 대상에서 제외
```

`/dev/ui` 컴포넌트 확인 화면은 Vite 개발 모드에만 노출됩니다. 기능 구현 시 `features/search`, `features/recommendations`처럼 해당 작업별 폴더를 추가하고 현재 준비 화면을 교체합니다.

배포 시 BrowserRouter를 위해 서버에서 앱 경로를 `index.html`로 돌려주는 fallback 설정이 필요합니다. `/api/`는 이 fallback에서 제외해야 합니다. `dist/`를 정적 서버에 배포하며, `npm run preview`는 로컬 빌드 확인용입니다.
