"""
export_recent.py — 서버 DB에서 최근 N일 데이터를 뽑아 prep_5m.py / predict_batch.py 입력 파일을 만든다.
11/13 학습 데이터(EVision_5m_1113)와 같은 파일 이름·컬럼으로 저장하므로 학습 때와 같은 명령으로 이어서 돌릴 수 있다.

  python export_recent.py --out-dir work/export --days 9
  (DB 주소는 환경변수 EVISION_FORECAST_DB_URL, 예: postgresql://user:pw@localhost:5432/evision)

만드는 파일
  occupancy_5m.csv.gz        충전소 x 5분 시점별 급속충전기 상태 수 (빠짐없는 격자)
  status_history_raw.csv.gz  급속충전기 상태 이력 원본 (충전 경과시간 변수용)
  chargers.csv               급속충전기 목록 (charger_id, station_id, output_kw)
  stations.csv               충전소 정보 (station_id, stat_id, name, latitude, longitude, fast_charger_count, max_output_kw)
  station_access.csv         충전소 ↔ 휴게소·IC 연결

5분 시점 상태 계산은 백엔드 30분 집계(aggregation/OccupancyAggregationRepository.POINT_COUNTS)와 같은 규칙이다.
  - 대상: station_access 에 매핑되고 급속충전기(삭제 안 됨)가 1기 이상인 충전소의 급속충전기
  - 시점 t 상태: status_updated_at <= t 인 가장 최근 이력의 정규화 상태 (이력 없으면 UNKNOWN)
  - 센서 멈춤: CHARGING 인데 마지막 변경이 t - 6시간 이전이면 UNKNOWN
  - 유효 시점: (t - 10분, t + 1분] 안에 SUCCESS/PARTIAL STATUS 수집 회차가 시작됐으면 1
--check-30m 을 주면 이 파일로 다시 만든 30분 값이 DB occupancy_30m 과 같은지 비교한다.
"""
import argparse
import os
import sys
import time
from pathlib import Path

import numpy as np
import pandas as pd

POINT_MIN = 5
WINDOW_MIN = 30
STUCK_HOURS = 6
VALID_LOOKBACK = pd.Timedelta(minutes=10)
VALID_TOLERANCE = pd.Timedelta(minutes=1)
STATUSES = ("AVAILABLE", "CHARGING", "UNAVAILABLE", "UNKNOWN")

TARGETS = """
    SELECT c.charger_id, c.station_id, c.output_kw
    FROM charger c
    WHERE c.is_fast AND c.del_yn = 'N'
      AND EXISTS (SELECT 1 FROM station_access sa WHERE sa.station_id = c.station_id)
"""
TARGET_CHARGERS_SQL = TARGETS + "ORDER BY c.station_id, c.charger_id"

# 기간 안 이력 + 기간 시작 시점의 상태를 알기 위한 직전 이력 1건 (충전기별)
HISTORY_SQL = """
    WITH tg AS (""" + TARGETS + """)
    SELECT h.charger_id, h.status_code, h.status_updated_at, h.collected_at, cd.normalized_status
    FROM charger_status_history h
    JOIN tg ON tg.charger_id = h.charger_id
    LEFT JOIN code_dictionary cd ON cd.code_group = 'CHARGER_STAT' AND cd.code = CAST(h.status_code AS text)
    WHERE h.status_updated_at > %(start)s AND h.status_updated_at <= %(end)s
    UNION ALL
    SELECT p.charger_id, p.status_code, p.status_updated_at, p.collected_at, cd.normalized_status
    FROM tg
    CROSS JOIN LATERAL (
      SELECT h.charger_id, h.status_code, h.status_updated_at, h.collected_at
      FROM charger_status_history h
      WHERE h.charger_id = tg.charger_id AND h.status_updated_at <= %(start)s
      ORDER BY h.status_updated_at DESC
      LIMIT 1
    ) p
    LEFT JOIN code_dictionary cd ON cd.code_group = 'CHARGER_STAT' AND cd.code = CAST(p.status_code AS text)
"""

RUNS_SQL = """
    SELECT started_at FROM collection_run
    WHERE run_type = 'STATUS' AND run_status IN ('SUCCESS', 'PARTIAL')
      AND started_at > %(start)s AND started_at <= %(end)s
"""

STATIONS_SQL = """
    SELECT s.station_id, s.stat_id, s.name, s.latitude, s.longitude, s.busi_id, s.org_name,
           count(c.charger_id) AS fast_charger_count, max(c.output_kw) AS max_output_kw
    FROM station s
    JOIN charger c ON c.station_id = s.station_id AND c.is_fast AND c.del_yn = 'N'
    WHERE EXISTS (SELECT 1 FROM station_access sa WHERE sa.station_id = s.station_id)
    GROUP BY s.station_id
    ORDER BY s.station_id
"""

