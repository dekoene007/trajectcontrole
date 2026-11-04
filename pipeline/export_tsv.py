"""segments_v2.json -> segments.tsv (voor de app; geen JSON-parser nodig).
Gebruik: python3 export_tsv.py uit.tsv [versie]
Kopregel: # versie=<int> aantal=<n> datum=<iso> bron=OpenStreetMap (ODbL)
Kolommen: id, naam, limiet, tweerichting(0/1), volgende ids (komma), polylijn lat,lon;lat,lon;..."""
import json, sys, datetime
d = json.load(open('segments_v2.json'))
ok = [s for s in d['segments'] if s['status'] == 'ok']
ok_ids = {s['id'] for s in ok}
nexts = {}
for c in d['chains']:
    if c['van'] in ok_ids and c['naar'] in ok_ids: nexts.setdefault(c['van'], []).append(c['naar'])
now = datetime.datetime.now(datetime.timezone.utc)
version = int(sys.argv[2]) if len(sys.argv) > 2 else int(now.strftime('%Y%m%d%H%M'))
with open(sys.argv[1], 'w') as f:
    f.write(f'# versie={version} aantal={len(ok)} datum={now.strftime("%Y-%m-%dT%H:%MZ")} bron=OpenStreetMap (ODbL)\n')
    for s in sorted(ok, key=lambda s: s['id']):
        name = (s['description'] or s['name'] or f"Traject {s['id']}").split('\n')[0].replace('\t', ' ').strip()
        if s['name'] and s['description'] and s['name'] not in name: name = f"{s['name']} {name}"
        nx = ','.join(str(n) for n in nexts.get(s['id'], []))
        poly = ';'.join(f'{a:.6f},{b:.6f}' for a, b in s['polyline'])
        f.write(f"{s['id']}\t{name}\t{s['maxspeed']}\t{1 if s.get('bidirectional') else 0}\t{nx}\t{poly}\n")
print(len(ok), 'trajecten, versie', version)
