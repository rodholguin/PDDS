import subprocess, datetime, re, sys, time
thresholds=[1,1.25,1.5,1.75,2,2.25,2.5,2.75,3,3.5,4,5,7]
start=datetime.datetime(2026,1,1)
end=datetime.datetime(2029,1,7)
months=[]
d=start
while d<end:
    nd=(d.replace(day=28)+datetime.timedelta(days=4)).replace(day=1)
    months.append((d,min(nd,end)))
    d=nd

def run_query(th, a, b):
    interval=f"{th} days"
    sql=f"""
SET statement_timeout='180s';
WITH s0 AS (
  SELECT id, shipment_code, origin_airport_id o, destination_airport_id d, registration_date reg
  FROM shipment
  WHERE source='HISTORICAL' AND is_inter_continental = true
    AND registration_date >= timestamp '{a:%Y-%m-%d %H:%M:%S}'
    AND registration_date < timestamp '{b:%Y-%m-%d %H:%M:%S}'
  ORDER BY registration_date, id
), scored AS (
  SELECT s0.*, eta.eta
  FROM s0
  CROSS JOIN LATERAL (
    SELECT min(arr) eta FROM (
      (SELECT f.scheduled_arrival arr
       FROM flight f
       WHERE f.origin_airport_id=s0.o AND f.destination_airport_id=s0.d
         AND f.scheduled_departure >= s0.reg
         AND f.scheduled_departure < s0.reg + interval '8 days'
       ORDER BY f.scheduled_arrival
       LIMIT 6)
      UNION ALL
      (SELECT f2.scheduled_arrival arr
       FROM flight f1
       JOIN flight f2 ON f2.origin_airport_id=f1.destination_airport_id
       WHERE f1.origin_airport_id=s0.o
         AND f2.destination_airport_id=s0.d
         AND f1.scheduled_departure >= s0.reg
         AND f1.scheduled_departure < s0.reg + interval '8 days'
         AND f2.scheduled_departure >= f1.scheduled_arrival
         AND f2.scheduled_departure < s0.reg + interval '8 days'
       ORDER BY f2.scheduled_arrival
       LIMIT 60)
    ) x
  ) eta
)
SELECT shipment_code, to_char(reg,'YYYY-MM-DD HH24:MI') reg, to_char(reg + interval '{interval}','YYYY-MM-DD HH24:MI') collapse_at,
       to_char(eta,'YYYY-MM-DD HH24:MI') eta,
       round(extract(epoch from eta-reg)/3600.0,2) required_h,
       o,d
FROM scored
WHERE eta IS NULL OR eta > reg + interval '{interval}'
ORDER BY reg, id
LIMIT 1;
"""
    cmd=['docker','exec','-i','tasfb2b-postgres','psql','-U','tasfb2b','-d','tasfb2b','-q','-t','-A','-F','|','-c',sql]
    p=subprocess.run(cmd, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=220)
    out='\n'.join([line for line in p.stdout.splitlines() if line.strip() and not line.startswith('SET')]).strip()
    if p.returncode!=0:
        return ('ERR', p.stderr[-500:])
    return out or None

results={}
for th in thresholds:
    print('threshold',th, flush=True)
    found=None
    for a,b in months:
        t0=time.time()
        res=run_query(th,a,b)
        print(' ',a.strftime('%Y-%m'), '=>', 'none' if res is None else str(res)[:120], 'sec', round(time.time()-t0,1), flush=True)
        if res:
            found=res; break
    results[th]=found
print('SUMMARY')
for th,res in results.items():
    print(th, res or 'no collapse through dataset')
