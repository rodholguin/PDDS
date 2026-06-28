
#!/usr/bin/env python3
import csv
import subprocess
import sys
import time
from datetime import datetime, timedelta
from pathlib import Path

BASE = Path('/home/1inf54.984.3c')
LOG = BASE / 'deadline_scan.log'
OUT = BASE / 'deadline_scan_results.csv'
PROGRESS = BASE / 'deadline_scan_progress.txt'

def log(msg):
    line = f"{datetime.now().isoformat(timespec='seconds')} {msg}"
    print(line, flush=True)
    with LOG.open('a', encoding='utf-8') as f:
        f.write(line + '\n')

def psql(sql, timeout=None):
    cmd = ['docker','exec','-i','tasfb2b-postgres','psql','-U','tasfb2b','-d','tasfb2b','-q','-t','-A','-F','|','-c',sql]
    return subprocess.run(cmd, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout)

def psql_plain(sql, timeout=None):
    cmd = ['docker','exec','-i','tasfb2b-postgres','psql','-U','tasfb2b','-d','tasfb2b','-v','ON_ERROR_STOP=1','-c',sql]
    return subprocess.run(cmd, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout)

log('deadline scan starting')
log('creating helper indexes concurrently if needed')
for sql in [
    "CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_flight_od_departure_arrival ON flight(origin_airport_id, destination_airport_id, scheduled_departure, scheduled_arrival)",
    "CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_shipment_source_inter_reg_id ON shipment(source, is_inter_continental, registration_date, id)"
]:
    started = time.time()
    r = psql_plain(sql, timeout=None)
    log(f'index done rc={r.returncode} sec={time.time()-started:.1f} stderr={r.stderr.strip()[-300:]}')
    if r.returncode != 0:
        raise SystemExit(r.returncode)

thresholds = [1.0, 1.25, 1.5, 1.75, 2.0, 2.25, 2.5, 2.75, 3.0, 3.5, 4.0, 5.0, 7.0, 10.0]
remaining_inter = set(thresholds)
intra_found = None
inter_found = {}

if not OUT.exists():
    with OUT.open('w', newline='', encoding='utf-8') as f:
        w = csv.writer(f)
        w.writerow(['scope','threshold_days','shipment_code','registration_at','collapse_at','eta','required_hours','origin_id','destination_id','analysis_note'])

def append_row(row):
    with OUT.open('a', newline='', encoding='utf-8') as f:
        csv.writer(f).writerow(row)

