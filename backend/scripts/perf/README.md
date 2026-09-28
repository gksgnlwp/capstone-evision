# 성능 측정 (명세 9장)

인덱스 적용 전·후의 실행계획(`EXPLAIN (ANALYZE, BUFFERS)`)과 API 응답시간을 측정한다. 결과는 `docs/perf/<날짜>/`에 저장한다.

**측정 전용 DB에서만 실행한다.** `generate_data.sql`은 모든 테이블을 비우고, `indexes_drop.sql`은 인덱스를 지운다.
로컬 개발 DB(`backend_evision-pgdata` 볼륨)나 운영 서버에서 실행하지 않는다.

## 파일

| 파일 | 내용 |
|---|---|
| `generate_data.sql` | 대용량 데이터 생성. 규모는 psql 변수로 바꾼다 (파일 머리말에 기본값과 근거) |
| `queries.sql` | 측정 대상 쿼리 11개. 앱이 실제로 실행하는 SQL(Hibernate 로그)과 집계 SQL |
| `indexes_drop.sql` / `indexes_create.sql` | 인덱스 적용 전 상태로 만들기 / V1 상태로 되돌리기 |
| `run_perf.sh` | 전 → 후 순서로 EXPLAIN과 HTTP 응답시간을 재고 요약표를 만든다 |

## 절차 (Git Bash, backend 폴더에서)

```bash
# 1. 측정 전용 PostgreSQL (포트 55432, 볼륨 없음)
docker run -d --name evision-perf -e POSTGRES_DB=evision -e POSTGRES_USER=perf -e POSTGRES_PASSWORD=perf \
  -e TZ=Asia/Seoul -p 55432:5432 --shm-size=1g postgres:17 \
  -c shared_buffers=1GB -c max_wal_size=4GB -c work_mem=64MB -c maintenance_work_mem=512MB

# 2. 스키마 적용 후 데이터 생성 (기본값: 충전소 10만, 이력 7일 ≈ 1,700만 행, 약 10분)
for f in src/main/resources/db/migration/V*.sql; do docker exec -i evision-perf psql -q -U perf -d evision < "$f"; done
docker exec -i evision-perf psql -U perf -d evision -v stations=100000 -v history_days=7 < scripts/perf/generate_data.sql

# 3. 앱을 측정 DB에 붙여 실행 (스케줄러 끔, Flyway는 V2까지 적용된 것으로 간주)
./gradlew bootJar
DB_URL=jdbc:postgresql://localhost:55432/evision DB_USERNAME=perf DB_PASSWORD=perf \
  java -jar build/libs/backend-0.0.1-SNAPSHOT.jar --server.port=18080 --evision.collection.enabled=false \
  --spring.flyway.baseline-on-migrate=true --spring.flyway.baseline-version=2

# 4. 다른 터미널에서 측정
bash scripts/perf/run_perf.sh

# 5. 정리
docker rm -f evision-perf
```

## 참고

- `docker exec`로 psql을 부르므로 PC에 psql을 설치할 필요가 없다.
- HTTP 시간은 같은 PC에서 curl로 잰 값이라 네트워크 지연이 거의 없는 서버 처리시간에 가깝다 (목표 PER-RES-01: 500ms 이내).
- 규모 기본값은 추정치다. 운영 DB에서 아래 값을 확인해 `-v`로 넘기면 실제 규모로 다시 잴 수 있다.
  ```sql
  SELECT count(*) FROM station;                         -- stations
  SELECT count(*) FROM charger;                         -- 충전소당 충전기 수 확인용
  SELECT sum(inserted_count) / count(DISTINCT started_at::date)
  FROM collection_run WHERE run_type = 'STATUS';        -- 하루 이력 증가량 ÷ 충전기 수 = changes_per_day
  ```
