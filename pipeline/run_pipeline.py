"""Wekelijkse update van de trajectcontroles.

  python3 pipeline/run_pipeline.py --data data              # haalt op bij Overpass (GitHub Actions)
  python3 pipeline/run_pipeline.py --data data --offline export1.json export2.json   # test met bestaande exports

Stappen:
 1. basisquery bij Overpass (alle Belgische average_speed-relaties + wegen errond)
 2. verwerken (enrich + build_segments)
 3. aanvullende query voor trajecten zonder wegdata, ophalen, opnieuw verwerken
 4. exporteren naar segments.tsv
 5. CONTROLES: niet publiceren als het resultaat verdacht is
 6. wijzigingsrapport t.o.v. de vorige versie + meta.json

Exitcodes: 0 = ok (gewijzigd of niet), 2 = controle mislukt (niets gepubliceerd), 1 = technische fout.
Schrijft voor GitHub Actions 'changed=true/false' en 'failed=true/false' naar $GITHUB_OUTPUT.
"""
import argparse, datetime, hashlib, json, math, os, shutil, subprocess, sys, tempfile, time, urllib.parse, urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
ENDPOINTS = ['https://overpass-api.de/api/interpreter', 'https://overpass.private.coffee/api/interpreter',
             'https://maps.mail.ru/osm/tools/overpass/api/interpreter']
USER_AGENT = 'trajectcontrole-pipeline/1.0 (github.com/dekoene007/trajectcontrole)'

# --- controles (bewust conservatief) ---
MIN_SEGMENTS = 100          # absolute ondergrens
MAX_DROP = 0.10             # niet meer dan 10 % minder dan de vorige versie
MIN_E40 = 15                # E40 moet (bijna) volledig blijven (nu 17)


DATA_DIR = None
OSM_REL = [None]


def log(*a): print(*a, flush=True)


def overpass(query, out_path, min_relations=0, min_elements=0):
    """Haalt op bij de eerste server met een geldig antwoord. min_relations: weiger een antwoord met
    duidelijk minder relaties (verouderde spiegelserver) en probeer de volgende; zo niet, neem het beste."""
    body = urllib.parse.urlencode({'data': query}).encode()
    last = None; best = None
    for attempt in range(3):
        for ep in ENDPOINTS:
            try:
                req = urllib.request.Request(ep, data=body, headers={'User-Agent': USER_AGENT})
                with urllib.request.urlopen(req, timeout=240) as r:
                    raw = r.read()
                d = json.loads(raw)
                if 'elements' not in d: raise ValueError('geen elements in antwoord')
                if 'remark' in d and ('error' in d['remark'].lower() or 'timed out' in d['remark'].lower()):
                    raise ValueError('Overpass-fout: ' + d['remark'][:200])
                nrel = sum(1 for e in d['elements'] if e['type'] == 'relation')
                log(f'  {ep}: {len(d["elements"])} elementen, {nrel} relaties')
                if len(d['elements']) < min_elements:
                    raise ValueError(f'te weinig elementen ({len(d["elements"])} < {min_elements}), server overbelast?')
                if nrel < min_relations:
                    log(f'  -> minder relaties dan verwacht ({min_relations}), mogelijk verouderde server; volgende proberen')
                    if best is None or nrel > best[0]: best = (nrel, raw, ep)
                    continue
                open(out_path, 'wb').write(raw)
                return nrel
            except Exception as e:
                last = e; log(f'  {ep} mislukt: {e}')
        time.sleep(30 * (attempt + 1))
    if best is not None and best[0] >= 0.9 * min_relations:
        log(f'  geen server voldeed volledig; beste antwoord gebruikt: {best[2]} ({best[0]} relaties)')
        open(out_path, 'wb').write(best[1]); return best[0]
    raise RuntimeError(f'Overpass: geen bruikbaar antwoord (beste: {best[0] if best else 0} relaties, verwacht ~{min_relations}; laatste fout: {last})')


