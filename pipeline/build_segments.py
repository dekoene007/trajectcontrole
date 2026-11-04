"""Stap 3: trajecten op het wegennet leggen -> echte lengte + polylijn.
Gebruik: python3 build_segments.py export.json  (na enrich.py, leest segments.json)"""
import json, sys, math, heapq
from collections import defaultdict, Counter
from normalise import load, hav, bearing, angdiff

N, W, R = load(*sys.argv[1:])
data = json.load(open('segments.json'))
segs, chains = data['segments'], data['chains']
relmem = {r['id']: r['members'] for r in R}

def oneway(t):
    o = t.get('oneway')
    if o in ('yes', '1', 'true'): return 1
    if o == '-1': return -1
    if o == 'no': return 0
    return 1 if t.get('highway') in ('motorway', 'motorway_link', 'trunk_link') or t.get('junction') == 'roundabout' else 0

# gerichte graaf: edges[u] = [(v, len, way)]
edges = defaultdict(list); E = []   # E = lijst gerichte edges (u,v,way)
for wid, w in W.items():
    t = w.get('tags', {})
    if t.get('highway') in (None, 'cycleway', 'footway', 'path', 'pedestrian', 'steps', 'bridleway', 'track',
                            'services', 'rest_area', 'construction', 'proposed', 'platform', 'corridor'):
        continue
    if t.get('access') in ('no', 'private') or t.get('motor_vehicle') == 'no': continue
    ow = oneway(t); ns = w['nodes']
    for a, b in zip(ns, ns[1:]):
        L = hav(N[a], N[b])
        if ow >= 0: edges[a].append((b, L, wid)); E.append((a, b, wid))
        if ow <= 0: edges[b].append((a, L, wid)); E.append((b, a, wid))

def xy(p, ref):
    k = math.cos(math.radians(ref[0]))
    return ((p[1]-ref[1])*111320*k, (p[0]-ref[0])*110540)

def snap(p, want_br=None, maxd=40, max_ang=35):
    """Dichtste gerichte edge; bij gekende richting enkel edges die ongeveer meelopen."""
    best = None
    for (u, v, wid) in E:
        a, b = N[u], N[v]
        m = 0.0012  # ~130 m marge rond de bounding box van de edge
        if p[0] < min(a[0], b[0]) - m or p[0] > max(a[0], b[0]) + m: continue
        if p[1] < min(a[1], b[1]) - m or p[1] > max(a[1], b[1]) + m: continue
        ax, ay = xy(a, p); bx, by = xy(b, p)
        dx, dy = bx-ax, by-ay; l2 = dx*dx+dy*dy
        tt = 0 if l2 == 0 else max(0, min(1, -(ax*dx+ay*dy)/l2))
        d = math.hypot(ax+tt*dx, ay+tt*dy)
        if d > maxd: continue
        br = bearing(a, b)
        if want_br is not None and angdiff(br, want_br) > max_ang: continue
        if best is None or d < best[0]: best = (d, u, v, tt, wid, br)
    return best

def snaps(p, maxd=80, k=4):
    """k dichtste gerichte edges, elke richting."""
    out = []
    for (u, v, wid) in E:
        a, b = N[u], N[v]; m = 0.0012
        if p[0] < min(a[0], b[0]) - m or p[0] > max(a[0], b[0]) + m: continue
        if p[1] < min(a[1], b[1]) - m or p[1] > max(a[1], b[1]) + m: continue
        ax, ay = xy(a, p); bx, by = xy(b, p)
        dx, dy = bx-ax, by-ay; l2 = dx*dx+dy*dy
        tt = 0 if l2 == 0 else max(0, min(1, -(ax*dx+ay*dy)/l2))
        d = math.hypot(ax+tt*dx, ay+tt*dy)
        if d <= maxd: out.append((d, u, v, tt, wid, bearing(a, b)))
    return sorted(out)[:k]

