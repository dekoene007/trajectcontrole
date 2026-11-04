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
ENDPOINTS = ['https://overpass-api.de/api/interpreter', 'https://overpass.private.coffee/api/interpreter']
USER_AGENT = 'trajectcontrole-pipeline/1.0 (github.com/dekoene007/trajectcontrole)'

# --- controles (bewust conservatief) ---
MIN_SEGMENTS = 100          # absolute ondergrens
MAX_DROP = 0.10             # niet meer dan 10 % minder dan de vorige versie
MIN_E40 = 15                # E40 moet (bijna) volledig blijven (nu 17)


DATA_DIR = None


def log(*a): print(*a, flush=True)


def overpass(query, out_path):
    body = urllib.parse.urlencode({'data': query}).encode()
    last = None
    for attempt in range(3):
        for ep in ENDPOINTS:
            try:
                req = urllib.request.Request(ep, data=body, headers={'User-Agent': USER_AGENT})
                with urllib.request.urlopen(req, timeout=900) as r:
                    raw = r.read()
                d = json.loads(raw)
                if 'elements' not in d: raise ValueError('geen elements in antwoord')
                if 'remark' in d and ('error' in d['remark'].lower() or 'timed out' in d['remark'].lower()):
                    raise ValueError('Overpass-fout: ' + d['remark'][:200])
                open(out_path, 'wb').write(raw)
                log(f'  {ep}: {len(d["elements"])} elementen')
                return
            except Exception as e:
                last = e; log(f'  {ep} mislukt: {e}')
        time.sleep(60 * (attempt + 1))
    raise RuntimeError(f'Overpass onbereikbaar: {last}')


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


def report(prev, new, failed_checks, version, published):
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

    def osm(i): return f'[{i}](https://www.openstreetmap.org/relation/{abs(i)})'
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
    if not (added or removed or lim or geo) and not failed_checks:
        r += ['Geen wijzigingen.', '']
    return '\n'.join(r), bool(added or removed or lim or geo)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--data', required=True, help='map met segments.tsv, meta.json, RAPPORT.md')
    ap.add_argument('--offline', nargs='+', help='bestaande exports gebruiken i.p.v. Overpass')
    a = ap.parse_args()
    global DATA_DIR
    data = os.path.abspath(a.data); os.makedirs(data, exist_ok=True); DATA_DIR = data
    work = tempfile.mkdtemp(prefix='tc_')
    log('werkmap', work)

    # 1-3: ophalen en verwerken
    if a.offline:
        exports = [os.path.abspath(p) for p in a.offline]
        run('enrich.py', *exports, cwd=work); log(run('build_segments.py', *exports, cwd=work).strip())
    else:
        e1 = os.path.join(work, 'export1.json')
        log('basisquery…'); overpass(open(os.path.join(HERE, 'query_basis.overpassql')).read(), e1)
        run('enrich.py', e1, cwd=work); run('build_segments.py', e1, cwd=work)
        q2 = os.path.join(work, 'aanvulling.overpassql')
        log(run('gen_aanvulling.py', e1, q2, cwd=work).strip())
        exports = [e1]
        if open(q2).read().count('way(around') > 0:
            e2 = os.path.join(work, 'export2.json')
            log('aanvullende query…'); overpass(open(q2).read(), e2)
            exports.append(e2)
        run('enrich.py', *exports, cwd=work); log(run('build_segments.py', *exports, cwd=work).strip())

    # 4: export
    new_tsv = os.path.join(work, 'segments.tsv')
    log(run('export_tsv.py', new_tsv, cwd=work).strip())

    # 5: controles
    _, prev = read_tsv(os.path.join(data, 'segments.tsv'))
    head, new = read_tsv(new_tsv)
    version = int(head.split('versie=')[1].split()[0])
    checks = []
    if len(new) < MIN_SEGMENTS: checks.append(f'slechts {len(new)} trajecten (minimum {MIN_SEGMENTS})')
    if prev and len(new) < (1 - MAX_DROP) * len(prev):
        checks.append(f'{len(new)} trajecten, meer dan {int(MAX_DROP*100)} % minder dan vorige versie ({len(prev)})')
    n_e40 = sum(1 for v in new.values() if 'E40' in v[0])
    if n_e40 < MIN_E40: checks.append(f'E40 onvolledig: {n_e40} trajecten (minimum {MIN_E40})')
    short = [i for i, v in new.items() if v[2] < 100]
    if short: checks.append(f'{len(short)} trajecten korter dan 100 m: {short[:5]}')

    # 6: rapport + publiceren
    published = not checks
    text, changed = report(prev, new, checks, version, published)
    open(os.path.join(data, 'RAPPORT.md'), 'w', encoding='utf-8').write(text + '\n')
    meta_p = os.path.join(data, 'meta.json')
    meta = json.load(open(meta_p)) if os.path.exists(meta_p) else {}
    meta['laatst_gecontroleerd'] = datetime.datetime.now(datetime.timezone.utc).strftime('%Y-%m-%dT%H:%MZ')
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