def run(script, *args, cwd):
    r = subprocess.run([sys.executable, os.path.join(HERE, script), *args], cwd=cwd, capture_output=True, text=True)
    if r.returncode != 0:
        log(r.stdout); log(r.stderr); raise RuntimeError(f'{script} faalde')
    return r.stdout


def read_tsv(path):
    """-> (header, {id: (naam, limiet, lengte_m, regel)})"""
    if not os.path.exists(path): return None, {}
    head, out = None, {}
    for line in open(path, encoding='utf-8'):
        if line.startswith('#'):
            head = head or line.strip(); continue
        c = line.rstrip('\n').split('\t')
        if len(c) < 6: continue
        pts = [tuple(map(float, p.split(','))) for p in c[5].split(';')]
        L = 0.0
        for (a1, b1), (a2, b2) in zip(pts, pts[1:]):
            k = math.cos(math.radians(a1))
            L += math.hypot((b2 - b1) * 111320 * k, (a2 - a1) * 110540)
        out[int(c[0])] = (c[1], int(c[2]), L, line)
    return head, out


AWV_MIN, AWV_MAX = 800_000_000, 899_999_999


def add_awv(tsv_path, work, offline, prev_tsv):
    """Voegt nieuwe AWV-trajecten toe aan tsv_path. Bij een fout: AWV-rijen van de vorige versie behouden."""
    tc, rg, rows = os.path.join(work, 'awv_tc.json'), os.path.join(work, 'awv_reg.json'), os.path.join(work, 'awv_rows.tsv')
    try:
        if offline:
            shutil.copy(offline[0], tc); shutil.copy(offline[1], rg)
        else:
            log(run('awv.py', 'fetch', tc, rg, cwd=work).strip())
        summ = json.loads(run('awv.py', 'build', tc, rg, tsv_path, rows, cwd=work))
        new_rows = open(rows, encoding='utf-8').read()
        summ['bron'] = 'AWV open data (vandaag opgehaald)'
    except Exception as e:
        log('AWV mislukt, vorige AWV-trajecten behouden:', e)
        new_rows = ''
        if os.path.exists(prev_tsv):
            new_rows = ''.join(l for l in open(prev_tsv, encoding='utf-8')
                               if not l.startswith('#') and l.split('\t')[0].isdigit() and AWV_MIN <= int(l.split('\t')[0]) <= AWV_MAX)
        summ = dict(bron=f'AWV niet bereikbaar ({e}); vorige versie behouden', nieuw=new_rows.count('\n'))
    lines = open(tsv_path, encoding='utf-8').readlines()
    head, body = lines[0], [l for l in lines[1:] if l.strip()]
    weg = {x['ons'] for x in summ.get('vervangen', [])}
    if weg:
        body = [l for l in body if abs(int(l.split('\t')[0])) not in weg]
        log(f'AWV vervangt {len(weg)} OSM-traject(en)')
    body += [l if l.endswith('\n') else l + '\n' for l in new_rows.splitlines(True) if l.strip()]
    import re
    head = re.sub(r'aantal=\d+', f'aantal={len(body)}', head)
    if 'AWV' not in head: head = head.rstrip('\n') + ' + AWV open data\n'
    open(tsv_path, 'w', encoding='utf-8').write(head + ''.join(body))
    log(f"AWV: {summ.get('nieuw', 0)} trajecten toegevoegd")
    return summ