def best_route(a_pts, b_pts, prefer):
    best = (None, None)
    for x in a_pts:
        for y in b_pts:
            L, poly = route(x, y, prefer)
            if L is not None and (best[0] is None or L < best[0]): best = (L, poly)
    return best

def route(sn, en, prefer):
    """Dijkstra van gesnapt punt sn naar en. prefer = set sectiewegen (goedkoper)."""
    _, su, sv, st, swid, _ = sn; _, eu, ev, et, ewid, _ = en
    Ls = hav(N[su], N[sv])
    def at(u, v, t):
        a, b = N[u], N[v]; return (a[0]+t*(b[0]-a[0]), a[1]+t*(b[1]-a[1]))
    S_pt, E_pt = at(su, sv, st), at(eu, ev, et)
    if (su, sv) == (eu, ev) and et >= st:
        return (et-st)*Ls, [S_pt, E_pt]
    dist = {sv: (1-st)*Ls}; cost = {sv: (1-st)*Ls}; prev = {sv: None}
    pq = [(cost[sv], sv)]; target = eu; Le = hav(N[eu], N[ev])
    while pq:
        c, u = heapq.heappop(pq)
        if c > cost.get(u, 1e18): continue
        if u == target: break
        for v, L, wid in edges[u]:
            w_ = L * (1.0 if wid in prefer else 1.5)
            if c + w_ < cost.get(v, 1e18):
                cost[v] = c + w_; dist[v] = dist[u] + L; prev[v] = u
                heapq.heappush(pq, (cost[v], v))
    if target not in dist: return None, None
    path = []; u = target
    while u is not None: path.append(N[u]); u = prev[u]
    path.reverse()
    return dist[target] + et*Le, [S_pt] + path + [E_pt]

stats = Counter()
for s in segs:
    prefer = {m['ref'] for m in relmem[s['id']] if m['type'] == 'way'}
    s['length_m'] = None; s['polyline'] = None
    if not s['start']:
        # richting proberen via dichtste eenrichtingsweg
        pts = s['nodes']
        sn = [snap(N[n]) for n in pts]
        ow_br = [x[5] for x, n in zip(sn, pts) if x and oneway(W[x[4]].get('tags', {})) != 0]
        if len(pts) >= 2 and ow_br:
            x = sum(math.sin(math.radians(b)) for b in ow_br); y = sum(math.cos(math.radians(b)) for b in ow_br)
            br = (math.degrees(math.atan2(x, y)) + 360) % 360
            ux, uy = math.sin(math.radians(br)), math.cos(math.radians(br))
            order = sorted(pts, key=lambda n: N[n][1]*math.cos(math.radians(N[n][0]))*ux + N[n][0]*uy)
            s['start'], s['end'], s['bearing'] = order[0], order[-1], br
            s['how'] = 'afgeleid_uit_rijbaan_v2'
            s['issues'] = [i for i in s['issues'] if i != 'richting_onbekend']
        elif len(pts) >= 2:
            a_, b_ = max(((x, y) for x in pts for y in pts if x != y), key=lambda q: hav(N[q[0]], N[q[1]]))
            s['start'], s['end'] = a_, b_; s['bearing'] = bearing(N[a_], N[b_]); s['bidirectional'] = True
            s['how'] = 'tweerichting_runtime'
            s['issues'] = [i for i in s['issues'] if i != 'richting_onbekend'] + ['richting_bij_rijden_bepalen']
    if not s['start'] or s['start'] == s['end']:
        stats['geen_route'] += 1; continue
    sn = snap(N[s['start']], s['bearing']) or snap(N[s['start']], s['bearing'], maxd=80) or snap(N[s['start']], s['bearing'], 80, 60)
    en = snap(N[s['end']], s['bearing']) or snap(N[s['end']], s['bearing'], maxd=80) or snap(N[s['end']], s['bearing'], 80, 60)
    L, poly = (route(sn, en, prefer) if sn and en else (None, None))
    if L is None and sn and en:
        sn2 = snap(N[s['start']], s['bearing'], maxd=80); en2 = snap(N[s['end']], s['bearing'], maxd=80)
        for a_, b_ in ((sn2, en), (sn, en2), (sn2, en2)):
            if a_ and b_:
                L, poly = route(a_, b_, prefer)
                if L is not None: break
    vv0 = hav(N[s['start']], N[s['end']])
    if L is None or L/vv0 > 1.6:
        # ruimer zoeken: dichtste wegen in elke richting
        L2, poly2 = best_route(snaps(N[s['start']]), snaps(N[s['end']]), prefer)
        if L2 is not None and L2/vv0 <= 1.6: L, poly = L2, poly2
        elif 'start_einde_tegen_rijrichting' in s['issues']:
            # from/to vermoedelijk omgewisseld in OSM
            L3, poly3 = best_route(snaps(N[s['end']]), snaps(N[s['start']]), prefer)
            if L3 is not None and L3/vv0 <= 1.6:
                s['start'], s['end'] = s['end'], s['start']; L, poly = L3, poly3
                s['issues'] = [i for i in s['issues'] if i != 'start_einde_tegen_rijrichting'] + ['from_to_omgewisseld']
    if L is None:
        s['issues'].append('geen_route_gevonden'); stats['geen_route'] += 1; continue
    pl = []
    for a, b in poly:
        q = [round(a, 6), round(b, 6)]
        if not pl or q != pl[-1]: pl.append(q)
    s['length_m'] = round(L); s['polyline'] = pl
    vv = hav(N[s['start']], N[s['end']])
    ratio = L / vv if vv > 0 else 99
    s['ratio_weg_vogelvlucht'] = round(ratio, 2)
    if ratio > 1.6: s['issues'].append('omweg_verdacht')
    stats['gerouteerd'] += 1