ACCESS_SQL = """
    SELECT sa.station_id, sa.access_type,
           coalesce(rr.route_no, ir.route_no) AS route_no,
           coalesce(rr.route_name, ir.route_name) AS route_name,
           coalesce(ra.name, ic.name) AS facility_name,
           ra.direction,
           sa.detour_km, sa.mapping_method
    FROM station_access sa
    LEFT JOIN rest_area ra ON ra.rest_area_id = sa.rest_area_id
    LEFT JOIN route rr ON rr.route_id = ra.route_id
    LEFT JOIN interchange ic ON ic.ic_id = sa.ic_id
    LEFT JOIN route ir ON ir.route_id = ic.route_id
    ORDER BY sa.station_id, sa.access_id
"""

OCC30_SQL = """
    SELECT station_id, window_start, occupancy_rate, available, observed_points
    FROM occupancy_30m
    WHERE window_start >= %(start)s AND window_start < %(end)s
"""


def floor_window(t):
    return t.floor(f"{WINDOW_MIN}min")


def export_range(now, days):
    """[start, end): 마지막으로 닫힌 30분 구간까지. 집계가 끝나는 2분 여유를 둔다 (집계 스케줄러와 같은 기준)."""
    end = floor_window(now - pd.Timedelta(minutes=2))
    start = end - pd.Timedelta(days=days)
    return start, end


def valid_points(points, run_starts):
    """시점별 유효 여부: (t - 10분, t + 1분] 안에 성공한 수집 회차 시작이 있으면 True."""
    runs = np.sort(pd.to_datetime(pd.Series(run_starts, dtype="datetime64[ns]")).to_numpy())
    pts = pd.DatetimeIndex(points).to_numpy()
    lo = np.searchsorted(runs, pts - VALID_LOOKBACK.to_timedelta64(), side="right")   # > t-10분
    hi = np.searchsorted(runs, pts + VALID_TOLERANCE.to_timedelta64(), side="right")  # <= t+1분
    return hi > lo


def point_counts(points, chargers, history, stuck_hours=STUCK_HOURS, chunk=288):
    """
    충전소 x 시점별 정규화 상태 수. points: 정렬된 시점, chargers: charger_id/station_id,
    history: charger_id, status_updated_at, normalized_status.
    """
    h = history[["charger_id", "status_updated_at", "normalized_status"]].copy()
    h["status_updated_at"] = h["status_updated_at"].astype("datetime64[ns]")
    h["normalized_status"] = h["normalized_status"].fillna("UNKNOWN")
    h = h.sort_values("status_updated_at", kind="stable")
    ch = chargers[["charger_id", "station_id"]].drop_duplicates("charger_id")
    stuck = pd.Timedelta(hours=stuck_hours)
    points = pd.DatetimeIndex(points).astype("datetime64[ns]")

    parts = []
    for i in range(0, len(points), chunk):
        pts = points[i:i + chunk]
        left = pd.DataFrame({
            "t": np.repeat(pts.to_numpy(), len(ch)),
            "charger_id": np.tile(ch["charger_id"].to_numpy(), len(pts)),
            "station_id": np.tile(ch["station_id"].to_numpy(), len(pts)),
        })
        m = pd.merge_asof(left, h, left_on="t", right_on="status_updated_at", by="charger_id",
                          direction="backward", allow_exact_matches=True)
        ns = m["normalized_status"].fillna("UNKNOWN").to_numpy(dtype=object)
        is_stuck = (ns == "CHARGING") & (m["status_updated_at"] < m["t"] - stuck).to_numpy()
        ns = np.where(is_stuck, "UNKNOWN", ns)
        d = pd.DataFrame({"station_id": m["station_id"], "ts": m["t"]})
        for s in STATUSES:
            d[s.lower()] = (ns == s).astype("int32")
        parts.append(d.groupby(["station_id", "ts"], sort=False).sum().reset_index())
    out = pd.concat(parts, ignore_index=True)
    return out.sort_values(["station_id", "ts"], kind="stable").reset_index(drop=True)


def build_occupancy_5m(start, end, chargers, history, run_starts):
    points = pd.date_range(start, end - pd.Timedelta(minutes=POINT_MIN), freq=f"{POINT_MIN}min")
    counts = point_counts(points, chargers, history)
    valid = pd.Series(valid_points(points, run_starts).astype("int8"), index=points)
    counts["valid"] = counts["ts"].map(valid).astype("int8")
    fast = chargers.groupby("station_id")["charger_id"].nunique().rename("fast_charger_count")
    counts = counts.merge(fast, left_on="station_id", right_index=True)
    cols = ["station_id", "ts", "valid", "fast_charger_count", "available", "charging", "unavailable", "unknown"]
    return counts[cols]


