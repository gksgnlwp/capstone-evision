"""
upsert_forecast.py — predict_batch.py 가 만든 예측 CSV를 occupancy_forecast 테이블에 넣는다 (같은 구간은 덮어쓰기).
추천 API는 이 테이블에서 (충전소, 도착 구간) 값을 읽는다. 규격: docs/forecast/README.md

  python upsert_forecast.py --csv work/prediction.csv
  (DB 주소는 환경변수 EVISION_FORECAST_DB_URL)

컬럼 대응 (CSV → 테이블)
  station_id               → station_id
  target_time              → target_window_start
  p_available              → p_available            (쾌적 이용 확률, 사용률 50% 미만일 확률)
  predicted_occupancy_rate → predicted_occupancy_rate
  model_version            → model_version
  predicted_at             → generated_at           (추천은 이 값으로 90분 신선도를 판단)
  horizon_min              → (저장 안 함. 구간 시작 시각으로 구분된다)

검사: 구간 시작이 00분/30분 정각인지, 확률이 0~1인지, 필수 값이 비었는지. 하나라도 틀리면 아무것도 넣지 않는다.
DB에 없는 충전소 ID는 건너뛰고 개수만 알린다. 전체를 한 트랜잭션으로 넣는다.
종료 코드: 0 성공 / 1 오류 (입력 형식, DB)
"""
import argparse
import os
import sys

import pandas as pd

REQUIRED = ["station_id", "target_time", "p_available", "model_version", "predicted_at"]

UPSERT_SQL = """
    INSERT INTO occupancy_forecast
        (station_id, target_window_start, p_available, predicted_occupancy_rate, model_version, generated_at)
    VALUES %s
    ON CONFLICT (station_id, target_window_start) DO UPDATE SET
        p_available = EXCLUDED.p_available,
        predicted_occupancy_rate = EXCLUDED.predicted_occupancy_rate,
        model_version = EXCLUDED.model_version,
        generated_at = EXCLUDED.generated_at
"""


class InvalidForecast(ValueError):
    pass


def to_rows(df):
    """예측 CSV → occupancy_forecast 행 목록. 형식이 틀리면 InvalidForecast."""
    missing = [c for c in REQUIRED if c not in df.columns]
    if missing:
        raise InvalidForecast("필수 컬럼이 없습니다: " + ", ".join(missing))
    if df.empty:
        raise InvalidForecast("예측 행이 없습니다.")
    d = df.copy()
    if "predicted_occupancy_rate" not in d.columns:
        d["predicted_occupancy_rate"] = None
    if d[REQUIRED].isna().any().any():
        raise InvalidForecast("필수 값이 빈 행이 있습니다: " + ", ".join(c for c in REQUIRED if d[c].isna().any()))

    target = pd.to_datetime(d["target_time"])
    generated = pd.to_datetime(d["predicted_at"])
    if getattr(target.dt, "tz", None) is not None or getattr(generated.dt, "tz", None) is not None:
        raise InvalidForecast("시각에 시간대가 붙어 있습니다. 한국 시간(시간대 표시 없음)이어야 합니다.")
    off_grid = (target.dt.minute % 30 != 0) | (target.dt.second != 0) | (target.dt.microsecond != 0)
    if off_grid.any():
        raise InvalidForecast(f"구간 시작이 00분/30분 정각이 아닌 행 {int(off_grid.sum())}개 (예: {target[off_grid].iloc[0]})")

    p = pd.to_numeric(d["p_available"], errors="coerce")
    rate = pd.to_numeric(d["predicted_occupancy_rate"], errors="coerce")
    if (p.isna() | (p < 0) | (p > 1)).any():
        raise InvalidForecast("p_available 이 0~1 범위를 벗어난 행이 있습니다.")
    if ((rate < 0) | (rate > 1)).any():
        raise InvalidForecast("predicted_occupancy_rate 가 0~1 범위를 벗어난 행이 있습니다.")
    if d["model_version"].astype(str).str.len().gt(40).any():
        raise InvalidForecast("model_version 은 40자 이하여야 합니다.")

    key = pd.DataFrame({"station_id": d["station_id"].astype("int64"), "target": target})
    if key.duplicated().any():
        raise InvalidForecast(f"같은 (충전소, 구간) 행이 {int(key.duplicated().sum())}개 중복됩니다.")

    return [
        (int(s), t.to_pydatetime(), round(float(pv), 4), None if pd.isna(r) else round(float(r), 4),
         str(mv), g.floor("s").to_pydatetime())
        for s, t, pv, r, mv, g in zip(key["station_id"], target, p, rate, d["model_version"], generated)
    ]


def upsert(conn, rows):
    """DB에 있는 충전소만 넣는다. (넣은 행 수, 건너뛴 행 수)"""
    from psycopg2.extras import execute_values

    with conn.cursor() as cur:
        cur.execute("SELECT station_id FROM station WHERE station_id = ANY(%s)", (sorted({r[0] for r in rows}),))
        known = {x[0] for x in cur.fetchall()}
        keep = [r for r in rows if r[0] in known]
        if keep:
            execute_values(cur, UPSERT_SQL, keep, page_size=1000)
    return len(keep), len(rows) - len(keep)


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--csv", required=True, help="predict_batch.py --out 파일")
    ap.add_argument("--db-url", default=None, help="기본은 환경변수 EVISION_FORECAST_DB_URL")
    a = ap.parse_args(argv)

    url = a.db_url or os.environ.get("EVISION_FORECAST_DB_URL")
    if not url:
        print("[오류] DB 주소가 없습니다. EVISION_FORECAST_DB_URL 환경변수를 설정하세요.")
        return 1
    try:
        rows = to_rows(pd.read_csv(a.csv))
    except (InvalidForecast, FileNotFoundError, pd.errors.EmptyDataError) as e:
        print("[오류]", e)
        return 1

    import psycopg2
    conn = psycopg2.connect(url)
    try:
        with conn:                                    # 한 트랜잭션: 실패하면 전부 되돌림
            n, skipped = upsert(conn, rows)
    except psycopg2.Error as e:
        print("[오류] DB 저장 실패, 아무것도 바뀌지 않았습니다:", str(e).strip())
        return 1
    finally:
        conn.close()
    targets = sorted({r[1] for r in rows})
    print(f"저장: {n}행 (구간 {', '.join(t.strftime('%m-%d %H:%M') for t in targets)}, 모델 {rows[0][4]})"
          + (f", DB에 없는 충전소 {skipped}행 건너뜀" if skipped else ""))
    return 0


if __name__ == "__main__":
    sys.exit(main())