def query_day(day_start, day_end, inter_thresholds, need_intra):
    values = ','.join(f'({th})' for th in sorted(inter_thresholds)) or '(NULL::numeric)'
    include_intra = 'true' if need_intra else 'false'
    sql = f"""
WITH inter_thresholds(threshold_days) AS (VALUES {values}),
inter_shipments AS (
  SELECT id, shipment_code, origin_airport_id o, destination_airport_id d, registration_date reg, true AS inter
  FROM shipment
  WHERE source='HISTORICAL'
    AND is_inter_continental = true
    AND registration_date >= timestamp '{day_start:%Y-%m-%d %H:%M:%S}'
    AND registration_date < timestamp '{day_end:%Y-%m-%d %H:%M:%S}'
),
intra_shipments AS (
  SELECT id, shipment_code, origin_airport_id o, destination_airport_id d, registration_date reg, false AS inter
  FROM shipment
  WHERE {include_intra}
    AND source='HISTORICAL'
    AND is_inter_continental = false
    AND registration_date >= timestamp '{day_start:%Y-%m-%d %H:%M:%S}'
    AND registration_date < timestamp '{day_end:%Y-%m-%d %H:%M:%S}'
),
all_shipments AS (
  SELECT * FROM inter_shipments
  UNION ALL
  SELECT * FROM intra_shipments
),
scored AS (
  SELECT s.*, eta.eta
  FROM all_shipments s
  CROSS JOIN LATERAL (
    SELECT min(arr) eta FROM (
      (SELECT f.scheduled_arrival arr
       FROM flight f
       WHERE f.origin_airport_id=s.o AND f.destination_airport_id=s.d
         AND f.scheduled_departure >= s.reg
         AND f.scheduled_departure < s.reg + interval '10 days'
       ORDER BY f.scheduled_arrival
       LIMIT 8)
      UNION ALL
      (SELECT f2.scheduled_arrival arr
       FROM flight f1
       JOIN flight f2 ON f2.origin_airport_id=f1.destination_airport_id
       WHERE f1.origin_airport_id=s.o
         AND f2.destination_airport_id=s.d
         AND f1.scheduled_departure >= s.reg
         AND f1.scheduled_departure < s.reg + interval '10 days'
         AND f2.scheduled_departure >= f1.scheduled_arrival
         AND f2.scheduled_departure < s.reg + interval '10 days'
       ORDER BY f2.scheduled_arrival
       LIMIT 80)
    ) x
  ) eta
),
checks AS (
  SELECT 'INTER'::text scope, it.threshold_days::numeric threshold_days, s.*
  FROM scored s JOIN inter_thresholds it ON s.inter = true AND it.threshold_days IS NOT NULL
  UNION ALL
  SELECT 'INTRA'::text scope, 1.0::numeric threshold_days, s.*
  FROM scored s WHERE s.inter = false
),
failures AS (
  SELECT *, row_number() OVER (PARTITION BY scope, threshold_days ORDER BY reg, id) rn
  FROM checks
  WHERE eta IS NULL OR eta > reg + (threshold_days::text || ' days')::interval
)
SELECT scope,
       threshold_days,
       shipment_code,
       to_char(reg,'YYYY-MM-DD HH24:MI') registration_at,
       to_char(reg + (threshold_days::text || ' days')::interval,'YYYY-MM-DD HH24:MI') collapse_at,
       coalesce(to_char(eta,'YYYY-MM-DD HH24:MI'),'NO_ROUTE') eta,
       coalesce(round(extract(epoch from eta-reg)/3600.0,2)::text,'INF') required_hours,
       o,
       d,
       CASE WHEN eta IS NULL THEN 'no route within 10 days/direct+1hop' ELSE 'direct_or_1_connection_eta' END note
FROM failures
WHERE rn=1
ORDER BY scope, threshold_days;
"""
    return psql(sql, timeout=None)

start = datetime(2026,1,1)
end = datetime(2029,1,7)
day = start
while day < end and (remaining_inter or intra_found is None):
    nxt = min(day + timedelta(days=1), end)
    t0 = time.time()
    log(f'scanning day {day:%Y-%m-%d}; remaining_inter={sorted(remaining_inter)} need_intra={intra_found is None}')
    r = query_day(day, nxt, remaining_inter, intra_found is None)
    elapsed = time.time() - t0
    if r.returncode != 0:
        log(f'query failed day={day:%Y-%m-%d} rc={r.returncode} stderr={r.stderr[-1000:]}')
        time.sleep(10)
        continue
    lines = [line for line in r.stdout.splitlines() if line.strip()]
    for line in lines:
        parts = line.split('|')
        if len(parts) < 10:
            continue
        scope = parts[0]
        th = float(parts[1])
        if scope == 'INTRA' and intra_found is None:
            intra_found = parts
            append_row(parts)
            log(f'FOUND INTRA 1d: {parts}')
        elif scope == 'INTER' and th in remaining_inter:
            inter_found[th] = parts
            remaining_inter.remove(th)
            append_row(parts)
            log(f'FOUND INTER {th}d: {parts}')
    with PROGRESS.open('w', encoding='utf-8') as f:
        f.write(f'last_day={day:%Y-%m-%d}\n')
        f.write(f'remaining_inter={sorted(remaining_inter)}\n')
        f.write(f'intra_found={intra_found}\n')
        f.write(f'inter_found={inter_found}\n')
    log(f'day {day:%Y-%m-%d} done sec={elapsed:.1f} rows={len(lines)}')
    day = nxt

log('deadline scan finished')
log(f'intra_found={intra_found}')
log(f'inter_found={inter_found}')
