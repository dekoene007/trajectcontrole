#!/usr/bin/env python3
"""Verwerkt een melding uit de app (GitHub-issue) naar de data.

Het issue bevat een blok
    ```trajectcontrole
    nieuw;id=920123456;limiet=50;naam=Holsbeek, Dutselstraat;punten=lat,lon;lat,lon;...
    correctie;id=815154;kant=begin;naam=...;punten=lat,lon;...
    ```
- nieuw      -> data/eigen_trajecten.tsv (zelfde id = vervangen)
- correctie  -> data/correcties.tsv (nieuwe lijn voor dat traject-id; negatief id = één richting)

Gebruik: ISSUE_BODY=... ISSUE_NR=12 python3 issue_melding.py <data-map>
Schrijft een samenvatting (markdown) naar stdout; exit 0 = iets verwerkt, 3 = niets geldigs gevonden.
"""
import os, re, sys

LIMITS = {20, 30, 50, 70, 80, 90, 100, 110, 120}
BBOX = (49.4, 51.6, 2.4, 6.5)   # België ruim


def parse_points(s):
    pts = []
    for p in s.split(';'):
        a = p.split(',')
        if len(a) != 2: continue
        try: la, lo = float(a[0]), float(a[1])
        except ValueError: continue
        if not (BBOX[0] <= la <= BBOX[1] and BBOX[2] <= lo <= BBOX[3]): raise ValueError(f'punt buiten België: {la},{lo}')
        pts.append((la, lo))
    if len(pts) < 2: raise ValueError('minder dan 2 punten')
    if len(pts) > 3000: raise ValueError('te veel punten')
    return pts


def fmt(pts): return ';'.join(f'{a:.6f},{b:.6f}' for a, b in pts)


def parse_line(line):
    kind, *rest = line.split(';', 1)
    fields = {}
    if rest:
        # punten= staat altijd laatst en bevat zelf ';'
        head, _, pts = rest[0].partition('punten=')
        for kv in head.split(';'):
            if '=' in kv: k, v = kv.split('=', 1); fields[k.strip()] = v.strip()
        fields['punten'] = pts.strip()
    return kind.strip(), fields


def upsert(path, key, row, header):
    lines = open(path, encoding='utf-8').read().splitlines() if os.path.exists(path) else header
    lines = [l for l in lines if l.startswith('#') or not l.strip() or l.split('\t')[0] != key]
    lines.append(row)
    open(path, 'w', encoding='utf-8').write('\n'.join(lines) + '\n')


def main():
    data = sys.argv[1]
    body = os.environ.get('ISSUE_BODY', '')
    nr = os.environ.get('ISSUE_NR', '?')
    m = re.search(r'```trajectcontrole\s*\n(.*?)```', body, re.S)
    if not m:
        print('Geen blok `trajectcontrole` gevonden.'); return 3
    ok, err = [], []
    for raw in m.group(1).splitlines():
        line = raw.strip()
        if not line: continue
        try:
            kind, f = parse_line(line)
            sid = int(f['id'])
            pts = parse_points(f.get('punten', ''))
            naam = re.sub(r'[\t\n]', ' ', f.get('naam', '')).strip()
            if kind == 'nieuw':
                lim = int(f.get('limiet', '0'))
                if lim not in LIMITS: raise ValueError(f'ongeldige limiet {lim}')
                if sid < 900_000_000: raise ValueError('id van een nieuw traject moet ≥ 900000000 zijn')
                upsert(os.path.join(data, 'eigen_trajecten.tsv'), str(sid),
                       f'{sid}\t{naam or "Eigen traject"} (melding #{nr})\t{lim}\t0\t\t{fmt(pts)}',
                       ['# Eigen trajectcontroles (zie README)'])
                ok.append(f'nieuw traject **{naam}** ({sid}), limiet {lim}, {len(pts)} punten')
            elif kind == 'correctie':
                kant = f.get('kant', '?')
                upsert(os.path.join(data, 'correcties.tsv'), str(sid), f'{sid}\t{fmt(pts)}\t{kant} via melding #{nr}',
                       ['# Correcties van de lijn van bestaande trajecten (uit meldingen in de app).',
                        '# Kolommen (tab): id  lat,lon;lat,lon;...  opmerking. Negatief id = enkel de omgekeerde richting.'])
                ok.append(f'correctie {kant} van **{naam or sid}** ({sid}), {len(pts)} punten')
            else:
                raise ValueError(f'onbekend soort "{kind}"')
        except (ValueError, KeyError) as e:
            err.append(f'`{line[:60]}…`: {e}')
    print('### Verwerkt\n' + ('\n'.join(f'- {x}' for x in ok) or '- niets'))
    if err: print('\n### Niet verwerkt\n' + '\n'.join(f'- {x}' for x in err))
    return 0 if ok else 3


if __name__ == '__main__':
    sys.exit(main())
