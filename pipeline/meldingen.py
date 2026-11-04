"""data/eigen_meldingen.csv -> nagemaakte OSM-relaties (JSON in Overpass-formaat).

Elke regel wordt een average_speed-relatie met een 'from'- en 'to'-knoop op de opgegeven
coördinaten. Zo doorlopen eigen meldingen exact dezelfde verwerking als OSM-trajecten
(op de weg leggen, ontbrekende wegen ophalen, controles).

Gebruik: python3 meldingen.py eigen_meldingen.csv uit.json   -> drukt ook een samenvatting af (JSON) op stdout

CSV (puntkomma-gescheiden, regels met # = commentaar):
  naam;limiet;richting;begin;einde
  richting: 'beide' of 'een' (= van begin naar einde)
  begin/einde: 'lat,lon' (bv. uit Google Maps: lang drukken -> coördinaten kopiëren)
"""
import json, sys, zlib, re

BASE = 910_000_000  # ids 910.000.000+ = eigen meldingen (900.000.000+ = eigen_trajecten.tsv)


def parse_point(s):
    m = re.match(r'^\s*\(?\s*(-?\d+(?:\.\d+)?)\s*,\s*(-?\d+(?:\.\d+)?)\s*\)?\s*$', s)
    if not m: return None
    la, lo = float(m.group(1)), float(m.group(2))
    if not (49.4 < la < 51.6 and 2.4 < lo < 6.5): return None   # België (ruim)
    return la, lo


def stable_id(name):
    return BASE + (zlib.crc32(name.strip().lower().encode()) % 40_000_000) * 2


def main(src, out):
    elements, summary, seen = [], [], set()
    nid = -1
    for ln, raw in enumerate(open(src, encoding='utf-8-sig'), 1):
        line = raw.strip()
        if not line or line.startswith('#') or line.lower().startswith('naam;'): continue
        c = [x.strip() for x in line.split(';')]
        entry = {'regel': ln, 'naam': c[0] if c else '', 'fouten': []}
        summary.append(entry)
        if len(c) < 5:
            entry['fouten'].append('verwacht 5 velden: naam;limiet;richting;begin;einde'); continue
        name, lim, rich, b, e = c[:5]
        if not name: entry['fouten'].append('naam ontbreekt')
        if not lim.isdigit() or not (10 <= int(lim) <= 130): entry['fouten'].append(f'limiet "{lim}" ongeldig')
        r = rich.lower()
        if r not in ('beide', 'een', 'één', '1', '2'): entry['fouten'].append(f'richting "{rich}": gebruik beide of een')
        pb, pe = parse_point(b), parse_point(e)
        if not pb: entry['fouten'].append(f'begin "{b}" is geen geldig lat,lon in België')
        if not pe: entry['fouten'].append(f'einde "{e}" is geen geldig lat,lon in België')
        if entry['fouten']: continue
        rid = stable_id(name)
        if rid in seen: entry['fouten'].append('naam komt twee keer voor'); continue
        seen.add(rid)
        both = r in ('beide', '2')
        entry.update(id=rid, ids=[rid] + ([rid + 1] if both else []), limiet=int(lim))
        for k, (p1, p2) in enumerate([(pb, pe)] + ([(pe, pb)] if both else [])):
            n1, n2 = nid, nid - 1; nid -= 2
            elements += [{'type': 'node', 'id': n1, 'lat': p1[0], 'lon': p1[1]},
                         {'type': 'node', 'id': n2, 'lat': p2[0], 'lon': p2[1]}]
            elements.append({'type': 'relation', 'id': rid + k,
                             'members': [{'type': 'node', 'ref': n1, 'role': 'from'}, {'type': 'node', 'ref': n2, 'role': 'to'}],
                             'tags': {'type': 'enforcement', 'enforcement': 'average_speed', 'maxspeed': lim,
                                      'name': f'{name} (eigen melding)' + (' ↩' if k else '')}})
    json.dump({'elements': elements}, open(out, 'w'))
    print(json.dumps(summary, ensure_ascii=False))


if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2])
