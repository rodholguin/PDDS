import subprocess, csv, bisect, heapq, time, math, sys
from collections import defaultdict
from datetime import datetime, timezone

PSQL = ['docker','exec','-i','tasfb2b-postgres','psql','-U','tasfb2b','-d','tasfb2b','-q','-A','-F',',','-c']

def run_copy(sql):
    cmd = ['docker','exec','-i','tasfb2b-postgres','psql','-U','tasfb2b','-d','tasfb2b','-q','-c', f"COPY ({sql}) TO STDOUT WITH CSV"]
    return subprocess.Popen(cmd, stdout=subprocess.PIPE, text=True)

print('loading flights...', flush=True)
flight_sql = """
select origin_airport_id, destination_airport_id,
       extract(epoch from scheduled_departure)::bigint,
       extract(epoch from scheduled_arrival)::bigint
from flight
where scheduled_departure >= timestamp '2026-01-01'
  and scheduled_departure < timestamp '2029-01-13'
order by origin_airport_id, scheduled_departure
"""
proc = run_copy(flight_sql)
flights = defaultdict(list)
for row in csv.reader(proc.stdout):
    o,d,dep,arr = map(int,row)
    flights[o].append((dep,arr,d))
proc.wait()
flight_deps = {o:[x[0] for x in lst] for o,lst in flights.items()}
print('origins', len(flights), 'flights', sum(len(v) for v in flights.values()), flush=True)

def earliest_arrival(origin, dest, reg, max_days=10, max_legs=3):
    if origin == dest:
        return reg, 0
    cutoff = reg + max_days*86400
    pq = [(reg, origin, 0)]
    best = {(origin,0): reg}
    best_dest = None
    while pq:
        t, a, legs = heapq.heappop(pq)
        if best.get((a,legs)) != t:
            continue
        if best_dest is not None and t >= best_dest:
            continue
        if legs >= max_legs:
            continue
        lst = flights.get(a)
        if not lst:
            continue
        deps = flight_deps[a]
        i = bisect.bisect_left(deps, t)
        # enumerate departures until cutoff or current best destination arrival
        local_limit = cutoff if best_dest is None else min(cutoff, best_dest)
        while i < len(lst):
            dep, arr, b = lst[i]
            if dep > local_limit:
                break
            if arr > cutoff:
                i += 1; continue
            nl = legs + 1
            if b == dest:
                if best_dest is None or arr < best_dest:
                    best_dest = arr
            key=(b,nl)
            if arr < best.get(key, 10**18):
                best[key]=arr
                heapq.heappush(pq,(arr,b,nl))
            i += 1
    return (best_dest, None) if best_dest is not None else (None, None)

thresholds_days = [1, 1.25, 1.5, 1.75, 2, 2.25, 2.5, 3, 4, 5, 7]
first = {d: None for d in thresholds_days}
checked = 0
late_examples = []
start=time.time()
# exact chronological scan until end of 2027 first; if no collapse for large thresholds by then, that's enough for planning.
ship_sql = """
select shipment_code, origin_airport_id, destination_airport_id,
       extract(epoch from registration_date)::bigint,
       is_inter_continental
from shipment
where source='HISTORICAL'
  and registration_date >= timestamp '2026-01-01'
  and registration_date < timestamp '2028-01-01'
order by registration_date, id
"""
proc = run_copy(ship_sql)
for row in csv.reader(proc.stdout):
    code,o,d,reg,is_inter = row
    is_inter = (is_inter == 't')
    if not is_inter:
        # user asked to keep intra at 1 day; we still track if intra itself would fail.
        applicable = [1]
    o=int(o); d=int(d); reg=int(reg)
    eta,_ = earliest_arrival(o,d,reg,max_days=10,max_legs=3)
    checked += 1
    if eta is None:
        required_days = float('inf')
    else:
        required_days = (eta-reg)/86400.0
    for th in thresholds_days:
        if first[th] is not None:
            continue
        deadline_days = th if is_inter else 1.0
        if required_days > deadline_days + 1e-9:
            first[th] = (code, reg, eta, required_days, is_inter, o, d)
    if checked % 100000 == 0:
        done = sum(v is not None for v in first.values())
        print('checked', checked, 'done', done, 'elapsed', round(time.time()-start,1), flush=True)
    if all(first[d] is not None for d in thresholds_days if d <= 3):
        # Continue a little for 4/5/7? no, need approx to 1 year. Don't break early for larger thresholds.
        pass
proc.wait()
print('checked total', checked, 'elapsed', time.time()-start, flush=True)
for th in thresholds_days:
    item=first[th]
    if item is None:
        print(th, 'no collapse through 2027-12-31')
    else:
        code,reg,eta,req,is_inter,o,d=item
        print(th, code, datetime.fromtimestamp(reg, timezone.utc).strftime('%Y-%m-%d %H:%M'), 'collapse', datetime.fromtimestamp(reg+int(th*86400 if is_inter else 86400), timezone.utc).strftime('%Y-%m-%d %H:%M'), 'eta', None if eta is None else datetime.fromtimestamp(eta, timezone.utc).strftime('%Y-%m-%d %H:%M'), 'req_days', round(req,4), 'inter', is_inter, 'od', o, d)
