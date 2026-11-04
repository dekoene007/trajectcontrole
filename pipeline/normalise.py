"""Opkuis + koppeling van OSM average_speed-relaties (prototype).
Gebruik: python3 -I normalise.py export.json  -> schrijft segments.json + report.json"""
import json, sys, math
from collections import Counter, defaultdict

def hav(a, b):
    R = 6371000.0
    la1, lo1, la2, lo2 = map(math.radians, (a[0], a[1], b[0], b[1]))
    h = math.sin((la2-la1)/2)**2 + math.cos(la1)*math.cos(la2)*math.sin((lo2-lo1)/2)**2
    return 2*R*math.asin(math.sqrt(h))

def bearing(a, b):
    la1, lo1, la2, lo2 = map(math.radians, (a[0], a[1], b[0], b[1]))
    y = math.sin(lo2-lo1)*math.cos(la2)
    x = math.cos(la1)*math.sin(la2) - math.sin(la1)*math.cos(la2)*math.cos(lo2-lo1)
    return (math.degrees(math.atan2(y, x)) + 360) % 360

def angdiff(a, b):
    d = abs(a-b) % 360
    return min(d, 360-d)

def load(*paths):
    """Een of meer Overpass-exports samenvoegen (latere bestanden vullen aan)."""
    seen = {}
    for path in paths:
        for e in json.load(open(path))['elements']:
            k = (e['type'], e['id'])
            if k not in seen or len(e) > len(seen[k]):
                seen[k] = e
    E = list(seen.values())
    N = {e['id']: (e['lat'], e['lon']) for e in E if e['type'] == 'node' and 'lat' in e}
    W = {e['id']: e for e in E if e['type'] == 'way'}
    R = [e for e in E if e['type'] == 'relation']
    return N, W, R

def node_bearing(nid, N, W, node_ways):
    """Rijrichting op het punt, enkel als het op een eenrichtingsweg ligt."""
    for wid in node_ways.get(nid, []):
        w = W[wid]; t = w.get('tags', {})
        one = t.get('oneway')
        if one not in ('yes', '-1', '1') and t.get('highway') not in ('motorway', 'motorway_link'):
            continue
        nodes = w['nodes']; i = nodes.index(nid)
        a, b = (nodes[i-1], nodes[i]) if i > 0 else (nodes[i], nodes[i+1])
        if a not in N or b not in N:
            continue
        br = bearing(N[a], N[b])
        if one == '-1':
            br = (br + 180) % 360
        return br
    return None

def normalise(N, W, R):
    node_ways = defaultdict(list)
    for wid, w in W.items():
        for n in w.get('nodes', []):
            node_ways[n].append(wid)
    segs = []
    for r in R:
        t = r.get('tags', {})
        pts = {}
        for m in r['members']:
            if m['type'] == 'node' and m['ref'] in N:
                pts.setdefault(m['ref'], set()).add(m['role'] or '?')
        frm = [n for n, ro in pts.items() if 'from' in ro]
        to = [n for n, ro in pts.items() if 'to' in ro]
        dev = [n for n, ro in pts.items() if 'device' in ro]
        issues = []
        ms = t.get('maxspeed')
        try:
            ms = int(ms)
        except (TypeError, ValueError):
            issues.append('geen_maxspeed'); ms = None
        brs = [b for b in (node_bearing(n, N, W, node_ways) for n in pts) if b is not None]
        dir_br = None
        if brs:
            x = sum(math.sin(math.radians(b)) for b in brs); y = sum(math.cos(math.radians(b)) for b in brs)
            dir_br = (math.degrees(math.atan2(x, y)) + 360) % 360
        # start / einde
        if len(frm) == 1 and len(to) == 1:
            start, end, how = frm[0], to[0], 'from/to'
            if dir_br is None:
                dir_br = bearing(N[start], N[end])
        elif len(pts) >= 2 and dir_br is not None:
            ux, uy = math.sin(math.radians(dir_br)), math.cos(math.radians(dir_br))
            def proj(n):
                la, lo = N[n]
                return lo*math.cos(math.radians(la))*ux + la*uy
            order = sorted(pts, key=proj)
            start = frm[0] if len(frm) == 1 else order[0]
            end = to[0] if len(to) == 1 else order[-1]
            how = 'afgeleid_uit_rijrichting' if not (frm and to) else 'gemengd'
        else:
            start = end = None
            how = 'onbepaald'
            issues.append('te_weinig_punten' if len(pts) < 2 else 'richting_onbekend')
        if len(frm) > 1 or len(to) > 1:
            issues.append('meerdere_from_of_to')
        length = None
        if start and end:
            length = hav(N[start], N[end])
            if length < 200: issues.append('verdacht_kort')
            if length > 20000: issues.append('verdacht_lang')
            sb = bearing(N[start], N[end])
            if dir_br is not None and angdiff(sb, dir_br) > 60 and length > 300:
                issues.append('start_einde_tegen_rijrichting')
        segs.append(dict(id=r['id'], name=t.get('name'), description=t.get('description'),
                         maxspeed=ms, start=start, end=end, how=how, bearing=dir_br,
                         length_m_vogelvlucht=round(length) if length else None,
                         n_from=len(frm), n_to=len(to), n_device=len(dev), issues=issues,
                         nodes=sorted(pts)))
    # koppelingen
    by_node = defaultdict(list)
    for s in segs:
        for n in s['nodes']:
            by_node[n].append(s['id'])
    S = {s['id']: s for s in segs}
    chains = []
    for a in segs:
        if not a['end']: continue
        for b in segs:
            if b is a or not b['start']: continue
            if b['start'] == a['end'] or (a['end'] in b['nodes'] and a['end'] != b['end']):
                d, kind = 0, 'gedeeld_punt'
            else:
                d = hav(N[a['end']], N[b['start']])
                if d > 150: continue
                kind = 'nabij'
            if a['bearing'] is not None and b['bearing'] is not None and angdiff(a['bearing'], b['bearing']) > 45:
                continue
            chains.append(dict(van=a['id'], naar=b['id'], type=kind, afstand_m=round(d)))
    shared = {n: ids for n, ids in by_node.items() if len(ids) > 1}
    return segs, chains, shared

if __name__ == '__main__':
    N, W, R = load(sys.argv[1])
    segs, chains, shared = normalise(N, W, R)
    json.dump(dict(segments=segs, chains=chains), open('segments.json', 'w'), indent=1, ensure_ascii=False)
    print('relaties', len(segs))
    print('hoe bepaald', Counter(s['how'] for s in segs))
    print('issues', Counter(i for s in segs for i in s['issues']))
    print('bruikbaar (start+einde+maxspeed, geen kritieke issue)',
          sum(1 for s in segs if s['start'] and s['maxspeed'] and not set(s['issues']) & {'start_einde_tegen_rijrichting','verdacht_kort','verdacht_lang','meerdere_from_of_to'}))
    print('koppelingen', len(chains), Counter(c['type'] for c in chains))
    print('punten in >1 relatie', len(shared))
