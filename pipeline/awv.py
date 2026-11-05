"""Trajectcontroles uit de open data van het Agentschap Wegen en Verkeer (AWV).

Bron: WFS opendata.apps.mow.vlaanderen.be, lagen
  awv:Trajectcontroles                              (lijn per traject, status, portalen)
  awv:Snelheidsregimes_op_wegen_in_beheer_van_AWV   (limiet per wegnummer en referentiepunt)
Open data van de Vlaamse overheid (Modellicentie Gratis Hergebruik).

Bijzonderheden (vastgesteld 5/11/2026):
 - de coördinaten staan in Lambert 2008 (EPSG:3812) terwijl de laag Lambert 72 (EPSG:31370) opgeeft;
   de server rekent daardoor verkeerd om naar WGS84. We vragen de ruwe coördinaten en rekenen zelf om.
 - de lijn loopt van het eerste naar het tweede portaal = rijrichting.
 - status 'UNKNOWN' = trajecten van de federale politie / politiezones die AWV mee registreert.

Gebruik:
  python3 awv.py fetch uit_trajecten.json uit_regimes.json        (online, GitHub Actions)
  python3 awv.py build trajecten.json regimes.json segments.tsv uit_awv.tsv   -> zet ook samenvatting (JSON) op stdout
"""
import json, math, sys, time, zlib, urllib.parse, urllib.request
from collections import defaultdict

WFS = 'https://opendata.apps.mow.vlaanderen.be/opendata-geoserver/awv/wfs'
UA = 'trajectcontrole-pipeline/1.0 (github.com/dekoene007/trajectcontrole)'
BASE_ID = 800_000_000
GEPLAATST = ('Opgeleverd', 'UNKNOWN')          # statusprefixen die we meenemen
MIN_L, MAX_L = 150, 20_000                      # m

# ---------- Lambert 2008 (EPSG:3812, GRS80) -> WGS84 ----------
_a = 6378137.0; _f = 1/298.257222101; _e = math.sqrt(2*_f - _f*_f)
_p1, _p2 = math.radians(49 + 50/60), math.radians(51 + 10/60)
_p0 = math.radians(50 + 47/60 + 52.134/3600); _l0 = math.radians(4 + 21/60 + 33.177/3600)
_x0, _y0 = 649328.0, 665262.0
def _m(p): return math.cos(p) / math.sqrt(1 - (_e*math.sin(p))**2)
def _t(p): return math.tan(math.pi/4 - p/2) / ((1 - _e*math.sin(p)) / (1 + _e*math.sin(p)))**(_e/2)
_n = (math.log(_m(_p1)) - math.log(_m(_p2))) / (math.log(_t(_p1)) - math.log(_t(_p2)))
_F = _m(_p1) / (_n * _t(_p1)**_n); _r0 = _a * _F * _t(_p0)**_n
def l08_to_wgs84(x, y):
    dx, dy = x - _x0, _r0 - (y - _y0)
    r = math.copysign(math.hypot(dx, dy), _n); th = math.atan2(dx, dy)
    tt = (r / (_a*_F))**(1/_n); phi = math.pi/2 - 2*math.atan(tt)
    for _ in range(10):
        phi = math.pi/2 - 2*math.atan(tt * ((1 - _e*math.sin(phi)) / (1 + _e*math.sin(phi)))**(_e/2))
    return math.degrees(phi), math.degrees(th/_n + _l0)

# ---------- geometrie ----------
def xy(p, ref):
    k = math.cos(math.radians(ref[0])); return ((p[1]-ref[1])*111320*k, (p[0]-ref[0])*110540)
def length(pts): return sum(math.hypot(*xy(b, a)) for a, b in zip(pts, pts[1:]))
def heading(a, b):
    x, y = xy(b, a); return (math.degrees(math.atan2(x, y)) + 360) % 360
def angd(a, b): d = abs(a-b) % 360; return min(d, 360-d)

class Line:
    def __init__(s, pts):
        s.pts = pts; s.ref = pts[0]; s.P = [xy(p, s.ref) for p in pts]; s.cum = [0.0]
        for a, b in zip(s.P, s.P[1:]): s.cum.append(s.cum[-1] + math.hypot(b[0]-a[0], b[1]-a[1]))
        s.L = s.cum[-1]
        s.box = (min(p[0] for p in pts), max(p[0] for p in pts), min(p[1] for p in pts), max(p[1] for p in pts))
    def proj(s, p):
        x, y = xy(p, s.ref); best = None
        for i in range(len(s.P)-1):
            (ax, ay), (bx, by) = s.P[i], s.P[i+1]; dx, dy = bx-ax, by-ay; l2 = dx*dx + dy*dy
            if l2 == 0: continue
            t = max(0, min(1, ((x-ax)*dx + (y-ay)*dy)/l2)); d = math.hypot(x-ax-t*dx, y-ay-t*dy)
            if best is None or d < best[0]: best = (d, s.cum[i] + t*math.sqrt(l2), (math.degrees(math.atan2(dx, dy)) + 360) % 360)
        return best
    def samples(s, step=50):
        out = []
        for i in range(len(s.pts)-1):
            a, b = s.pts[i], s.pts[i+1]; seg = s.cum[i+1] - s.cum[i]
            if seg <= 0: continue
            k = 0
            while k*step < seg:
                t = k*step/seg; out.append(((a[0]+t*(b[0]-a[0]), a[1]+t*(b[1]-a[1])), heading(a, b))); k += 1
        out.append((s.pts[-1], heading(s.pts[-2], s.pts[-1])))
        return out

