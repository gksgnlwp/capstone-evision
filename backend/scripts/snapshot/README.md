# 운영 데이터 스냅샷 (로컬 확인·시연용)

운영 서버에서 **서비스 대상 충전소의 실제 수집 데이터만** 뽑아 둔 파일입니다. 로컬 DB에 넣으면 운영 서버 없이도 실제 데이터로 API와 화면을 확인할 수 있습니다.

`evision_snapshot.sql.gz` (약 24MB, 기준 시각 2026-11-28 12:07)

| 내용 | 행 수 |
|---|---|
| 노선·휴게소·IC | 37 · 210 · 659 |
| 서비스 대상 충전소 (매핑된 충전소, 원천에서 삭제된 곳 포함) | 1,344 |
| 충전기 (급속·완속, 현재 상태 포함) | 4,331 |
| 휴게소·IC 매핑 | 1,505 |
| 충전기 상태 이력 (최근 3일) | 603,954 |
| 30분 집계 (최근 28일, 추천 과거 통계용) | 1,777,858 |
| 수집 기록 (최근 29일, 오류 메시지는 비움) | 8,379 |
| AI 예측 (30분·60분 뒤, 모델 xgb-v0.7) | 33,820 |

## 넣는 방법

**기존 로컬 DB의 노선·충전소·충전기·이력·집계·수집 기록을 모두 지우고 다시 채웁니다.** 시연 데이터(`demo/seed_demo.sql`)를 넣은 DB라면 그 데이터도 사라집니다. 전용 DB(별도 Compose 프로젝트나 볼륨)에 넣는 것을 권합니다.

1. 백엔드를 한 번 실행해 빈 DB에 테이블을 만듭니다 (Flyway V1~V8).
2. 스냅샷을 DB 컨테이너에 복사해 넣습니다. PowerShell·bash 모두 같습니다.

```powershell
cd backend
docker compose cp scripts/snapshot/evision_snapshot.sql.gz postgres:/tmp/snapshot.sql.gz
docker compose exec postgres sh -c 'gunzip -c /tmp/snapshot.sql.gz | psql -X -q -U "$POSTGRES_USER" -d "$POSTGRES_DB"'
```

   별도 Compose 프로젝트를 쓰면 `docker compose -p <프로젝트 이름> ...`처럼 프로젝트를 지정합니다. 1~2분 걸리고, 마지막에 테이블별 행 수가 나옵니다.

3. 백엔드를 **수집을 끈 상태**로 실행합니다. 켜 두면 공공 API를 호출하고 집계를 다시 계산합니다.

```powershell
.\gradlew bootRun --args='--evision.collection.enabled=false'
```

4. 프론트에서 확인합니다.

```powershell
cd ..\frontend
npm run smoke:api
npm run dev:api
```

## 시각

넣을 때 모든 시각을 **넣는 시각 기준으로 옮깁니다**. 예를 들어 하루 뒤에 넣으면 상태 변경 시각·수집 시각·30분 구간이 모두 하루씩 뒤로 갑니다. 그래야 '마지막 수집 시각'이 방금 전으로 보이고, 충전 중 6시간·사용가능 7일 '의심' 판정도 실제 서버처럼 나옵니다.

- AI 예측도 같이 옮겨지므로 넣은 직후에는 추천이 `AI_FORECAST` 기준으로 나옵니다. 추천은 90분 이내에 만든 예측만 쓰므로, 넣고 1시간 반쯤 지나면 과거 통계 기준으로 바뀝니다. 다시 넣으면 돌아옵니다.
- 시간이 지나면 데이터가 다시 오래된 것처럼 보입니다 (예: 몇 시간 뒤에는 충전 중이던 충전기가 '의심'으로 바뀜). 같은 명령으로 다시 넣으면 됩니다.
- 원래 시각 그대로 넣으려면 psql에 `-v keep_time=1`을 붙입니다.

## 안전장치

충전기가 10만 대를 넘는 DB(운영 서버는 약 54만 대)에서는 아무것도 지우지 않고 멈춥니다.

## 새로 뽑기 (백엔드 담당)

서버 SSH가 허용된 곳에서 실행합니다. 서버 주소는 `scripts/deploy-target.txt`에서 읽습니다.

```bash
backend/scripts/snapshot/export.sh
```

기간은 환경변수로 바꿀 수 있습니다: `HIST_DAYS`(상태 이력, 기본 3), `OCC_DAYS`(30분 집계, 기본 28), `RUN_DAYS`(수집 기록, 기본 29). 파일이 커질수록 Git 저장소도 커지므로 필요할 때만 새로 뽑습니다.