# maxspeed erven van gekoppeld buurtraject
S = {s['id']: s for s in segs}
for c in chains:
    a, b = S[c['van']], S[c['naar']]
    for x, y in ((a, b), (b, a)):
        if not x['maxspeed'] and y['maxspeed']:
            x['maxspeed'] = y['maxspeed']; x['issues'] = [i for i in x['issues'] if i != 'geen_maxspeed'] + ['maxspeed_van_buur']

# koppelingen herberekenen met nieuwe richtingen
chains = []
for a in segs:
    if not a['end']: continue
    for b in segs:
        if b is a or not b['start']: continue
        if b['start'] == a['end']: d, kind = 0, 'gedeeld_punt'
        else:
            d = hav(N[a['end']], N[b['start']])
            if d > 150: continue
            kind = 'nabij'
        if a['bearing'] is not None and b['bearing'] is not None and angdiff(a['bearing'], b['bearing']) > 45: continue
        chains.append(dict(van=a['id'], naar=b['id'], type=kind, afstand_m=round(d)))

crit = {'start_einde_tegen_rijrichting','verdacht_kort','verdacht_lang','meerdere_from_of_to','te_weinig_punten',
        'richting_onbekend','geen_maxspeed','niet_op_weg_te_leggen','geen_route_gevonden','omweg_verdacht','route_te_kort'}
for s in segs:
    s.setdefault('bidirectional', False)
    if s.get('length_m') is not None and s['length_m'] < 100 and 'route_te_kort' not in s['issues']:
        s['issues'].append('route_te_kort')
    s['status'] = 'ok' if s['polyline'] and s['maxspeed'] and not set(s['issues']) & crit else 'nakijken'
json.dump(dict(segments=segs, chains=chains), open('segments_v2.json', 'w'), indent=1, ensure_ascii=False)
print(stats)
print('status', Counter(s['status'] for s in segs))
print('hoe', Counter(s['how'] for s in segs))
print('issues', Counter(i for s in segs for i in s['issues']))
print('koppelingen', len(chains), Counter(c['type'] for c in chains))
r = [s['ratio_weg_vogelvlucht'] for s in segs if s.get('ratio_weg_vogelvlucht')]
r.sort(); print('ratio weg/vogelvlucht mediaan', r[len(r)//2], 'max', r[-1])
