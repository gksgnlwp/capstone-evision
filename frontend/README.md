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

터미널에 표시된 주소를 엽니다. 기본 주소는 `http://127.0.0.1:5173`입니다. `.env.local` 없이도 기본 mock 모드로 실행됩니다.

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

첫 화면은 Figma M01의 **목적지 설정 홈**입니다. 출발·목적지 입력과 노선 직접 선택, 주변 지도 진입을 배치했습니다. 입력한 목적지는 경로 화면의 URL에 전달하며 실제 목적지 검색·경로 계산은 2번 작업에서 구현합니다. 아직 구현하지 않은 화면에는 간단한 준비 안내를 표시하며, 화면 ID·작업 단계·화면 목록 같은 개발용 정보를 제품 UI에 노출하지 않습니다. 운영 메뉴는 레이아웃 미리보기이며 인증·인가가 구현된 관리 기능이 아닙니다. 실제 운영 데이터나 제어 API를 연결하지 않았습니다. Git 저장소 초기화와 커밋을 수행하지 않습니다.

## 6개 작업 단위

| 번호 | 범위 | 상태 |
|---|---|---|
| 1 | 개발 환경·공통 UI·라우팅·API 기반 | 구현 |
| 2 | M01·M02·M03·M13 탐색 | 예정 |
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
