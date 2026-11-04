"""Maakt de aanvullende Overpass-query voor trajecten zonder wegdata (na een eerste build_segments-run).
Gebruik: python3 gen_aanvulling.py export1.json [meer.json ...] uit.overpassql   (leest segments_v2.json)"""
import json, sys
from normalise import load, hav
d = json.load(open('segments_v2.json'))
N, W, R = load(*sys.argv[1:-1])
miss = [s for s in d['segments'] if 'geen_route_gevonden' in s['issues'] and s['start'] and s['end']]
lines = ['[out:json][timeout:600];', '(']
for s in miss:
    a, b = N[s['start']], N[s['end']]; L = hav(a, b)
    if L > 3000: rad, cls = max(400, int(0.09*L)), 'motorway|trunk|primary|secondary'
    else: rad, cls = 400, 'motorway|trunk|primary|secondary|tertiary|unclassified|residential|living_street'
    lines.append(f'  way(around:{rad},{a[0]:.6f},{a[1]:.6f},{b[0]:.6f},{b[1]:.6f})["highway"~"^({cls})(_link)?$"];')
lines += [');', 'out body;', '>;', 'out skel qt;']
open(sys.argv[-1], 'w').write('\n'.join(lines) + '\n')
print(len(miss), 'trajecten in aanvullende query')