def link_all(tsv_path, max_gap=200, max_turn=60):
    """Vult 'volgende ids' aan: traject B volgt op A als B binnen max_gap m na het einde van A begint
    en de rijrichting doorloopt. Bestaande koppelingen blijven. Tweerichtingstrajecten: enkel voorwaarts."""
    lines = open(tsv_path, encoding='utf-8').readlines()
    head, body = lines[0], [l.rstrip('\n').split('\t') for l in lines[1:] if l.strip()]
    def P(c): return [tuple(map(float, q.split(','))) for q in c[5].split(';')]
    def d(a, b):
        k = math.cos(math.radians(a[0])); return math.hypot((b[1]-a[1])*111320*k, (b[0]-a[0])*110540)
    def hd(a, b):
        k = math.cos(math.radians(a[0])); return (math.degrees(math.atan2((b[1]-a[1])*k, b[0]-a[0])) + 360) % 360
    info = []
    for c in body:
        p = P(c); info.append((int(c[0]), p[0], p[-1], hd(p[0], p[1]), hd(p[-2], p[-1])))
    starts = sorted(info, key=lambda x: x[1][0])
    import bisect
    lats = [x[1][0] for x in starts]
    added = 0
    for c, (i, s0, e0, h0, he) in zip(body, info):
        have = {int(x) for x in c[4].split(',') if x}
        lo, hi = bisect.bisect_left(lats, e0[0]-0.003), bisect.bisect_right(lats, e0[0]+0.003)
        for j, s1, _, h1, _ in starts[lo:hi]:
            if j == i or j in have: continue
            if d(e0, s1) <= max_gap and min(abs(he-h1) % 360, 360 - abs(he-h1) % 360) < max_turn:
                have.add(j); added += 1
        c[4] = ','.join(str(x) for x in sorted(have))
    open(tsv_path, 'w', encoding='utf-8').write(head + ''.join('\t'.join(c) + '\n' for c in body))
    log(f'{added} extra koppeling(en) tussen trajecten')


OSM_FALLBACK = [False]
MELD_PREV = []


def osm_available(work, data, meld_json):
    """Test of Overpass antwoordt (kleine query). Zo niet: bouw segments_v2.json uit de OSM-trajecten van
    de vorige gepubliceerde versie, zodat AWV en eigen trajecten toch verwerkt worden."""
    probe = os.path.join(work, 'probe.json')
    try:
        overpass('[out:json][timeout:25];relation["enforcement"="average_speed"](50.8,4.6,50.95,4.9);out ids;', probe)
        return True
    except Exception as e:
        log('Overpass onbereikbaar, OSM-trajecten van de vorige versie worden hergebruikt:', e)
    osm_fallback(work, data)
    return False


def osm_fallback(work, data):
    """segments_v2.json opbouwen uit de OSM-trajecten van de vorige gepubliceerde versie."""
    prev = os.path.join(data, 'segments.tsv')
    segs = []
    for l in open(prev, encoding='utf-8'):
        if l.startswith('#') or not l.strip(): continue
        c = l.rstrip('\n').split('\t'); i = int(c[0])
        if i >= AWV_MIN: continue   # AWV / eigen worden opnieuw opgebouwd
        pts = [list(map(float, q.split(','))) for q in c[5].split(';')]
        segs.append(dict(id=i, name=c[1], description=None, maxspeed=int(c[2]), status='ok', bidirectional=c[3] == '1',
                         polyline=pts, issues=[], start=None, end=None, length_m=None))
    chains = [dict(van=int(l.split('\t')[0]), naar=int(n)) for l in open(prev, encoding='utf-8') if not l.startswith('#') and l.strip()
              for n in l.split('\t')[4].split(',') if n and int(l.split('\t')[0]) < AWV_MIN and int(n) < AWV_MIN]
    json.dump(dict(segments=segs, chains=chains), open(os.path.join(work, 'segments_v2.json'), 'w'))
    OSM_FALLBACK[0] = True


def merge_eigen(tsv_path, eigen_path):
    """Voegt eigen trajecten toe aan tsv_path (zelfde id = vervangen, limiet 0 = verwijderen). -> lijst eigen ids."""
    if not os.path.exists(eigen_path): return []
    eigen = {}
    for line in open(eigen_path, encoding='utf-8'):
        if line.startswith('#') or not line.strip(): continue
        c = line.rstrip('\n').split('\t')
        if len(c) >= 6 and c[0].lstrip('-').isdigit(): eigen[int(c[0])] = line if line.endswith('\n') else line + '\n'
    lines = open(tsv_path, encoding='utf-8').readlines()
    head, body = lines[0], [l for l in lines[1:] if l.strip()]
    body = [l for l in body if int(l.split('\t')[0]) not in eigen]
    body += [l for i, l in sorted(eigen.items()) if int(l.split('\t')[2]) > 0]
    import re
    head = re.sub(r'aantal=\d+', f'aantal={len(body)}', head)
    open(tsv_path, 'w', encoding='utf-8').write(head + ''.join(body))
    log(f'{len(eigen)} eigen traject(en) samengevoegd')
    return sorted(eigen)