def overlap(A, B, tol=40):
    """Fractie van lijn A die (in dezelfde rijrichting) binnen tol m van lijn B ligt."""
    if A.box[1] + 0.01 < B.box[0] or A.box[0] - 0.01 > B.box[1] or A.box[3] + 0.015 < B.box[2] or A.box[2] - 0.015 > B.box[3]:
        return 0.0
    S = A.samples(50); hits = 0
    for p, h in S:
        d, _, bh = B.proj(p)
        if d < tol and angd(h, bh) < 45: hits += 1
    return hits / len(S)

# ---------- ophalen ----------
def wfs(params, tries=3):
    url = WFS + '?' + urllib.parse.urlencode(params, quote_via=urllib.parse.quote)
    last = None
    for i in range(tries):
        try:
            req = urllib.request.Request(url, headers={'User-Agent': UA})
            with urllib.request.urlopen(req, timeout=300) as r:
                return json.loads(r.read())
        except Exception as e:
            last = e; time.sleep(20*(i+1))
    raise RuntimeError(f'AWV WFS onbereikbaar: {last}')

def fetch(out_tc, out_reg):
    tc = wfs({'service': 'WFS', 'version': '2.0.0', 'request': 'GetFeature', 'typeNames': 'awv:Trajectcontroles',
              'outputFormat': 'application/json'})
    json.dump(tc, open(out_tc, 'w'))
    wegen = sorted({f['properties']['Wegnummer'] for f in tc['features']
                    if str(f['properties'].get('Status trajectcontrole', '')).startswith(GEPLAATST)})
    feats = []
    for i in range(0, len(wegen), 40):
        chunk = wegen[i:i+40]
        r = wfs({'service': 'WFS', 'version': '2.0.0', 'request': 'GetFeature',
                 'typeNames': 'awv:Snelheidsregimes_op_wegen_in_beheer_van_AWV', 'outputFormat': 'application/json',
                 'propertyName': 'wegnummer,van_refpunt,van_afstand,tot_refpunt,tot_afstand,snelheidsbeperking',
                 'CQL_FILTER': 'wegnummer IN (' + ','.join(f"'{w}'" for w in chunk) + ')'})
        feats += [f['properties'] for f in r['features']]
    json.dump(feats, open(out_reg, 'w'))
    print(f'AWV: {len(tc["features"])} trajecten, {len(feats)} snelheidsregimes op {len(wegen)} wegen')

# ---------- verwerken ----------
def pos(ref, afst):
    try: return float(ref)*1000 + float(afst or 0)
    except (TypeError, ValueError): return None

def limit_for(reg_by_road, weg, v, t):
    lo, hi = min(v, t), max(v, t); w = defaultdict(float)
    for r in reg_by_road.get(weg, []):
        a, b = pos(r['van_refpunt'], r['van_afstand']), pos(r['tot_refpunt'], r['tot_afstand'])
        if a is None or b is None: continue
        ov = min(hi, max(a, b)) - max(lo, min(a, b))
        sb = str(r.get('snelheidsbeperking') or '')
        if ov > 0 and sb.isdigit(): w[int(sb)] += ov
    if not w: return None, 0
    lim, wt = max(w.items(), key=lambda kv: kv[1])
    return lim, wt / sum(w.values())

