"""Stap 2: maxspeed uit wegen halen en richting doorgeven via gedeelde punten."""
import json, sys, math
from collections import Counter, defaultdict
from normalise import load, normalise, hav, bearing, angdiff

N, W, R = load(*sys.argv[1:])
segs, _, _ = normalise(N, W, R)
S = {s['id']: s for s in segs}
node_ways = defaultdict(list)
for wid, w in W.items():
    for n in w.get('nodes', []): node_ways[n].append(wid)
relmembers = {r['id']: r['members'] for r in R}

# 1. maxspeed-fallback: sectiewegen of wegen door de punten
for s in segs:
    if s['maxspeed']: continue
    cand = Counter()
    for m in relmembers[s['id']]:
        if m['type'] == 'way' and m['ref'] in W:
            v = W[m['ref']].get('tags', {}).get('maxspeed')
            if v and v.isdigit(): cand[int(v)] += 2
    for n in s['nodes']:
        for wid in node_ways.get(n, []):
            v = W[wid].get('tags', {}).get('maxspeed')
            if v and v.isdigit(): cand[int(v)] += 1
    if cand:
        s['maxspeed'] = cand.most_common(1)[0][0]
        s['issues'] = [i for i in s['issues'] if i != 'geen_maxspeed'] + ['maxspeed_uit_weg']

# 2. richting doorgeven via gedeelde punten
changed = True
while changed:
    changed = False
    for x in segs:
        if x['start']: continue
        for n in x['nodes']:
            for y in segs:
                if y is x or not y['start'] or n not in y['nodes']: continue
                others = [m for m in x['nodes'] if m != n]
                if not others: continue
                far = max(others, key=lambda m: hav(N[m], N[n]))
                if n == y['start']:
                    x['start'], x['end'] = far, n
                elif n == y['end']:
                    x['start'], x['end'] = n, far
                else:
                    continue
                x['bearing'] = bearing(N[x['start']], N[x['end']])
                x['length_m_vogelvlucht'] = round(hav(N[x['start']], N[x['end']]))
                x['how'] = 'afgeleid_via_buurtraject'
                x['issues'] = [i for i in x['issues'] if i != 'richting_onbekend']
                changed = True
                break
            if x['start']: break

# 3. koppelingen opnieuw
chains = []
for a in segs:
    if not a['end']: continue
    for b in segs:
        if b is a or not b['start']: continue
        if b['start'] == a['end']:
            d, kind = 0, 'gedeeld_punt'
        else:
            d = hav(N[a['end']], N[b['start']])
            if d > 150: continue
            kind = 'nabij'
        if angdiff(a['bearing'], b['bearing']) > 45: continue
        chains.append(dict(van=a['id'], naar=b['id'], type=kind, afstand_m=round(d)))

json.dump(dict(segments=segs, chains=chains), open('segments.json', 'w'), indent=1, ensure_ascii=False)
crit = {'start_einde_tegen_rijrichting', 'verdacht_kort', 'verdacht_lang', 'meerdere_from_of_to', 'te_weinig_punten', 'richting_onbekend'}
ok = [s for s in segs if s['start'] and s['maxspeed'] and not set(s['issues']) & crit]
print('relaties', len(segs))
print('hoe bepaald', Counter(s['how'] for s in segs))
print('issues', Counter(i for s in segs for i in s['issues']))
print('direct bruikbaar', len(ok))
print('koppelingen', len(chains), Counter(c['type'] for c in chains))
print('maxspeed verdeling', Counter(s['maxspeed'] for s in segs))