def eigen_in_osm(new, eigen_ids):
    """Eigen trajecten waarvan begin EN einde binnen 60 m van een OSM-traject liggen -> mogelijk dubbel."""
    def pts(line): return [tuple(map(float, p.split(','))) for p in line.split('\t')[5].strip().split(';')]
    def d(a, b):
        k = math.cos(math.radians(a[0])); return math.hypot((b[1]-a[1])*111320*k, (b[0]-a[0])*110540)
    out = []
    for e in eigen_ids:
        if e not in new: continue
        ep = pts(new[e][3]); a, b = ep[0], ep[-1]
        for i, v in new.items():
            if i in eigen_ids or i < 0: continue
            op = pts(v[3])
            if min(d(a, p) for p in op) < 60 and min(d(b, p) for p in op) < 60:
                out.append((e, i)); break
    return out


def report(prev, new, failed_checks, version, published, eigen_ids=(), meldingen=(), awv=None):
    added = sorted(set(new) - set(prev)); removed = sorted(set(prev) - set(new))
    lim = [i for i in sorted(set(new) & set(prev)) if new[i][1] != prev[i][1]]
    geo = [i for i in sorted(set(new) & set(prev)) if new[i][1] == prev[i][1]
           and abs(new[i][2] - prev[i][2]) > max(200, 0.05 * prev[i][2])]
    r = [f'# Trajectcontroles: update {datetime.date.today().isoformat()}', '']
    if failed_checks:
        r += ['**NIET GEPUBLICEERD.** Een of meer controles faalden, de app houdt de vorige versie:', '']
        r += [f'- {c}' for c in failed_checks] + ['']
    r += [f'Trajecten: {len(prev)} → **{len(new)}** · nieuw {len(added)} · verdwenen {len(removed)} · '
          f'limiet gewijzigd {len(lim)} · lengte gewijzigd {len(geo)}' + (f' · versie {version}' if published else ''), '']

    def osm(i): return (f'{i} (AWV)' if AWV_MIN <= abs(i) <= AWV_MAX else f'{i} (eigen)') if abs(i) >= AWV_MIN else f'[{i}](https://www.openstreetmap.org/relation/{abs(i)})'
    if added:
        r += ['## Nieuw', '', '| Traject | Naam | Limiet | Lengte |', '|---|---|---|---|']
        r += [f'| {osm(i)} | {new[i][0]} | {new[i][1]} | {new[i][2]/1000:.1f} km |' for i in added] + ['']
    if removed:
        r += ['## Verdwenen', '', '| Traject | Naam | Limiet |', '|---|---|---|']
        r += [f'| {osm(i)} | {prev[i][0]} | {prev[i][1]} |' for i in removed] + ['']
    if lim:
        r += ['## Limiet gewijzigd', '', '| Traject | Naam | Was | Nu |', '|---|---|---|---|']
        r += [f'| {osm(i)} | {new[i][0]} | {prev[i][1]} | {new[i][1]} |' for i in lim] + ['']
    if geo:
        r += ['## Lengte gewijzigd (> 5 % of > 200 m)', '', '| Traject | Naam | Was | Nu |', '|---|---|---|---|']
        r += [f'| {osm(i)} | {new[i][0]} | {prev[i][2]/1000:.1f} km | {new[i][2]/1000:.1f} km |' for i in geo] + ['']
    if eigen_ids:
        r += ['## Eigen trajecten (data/eigen_trajecten.tsv)', '']
        r += [f'- {i}: {new[i][0]} ({new[i][1]} km/u, {new[i][2]/1000:.2f} km)' for i in eigen_ids if i in new] + ['']
        dup = eigen_in_osm(new, set(eigen_ids))
        if dup:
            r += ['**Staat nu ook in OpenStreetMap** — de eigen versie kan wellicht weg:', '']
            r += [f'- eigen {e} ≈ OSM {osm(o)} ({new[o][0]})' for e, o in dup] + ['']
    if awv:
        r += ['## AWV open data', '', f"Bron: {awv.get('bron')}. "
              f"Geplaatst volgens AWV: {awv.get('geplaatst', '?')} · al aanwezig: {awv.get('zelfde', '?')} · "
              f"toegevoegd: {awv.get('nieuw', '?')} · limiet onzeker (wisselt onderweg): {awv.get('limiet_onzeker', '?')}", '']
        if awv.get('vervangen'):
            r += ['Vervangen door AWV-portaalstukken (OSM voegde stukken samen of week af):', '',
                  '| Bestaand traject | Lengte | Vervangen door |', '|---|---|---|']
            r += [f"| {x['ons_naam']} | {x['ons_m']/1000:.2f} km | {'; '.join(x['door'])} |" for x in awv['vervangen']] + ['']
        if awv.get('lengteverschil'):
            r += ['Overlapt met een bestaand traject maar met **andere lengte** (bestaande versie behouden — na te kijken):', '',
                  '| AWV | AWV-lengte | Bestaand traject | Lengte |', '|---|---|---|---|']
            r += [f"| {x['awv']} | {x['awv_m']/1000:.2f} km | {x['ons_naam']} | {x['ons_m']/1000:.2f} km |" for x in awv['lengteverschil']] + ['']
        if awv.get('uitgesloten'):
            r += ['Niet opgenomen:', ''] + [f'- {t}: {why}' for t, why in awv['uitgesloten']] + ['']
    if meldingen:
        r += ['## Eigen meldingen (data/eigen_meldingen.csv)', '', '| Regel | Naam | Resultaat |', '|---|---|---|']
        for m in meldingen:
            res = ('⚠ ' + '; '.join(dict.fromkeys(m['fouten']))) if m['fouten'] else \
                  f"✓ {m['limiet']} km/u, {m.get('lengte', 0)/1000:.2f} km" + (', beide richtingen' if len(m.get('ids', [])) == 2 else '')
            r.append(f"| {m['regel']} | {m['naam']} | {res} |")
        r.append('')
    other = [i for i in sorted(set(new) & set(prev)) if new[i][3] != prev[i][3] and i not in lim and i not in geo]
    if other:
        r += [f'Overige kleine wijzigingen (naam, koppeling of lijn < 5 %): {len(other)} trajecten.', '']
    if not (added or removed or lim or geo or other) and not failed_checks:
        r += ['Geen wijzigingen.', '']
    return '\n'.join(r), bool(added or removed or lim or geo or other)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--data', required=True, help='map met segments.tsv, meta.json, RAPPORT.md')
    ap.add_argument('--offline', nargs='+', help='bestaande exports gebruiken i.p.v. Overpass')
    ap.add_argument('--awv-offline', nargs=2, metavar=('TRAJECTEN', 'REGIMES'), help='bestaande AWV-bestanden gebruiken')
    ap.add_argument('--zonder-awv', action='store_true', help='AWV-stap overslaan')
    a = ap.parse_args()
    global DATA_DIR
    data = os.path.abspath(a.data); os.makedirs(data, exist_ok=True); DATA_DIR = data
    work = tempfile.mkdtemp(prefix='tc_')
    log('werkmap', work)

    # 0: eigen meldingen (data/eigen_meldingen.csv) -> nagemaakte relaties
    meld_json = os.path.join(work, 'meldingen.json')
    meld_csv = os.path.join(data, 'eigen_meldingen.csv')
    meldingen = []
    if os.path.exists(meld_csv):
        meldingen = json.loads(run('meldingen.py', meld_csv, meld_json, cwd=work))
        log(f'{len(meldingen)} eigen melding(en) gelezen')
    else:
        json.dump({'elements': []}, open(meld_json, 'w'))

    # 1-3: ophalen en verwerken
    if a.offline:
        exports = [os.path.abspath(p) for p in a.offline] + [meld_json]
        run('enrich.py', *exports, cwd=work); log(run('build_segments.py', *exports, cwd=work).strip())
    elif not osm_available(work, data, meld_json):
        pass   # terugvalmodus: segments_v2.json is opgebouwd uit de vorige versie
    else:
        try:
            e1 = os.path.join(work, 'export1.json')
            meta_prev = json.load(open(os.path.join(data, 'meta.json'))) if os.path.exists(os.path.join(data, 'meta.json')) else {}
            verwacht = int(meta_prev.get('osm_relaties', 0) * 0.98)
            log('basisquery…'); nrel = overpass(open(os.path.join(HERE, 'query_basis.overpassql')).read(), e1, verwacht)
            OSM_REL[0] = nrel
            run('enrich.py', e1, meld_json, cwd=work); run('build_segments.py', e1, meld_json, cwd=work)
            q2 = os.path.join(work, 'aanvulling.overpassql')
            log(run('gen_aanvulling.py', e1, meld_json, q2, cwd=work).strip())
            exports = [e1, meld_json]
            if open(q2).read().count('way(around') > 0:
                e2 = os.path.join(work, 'export2.json')
                n_q = open(q2).read().count('way(around')
                log('aanvullende query…'); overpass(open(q2).read(), e2, min_elements=n_q)
                exports.append(e2)
            run('enrich.py', *exports, cwd=work); log(run('build_segments.py', *exports, cwd=work).strip())
        except Exception as e:
            log('OSM-verwerking mislukt, OSM-trajecten van de vorige versie worden hergebruikt:', e)
            OSM_REL[0] = None
            osm_fallback(work, data)

    # status per eigen melding (uit de verwerking)
    segv2 = {x['id']: x for x in json.load(open(os.path.join(work, 'segments_v2.json')))['segments']}
    if OSM_FALLBACK[0]:
        # meldingen konden niet op de weg gelegd worden; vorige versie ervan behouden
        prev_rows = [l for l in open(os.path.join(data, 'segments.tsv'), encoding='utf-8')
                     if not l.startswith('#') and l.strip() and 910_000_000 <= int(l.split('\t')[0]) < 1_000_000_000]
        for m in meldingen: m['fouten'].append('OpenStreetMap onbereikbaar: vorige versie behouden')
        MELD_PREV[:] = prev_rows
    for m in meldingen:
        for i in m.get('ids', []):
            sg = segv2.get(i)
            if sg is None: m['fouten'].append('niet verwerkt'); continue
            if sg['status'] != 'ok':
                uitleg = {'geen_route_gevonden': 'geen weg gevonden tussen begin en einde (ligt een punt naast de weg?)',
                          'niet_op_weg_te_leggen': 'begin of einde ligt te ver van een weg',
                          'omweg_verdacht': 'de route maakt een grote omweg: kijk begin en einde na',
                          'route_te_kort': 'traject korter dan 100 m',
                          'verdacht_kort': 'begin en einde liggen bijna op dezelfde plek',
                          'start_einde_tegen_rijrichting': 'lijkt tegen de rijrichting in (eenrichtingsweg?)'}
                m['fouten'] += [uitleg.get(x, x) for x in sg['issues'] if x not in ('maxspeed_uit_weg', 'maxspeed_van_buur')] or ['niet bruikbaar']
            else:
                m.setdefault('lengte', round(sg['length_m']))

    # 4: export
    new_tsv = os.path.join(work, 'segments.tsv')
    log(run('export_tsv.py', new_tsv, cwd=work).strip())
    if OSM_FALLBACK[0] and MELD_PREV:
        with open(new_tsv, 'a', encoding='utf-8') as fh: fh.write(''.join(MELD_PREV))

    # 4b: eigen trajecten (data/eigen_trajecten.tsv) samenvoegen
    eigen = merge_eigen(new_tsv, os.path.join(data, 'eigen_trajecten.tsv'))

    # 4c: AWV open data (trajectcontroles op gewest- en snelwegen)
    awv = None
    if not a.zonder_awv:
        awv = add_awv(new_tsv, work, a.awv_offline, os.path.join(data, 'segments.tsv'))

    # 4d: aaneensluitende trajecten koppelen over alle bronnen heen
    link_all(new_tsv)

    # 5: controles
    _, prev = read_tsv(os.path.join(data, 'segments.tsv'))
    head, new = read_tsv(new_tsv)
    version = int(head.split('versie=')[1].split()[0])
    checks = []
    if len(new) < MIN_SEGMENTS: checks.append(f'slechts {len(new)} trajecten (minimum {MIN_SEGMENTS})')
    if prev and len(new) < (1 - MAX_DROP) * len(prev):
        checks.append(f'{len(new)} trajecten, meer dan {int(MAX_DROP*100)} % minder dan vorige versie ({len(prev)})')
    n_e40 = sum(1 for v in new.values() if 'E40' in v[0] or ' A10 ' in v[0] or ' A3 ' in v[0])   # E40 = A10 + A3
    if n_e40 < MIN_E40: checks.append(f'E40 onvolledig: {n_e40} trajecten (minimum {MIN_E40})')
    # OSM-trajecten mogen niet stilletjes wegvallen (AWV-groei maskeert dat anders in het totaal)
    prev_osm = {i for i in prev if 0 < abs(i) < AWV_MIN}
    vervangen = {x['ons'] for x in (awv or {}).get('vervangen', [])}
    weg_osm = prev_osm - set(new) - vervangen
    if prev_osm and len(weg_osm) > MAX_DROP * len(prev_osm):
        checks.append(f'{len(weg_osm)} van {len(prev_osm)} OSM-trajecten verdwenen zonder AWV-vervanging')
    short = [i for i, v in new.items() if v[2] < 100]
    if short: checks.append(f'{len(short)} trajecten korter dan 100 m: {short[:5]}')

    # 6: rapport + publiceren
    published = not checks
    text, changed = report(prev, new, checks, version, published, eigen, meldingen, awv)
    open(os.path.join(data, 'RAPPORT.md'), 'w', encoding='utf-8').write(text + '\n')
    meta_p = os.path.join(data, 'meta.json')
    meta = json.load(open(meta_p)) if os.path.exists(meta_p) else {}
    meta['laatst_gecontroleerd'] = datetime.datetime.now(datetime.timezone.utc).strftime('%Y-%m-%dT%H:%MZ')
    if published and OSM_REL[0] and not OSM_FALLBACK[0]: meta['osm_relaties'] = OSM_REL[0]
    if published and (changed or not prev):
        shutil.copy(new_tsv, os.path.join(data, 'segments.tsv'))
        meta.update(versie=version, aantal=len(new), datum=meta['laatst_gecontroleerd'],
                    sha256=hashlib.sha256(open(new_tsv, 'rb').read()).hexdigest())
        log(f'GEPUBLICEERD: versie {version}, {len(new)} trajecten')
    else:
        changed = False
        log('niet gepubliceerd' if checks else 'geen wijzigingen')
    json.dump(meta, open(meta_p, 'w'), indent=1); open(meta_p, 'a').write('\n')
    log(text)

    gh = os.environ.get('GITHUB_OUTPUT')
    if gh:
        with open(gh, 'a') as f:
            f.write(f'changed={"true" if changed else "false"}\nfailed={"true" if checks else "false"}\n')
    shutil.rmtree(work, ignore_errors=True)
    sys.exit(2 if checks else 0)


if __name__ == '__main__':
    try:
        main()
    except SystemExit:
        raise
    except Exception as e:
        log('TECHNISCHE FOUT:', e)
        if DATA_DIR:
            open(os.path.join(DATA_DIR, 'RAPPORT.md'), 'w', encoding='utf-8').write(
                f'# Trajectcontroles: update {datetime.date.today().isoformat()}\n\n'
                f'**NIET GEPUBLICEERD: technische fout.** De app houdt de vorige versie.\n\n`{e}`\n\n'
                'Meestal is Overpass tijdelijk overbelast; de volgende wekelijkse run probeert opnieuw. '
                'Manueel herstarten kan via Actions → Run workflow.\n')
        gh = os.environ.get('GITHUB_OUTPUT')
        if gh: open(gh, 'a').write('changed=false\nfailed=true\n')
        sys.exit(1)