def build(tc_path, reg_path, ours_tsv, out_tsv):
    tc = json.load(open(tc_path)); regs = json.load(open(reg_path))
    reg_by_road = defaultdict(list)
    for r in regs: reg_by_road[r['wegnummer']].append(r)
    ours = []
    for l in open(ours_tsv, encoding='utf-8'):
        if l.startswith('#') or not l.strip(): continue
        c = l.rstrip('\n').split('\t'); pts = [tuple(map(float, p.split(','))) for p in c[5].split(';')]
        ours.append((int(c[0]), c[1], Line(pts)))
        if c[3] == '1': ours.append((-int(c[0]), c[1], Line(pts[::-1])))
    summ = dict(totaal=len(tc['features']), geplaatst=0, nieuw=0, zelfde=0, lengteverschil=[], uitgesloten=[])
    cand, seen = [], set()
    for f in tc['features']:
        p = f['properties']; st = str(p.get('Status trajectcontrole') or '')
        if not st.startswith(GEPLAATST) or not f.get('geometry'): continue
        summ['geplaatst'] += 1
        key = (p['Wegnummer'], p['Van referentiepunt'], p['Tot referentiepunt'])
        if key in seen: continue
        seen.add(key)
        pts = []
        for line in f['geometry']['coordinates']:
            for c in line:
                q = l08_to_wgs84(c[0], c[1]); q = (round(q[0], 6), round(q[1], 6))
                if not pts or q != pts[-1]: pts.append(q)
        tid = f"{p['TjCID']} {p['Wegnummer']} {p['Van referentiepunt']}->{p['Tot referentiepunt']}"
        if len(pts) < 2: summ['uitgesloten'].append((tid, 'geen geometrie')); continue
        L = length(pts)
        if not (MIN_L <= L <= MAX_L): summ['uitgesloten'].append((tid, f'lengte {L:.0f} m')); continue
        v, t = pos(p['Van referentiepunt'], p['Van afstand referentiepunt']), pos(p['Tot referentiepunt'], p['Tot afstand referentiepunt'])
        if v is not None and t is not None and abs(abs(t - v) - L) > max(300, 0.15*L):
            summ['uitgesloten'].append((tid, f'lijn {L:.0f} m past niet bij referentiepunten {abs(t-v):.0f} m')); continue
        lim, share = limit_for(reg_by_road, p['Wegnummer'], v, t) if v is not None and t is not None else (None, 0)
        if lim is None: summ['uitgesloten'].append((tid, 'geen snelheidslimiet gevonden')); continue
        portals = [x.strip() for x in str(p.get('Portalen') or '').split('<>')]
        cand.append(dict(tid=tid, cid=p['TjCID'], weg=p['Wegnummer'], status=st, lim=lim, share=share, pts=pts, L=L,
                         p_from=portals[0] if len(portals) == 2 else None, p_to=portals[-1] if len(portals) == 2 else None,
                         id=BASE_ID + (zlib.crc32(f"{p['Wegnummer']}|{p['Van referentiepunt']}|{p['Tot referentiepunt']}".encode()) % 50_000_000) * 2))
    # vergelijken met wat we al hebben (OSM + eigen)
    rows = []
    for c in cand:
        A = Line(c['pts']); best = None
        for oid, name, B in ours:
            fr = overlap(A, B)
            if fr > 0 and (best is None or fr > best[0]): best = (fr, oid, name, B)
        if best and best[0] >= 0.3:
            fr, oid, name, B = best
            if fr >= 0.8 and abs(A.L - B.L) < max(150, 0.1*B.L): summ['zelfde'] += 1
            else: summ['lengteverschil'].append(dict(awv=c['tid'], awv_m=round(A.L), ons=oid, ons_naam=name, ons_m=round(B.L), overlap=round(fr, 2)))
            continue
        summ['nieuw'] += 1; rows.append(c)
    # koppelingen via gedeelde portalen (einde-portaal = begin-portaal)
    by_from = defaultdict(list)
    for c in rows:
        if c['p_from'] and c['p_from'] not in ('UNK',): by_from[c['p_from']].append(c['id'])
    byid = {c['id']: c for c in rows}
    def next_ok(a, b):
        # zelfde rijrichting: einde van a ligt bij begin van b en de richting loopt door (geen keerpunt)
        end_h = heading(a['pts'][-2], a['pts'][-1]); start_h = heading(b['pts'][0], b['pts'][1])
        return math.hypot(*xy(b['pts'][0], a['pts'][-1])) < 200 and angd(end_h, start_h) < 60
    with open(out_tsv, 'w', encoding='utf-8') as f:
        for c in sorted(rows, key=lambda c: c['id']):
            cands = by_from.get(c['p_to'], []) if c['p_to'] and c['p_to'] != 'UNK' else []
            nxt = ','.join(str(i) for i in cands if i != c['id'] and next_ok(c, byid[i]))
            name = f"Trajectcontrole {c['weg'][0]}{int(c['weg'][1:5])} AWV {c['cid']}"
            poly = ';'.join(f'{a:.6f},{b:.6f}' for a, b in c['pts'])
            f.write(f"{c['id']}\t{name}\t{c['lim']}\t0\t{nxt}\t{poly}\n")
    summ['limiet_onzeker'] = sum(1 for c in rows if c['share'] < 0.8)
    print(json.dumps(summ, ensure_ascii=False))

if __name__ == '__main__':
    if sys.argv[1] == 'fetch': fetch(sys.argv[2], sys.argv[3])
    elif sys.argv[1] == 'build': build(*sys.argv[2:6])