def check_30m(occ5, occ30_db):
    """이 파일로 prep_5m 과 같은 방식으로 만든 30분 값이 DB 값과 같은지 비교. (일치 비율, 비교 행 수)"""
    import prep_5m
    w = prep_5m.windows_from_5m(occ5.copy())
    db = occ30_db.copy()
    db["occupancy_rate"] = pd.to_numeric(db["occupancy_rate"], errors="coerce")
    m = w.merge(db, on=["station_id", "window_start"], suffixes=("", "_db"))
    if m.empty:
        return float("nan"), 0
    a, b = m["occupancy_rate"].to_numpy(dtype=float), m["occupancy_rate_db"].to_numpy(dtype=float)
    same_rate = (np.isnan(a) & np.isnan(b)) | (np.abs(a - b) <= 6e-5)
    same_pts = m["observed_points"].to_numpy() == m["observed_points_db"].to_numpy()
    return float((same_rate & same_pts).mean()), len(m)


def connect(url):
    import psycopg2
    return psycopg2.connect(url)


def read_sql(conn, sql, params=None, parse_dates=None):
    with conn.cursor() as cur:
        cur.execute(sql, params or {})
        cols = [c.name for c in cur.description]
        df = pd.DataFrame(cur.fetchall(), columns=cols)
    for c in parse_dates or []:
        df[c] = pd.to_datetime(df[c])
    return df


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out-dir", required=True)
    ap.add_argument("--days", type=float, default=9, help="뽑을 기간(일). 지난주 같은 시간대 변수 때문에 8일 이상 (기본 9)")
    ap.add_argument("--now", default=None, help="기준 시각 (시험용, 기본은 한국 현재 시각)")
    ap.add_argument("--db-url", default=None, help="기본은 환경변수 EVISION_FORECAST_DB_URL")
    ap.add_argument("--check-30m", action="store_true", help="다시 만든 30분 값이 DB occupancy_30m 과 같은지 비교")
    a = ap.parse_args(argv)

    url = a.db_url or os.environ.get("EVISION_FORECAST_DB_URL")
    if not url:
        print("[오류] DB 주소가 없습니다. EVISION_FORECAST_DB_URL 환경변수를 설정하세요.")
        return 1
    now = pd.Timestamp(a.now) if a.now else pd.Timestamp.now(tz="Asia/Seoul").tz_localize(None)
    start, end = export_range(now, a.days)
    out = Path(a.out_dir)
    out.mkdir(parents=True, exist_ok=True)
    t0 = time.time()
    p = {"start": start.to_pydatetime(), "end": end.to_pydatetime()}

    with connect(url) as conn:
        conn.set_session(readonly=True)
        chargers = read_sql(conn, TARGET_CHARGERS_SQL)
        if chargers.empty:
            print("[오류] 서비스 대상 급속충전기가 없습니다.")
            return 1
        history = read_sql(conn, HISTORY_SQL,
                           p, parse_dates=["status_updated_at", "collected_at"])
        runs = read_sql(conn, RUNS_SQL, {"start": (start - VALID_LOOKBACK).to_pydatetime(),
                                         "end": (end + VALID_TOLERANCE).to_pydatetime()}, parse_dates=["started_at"])
        stations = read_sql(conn, STATIONS_SQL)
        access = read_sql(conn, ACCESS_SQL)
        occ30 = read_sql(conn, OCC30_SQL, p, parse_dates=["window_start"]) if a.check_30m else None
    print(f"DB 읽기: 충전기 {len(chargers):,}대, 이력 {len(history):,}줄, 수집 회차 {len(runs):,}회 ({time.time() - t0:.0f}초)")

    occ5 = build_occupancy_5m(start, end, chargers, history, runs["started_at"])
    valid_share = occ5.drop_duplicates("ts")["valid"].mean()
    print(f"5분 표: {len(occ5):,}행, 충전소 {occ5['station_id'].nunique()}곳, 기간 {start} ~ {end}, "
          f"유효 시점 {valid_share:.1%} ({time.time() - t0:.0f}초)")

    occ5.to_csv(out / "occupancy_5m.csv.gz", index=False)
    history.drop(columns=["normalized_status"]).to_csv(out / "status_history_raw.csv.gz", index=False)
    chargers.to_csv(out / "chargers.csv", index=False)
    stations.to_csv(out / "stations.csv", index=False)
    access.to_csv(out / "station_access.csv", index=False)
    print(f"저장: {out} ({time.time() - t0:.0f}초)")

    if a.check_30m:
        rate, n = check_30m(occ5, occ30)
        print(f"30분 값 대조: DB occupancy_30m 과 {n:,}행 중 {rate:.2%} 일치")
        if n and rate < 0.99:
            print("[경고] 일치율이 99% 미만입니다. 집계 규칙이 바뀌었는지 확인하세요.")
            return 3
    return 0


if __name__ == "__main__":
    sys.exit(main())
