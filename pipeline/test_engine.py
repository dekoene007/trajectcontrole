"""Gesimuleerde ritten over echte OSM-trajecten om de rekenmotor te testen."""
import json, math, random
from engine import Engine, Segment, Polyline, Fix, fine_threshold

d = json.load(open('segments_v2.json'))
S = {s['id']: s for s in d['segments']}
nexts = {}
for c in d['chains']: nexts.setdefault(c['van'], []).append(c['naar'])

def mkseg(i):
    s = S[i]
    return Segment(i, s['name'] or str(i), s['maxspeed'], Polyline(s['polyline']), nexts.get(i, []))

def all_segments():
    """Alle bruikbare trajecten; tweerichtingstrajecten ook omgekeerd (id negatief)."""
    out = []
    for i, s in S.items():
        if s['status'] != 'ok': continue
        out.append(mkseg(i))
        if s.get('bidirectional'):
            out.append(Segment(-i, (s['name'] or str(i)) + ' (omgekeerd)', s['maxspeed'], Polyline(s['polyline'][::-1]), []))
    return out

def route_points(seg_ids, lead_m=400, tail_m=300):
    """Rijlijn: aanloop (verlengd eerste stuk) + trajecten + uitloop."""
    pts = []
    for i in seg_ids:
        pl = [tuple(p) for p in S[abs(i)]['polyline']]
        if i < 0: pl = pl[::-1]
        for q in pl:
            if not pts or q != pts[-1]: pts.append(q)
    def ext(a, b, m):
        k = math.cos(math.radians(a[0]))
        dx, dy = (a[1]-b[1])*k, (a[0]-b[0]); n = math.hypot(dx, dy)
        return (a[0] + dy/n * m/111000, a[1] + dx/n * m/(111000*k))
    return [ext(pts[0], pts[1], lead_m)] + pts + [ext(pts[-1], pts[-2], tail_m)], lead_m

def drive(pts, speed_fn, noise=4.0, gaps=(), bad_acc=(), seed=1, start_offset=0.0):
    """Rijdt langs pts aan speed_fn(afstand)->m/s; 1 Hz fixes. Geeft fixes + functie afstand->tijd."""
    random.seed(seed)
    P = Polyline(pts); L = P.length
    # afstand -> positie
    def pos(s):
        for i in range(len(P.cum)-1):
            if P.cum[i+1] >= s:
                f = (s - P.cum[i]) / (P.cum[i+1]-P.cum[i])
                a, b = pts[i], pts[i+1]
                return (a[0]+f*(b[0]-a[0]), a[1]+f*(b[1]-a[1]))
        return pts[-1]
    t, s, dt = 0.0, start_offset, 0.05
    times = []  # (t, s) fijnmazig voor ground truth
    fixes = []; next_fix = 0.0
    while s < L:
        v = speed_fn(s)
        if t >= next_fix:
            if not any(a <= t < b for a, b in gaps):
                la, lo = pos(s)
                k = math.cos(math.radians(la))
                la += random.gauss(0, noise)/111000; lo += random.gauss(0, noise)/(111000*k)
                acc = 80.0 if any(a <= s < b for a, b in bad_acc) else 5.0
                fixes.append(Fix(t, la, lo, v + random.gauss(0, 0.3), acc))
            next_fix += 1.0
        times.append((t, s)); s += v*dt; t += dt
    def t_at(dist):
        for tt, ss in times:
            if ss >= dist: return tt
        return times[-1][0]
    return fixes, t_at

def truth(seg_ids, lead, t_at):
    out = []; off = lead
    for i in seg_ids:
        L = Polyline(S[abs(i)]['polyline']).length
        t0, t1 = t_at(off), t_at(off+L)
        out.append(dict(seg=i, t_entry=t0, t_exit=t1, avg=L/(t1-t0)*3.6)); off += L
    return out

def run(name, seg_ids, speed_fn, **kw):
    pts, lead = route_points(seg_ids)
    fixes, t_at = drive(pts, speed_fn, **kw)
    ev = []
    eng = Engine(all_segments(), on_event=ev.append)
    for f in fixes: eng.on_fix(f)
    gt = truth(seg_ids, lead, t_at)
    print(f'\n== {name}')
    ok = True
    for g in gt:
        r = next((r for r in eng.results if r['segment'] == g['seg']), None)
        if not r:
            print(f"  traject {g['seg']}: NIET GEMETEN"); ok = False; continue
        de, da = r['t_entry']-g['t_entry'], r['avg']-g['avg']
        good = abs(de) < 1.5 and abs(da) < 1.0
        ok &= good
        print(f"  traject {g['seg']} ({S[abs(g['seg'])]['maxspeed']} km/u): echt gem. {g['avg']:.1f}, app {r['avg']:.1f} "
              f"(fout {da:+.2f} km/u), instaptijd fout {de:+.2f} s{' [geschat]' if r['estimated'] else ''} -> {'OK' if good else 'FOUT'}")
    print('  events:', [e[0] for e in ev])
    return ok

A, B = 15484933, 15484936   # aaneensluitend: 50 km/u -> 30 km/u
X = 400 + Polyline(S[A]['polyline']).length   # grens A/B op de rijlijn
res = []
res.append(run('1. Aaneensluitend, constante snelheid', [A, B], lambda s: 52/3.6 if s < X else 33/3.6))
res.append(run('2. Variabele snelheid + GPS-ruis 8 m', [A, B],
               lambda s: (45 + 15*math.sin(s/80))/3.6 if s < X else (28 + 8*math.sin(s/50))/3.6, noise=8.0, seed=3))
res.append(run('3. Tunnel: 25 s geen GPS midden in traject 1', [A, B], lambda s: 50/3.6 if s < X else 30/3.6, gaps=[(40, 65)]))
res.append(run('4. Slechte GPS bij de start (eerste 250 m van traject 1)', [A, B],
               lambda s: 50/3.6 if s < X else 30/3.6, bad_acc=[(380, 650)]))
# E40 richting kust: Zwijnaarde -> Drongen -> Aalter -> Beernem (3 aaneensluitende trajecten, 120 km/u)
E1, E2, E3 = 15686089, 15686121, 15686129
b1 = 400 + Polyline(S[E1]['polyline']).length; b2 = b1 + Polyline(S[E2]['polyline']).length
def e40_profile(s):
    if s < b1: return (118 + 6*math.sin(s/900))/3.6          # rond de limiet
    if s < b2: return (128 + 5*math.sin(s/700))/3.6          # te snel -> rood
    return (110 + 10*math.sin(s/1200))/3.6
res.append(run('5. E40 richting kust, 3 trajecten na elkaar, GPS-ruis 6 m + 30 s tunnel',
               [E1, E2, E3], e40_profile, noise=6.0, gaps=[(200, 230)], seed=7))
bid = next(i for i, x in S.items() if x['status'] == 'ok' and x.get('bidirectional') and x['length_m'] > 500)
res.append(run(f'6. Tweerichtingstraject {bid}, in omgekeerde richting gereden', [-bid], lambda s: 48/3.6, noise=5.0))
print('\nGESLAAGD' if all(res) else '\nNIET ALLES GESLAAGD', f'({sum(res)}/{len(res)})')
print('\nBoetedrempels: ', {l: round(fine_threshold(l), 1) for l in (30, 50, 70, 90, 120)})
