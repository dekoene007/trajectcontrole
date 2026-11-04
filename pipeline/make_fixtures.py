"""Gesimuleerde ritten -> fixtures voor de Kotlin-unittests (zelfde scenario's als test_engine.py)."""
import io, contextlib, math, sys, os
with contextlib.redirect_stdout(io.StringIO()):
    import test_engine as T
out = sys.argv[1]; os.makedirs(out, exist_ok=True)
X = T.X
scen = [
 ('s1_aaneensluitend', [T.A, T.B], lambda s: 52/3.6 if s < X else 33/3.6, {}),
 ('s2_ruis8m', [T.A, T.B], lambda s: (45 + 15*math.sin(s/80))/3.6 if s < X else (28 + 8*math.sin(s/50))/3.6, dict(noise=8.0, seed=3)),
 ('s3_tunnel', [T.A, T.B], lambda s: 50/3.6 if s < X else 30/3.6, dict(gaps=[(40, 65)])),
 ('s4_slechte_gps_start', [T.A, T.B], lambda s: 50/3.6 if s < X else 30/3.6, dict(bad_acc=[(380, 650)])),
 ('s5_e40_kust', [T.E1, T.E2, T.E3], T.e40_profile, dict(noise=6.0, gaps=[(200, 230)], seed=7)),
 ('s6_tweerichting_omgekeerd', [-T.bid], lambda s: 48/3.6, dict(noise=5.0)),
]
for name, ids, fn, kw in scen:
    pts, lead = T.route_points(ids)
    fixes, t_at = T.drive(pts, fn, **kw)
    gt = T.truth(ids, lead, t_at)
    with open(f'{out}/{name}.fix', 'w') as f:
        for g in gt: f.write(f"#truth\t{g['seg']}\t{g['t_entry']:.3f}\t{g['avg']:.3f}\n")
        for x in fixes: f.write(f'{x.t:.3f}\t{x.lat:.7f}\t{x.lon:.7f}\t{x.speed:.3f}\t{x.acc:.1f}\n')
    print(name, len(fixes), 'fixes', [round(g['avg'], 1) for g in gt])
