"""Referentie-rekenmotor (Python) — wordt 1-op-1 omgezet naar Kotlin.
Principes: (1) voortdurend map-matchen i.p.v. start-trigger, (2) ringbuffer met
GPS-historiek om de echte instaptijd terug te vinden, (3) expliciete koppeling
van aaneensluitende trajecten."""
import math
from collections import deque
from dataclasses import dataclass, field

R_EARTH = 6371000.0

def xy(p, ref):
    k = math.cos(math.radians(ref[0]))
    return ((p[1]-ref[1])*math.pi/180*R_EARTH*k, (p[0]-ref[0])*math.pi/180*R_EARTH)

class Polyline:
    def __init__(self, pts):
        self.ref = pts[0]
        self.p = [xy(q, self.ref) for q in pts]
        self.cum = [0.0]
        for a, b in zip(self.p, self.p[1:]):
            self.cum.append(self.cum[-1] + math.hypot(b[0]-a[0], b[1]-a[1]))
        self.length = self.cum[-1]

    def project(self, latlon):
        """-> (afstand tot lijn m, voortgang m (mag <0 of >L: verlengd), richting graden)"""
        x, y = xy(latlon, self.ref)
        best = None
        n = len(self.p) - 1
        for i in range(n):
            (ax, ay), (bx, by) = self.p[i], self.p[i+1]
            dx, dy = bx-ax, by-ay; l2 = dx*dx+dy*dy
            if l2 == 0: continue
            t = ((x-ax)*dx+(y-ay)*dy)/l2
            lo = -math.inf if i == 0 else 0.0     # eerste stuk verlengen naar achter
            hi = math.inf if i == n-1 else 1.0    # laatste stuk verlengen naar voor
            tc = max(lo, min(hi, t))
            d = math.hypot(x-(ax+tc*dx), y-(ay+tc*dy))
            if best is None or d < best[0]:
                hd = (math.degrees(math.atan2(dx, dy)) + 360) % 360
                best = (d, self.cum[i] + tc*math.sqrt(l2), hd)
        return best

@dataclass
class Segment:
    id: int
    name: str
    maxspeed: int
    poly: Polyline
    next_ids: list = field(default_factory=list)

def fine_threshold(limit):
    """Gemeten snelheid waarboven de gecorrigeerde snelheid de limiet overschrijdt (BE).
    <100 km/u: -6 km/u; >=100 km/u: -6%."""
    t = limit + 6
    return t if t < 100 else limit / 0.94

def fit_time_at(samples, target):
    """Lineaire regressie voortgang(t) over samples [(t, prog)] -> tijdstip waarop prog == target."""
    n = len(samples)
    if n < 2: return None
    mt = sum(t for t, _ in samples)/n; mp = sum(p for _, p in samples)/n
    var = sum((t-mt)**2 for t, _ in samples)
    if var == 0: return None
    a = sum((t-mt)*(p-mp) for t, p in samples)/var
    if a <= 0.5: return None            # (bijna) stilstand: geen betrouwbare fit
    return mt + (target - mp)/a

def angdiff(a, b):
    d = abs(a-b) % 360
    return min(d, 360-d)

@dataclass
class Fix:
    t: float; lat: float; lon: float; speed: float; acc: float; course: float = None

class Engine:
    MATCH_DIST = 30.0       # m
    LEAVE_DIST = 60.0       # m, langer dan LEAVE_TIME -> traject verlaten
    LEAVE_TIME = 20.0       # s
    BUFFER_S = 900.0        # 15 min historiek
    MAX_ACC = 50.0          # fixes met slechtere nauwkeurigheid negeren voor matching

    def __init__(self, segments, on_event=print):
        self.segs = {s.id: s for s in segments}
        self.buf = deque()
        self.active = None      # dict(seg, t_entry, progress, estimated, off_since)
        self.on_event = on_event
        self.results = []

    # ---------- publieke API ----------
    def on_fix(self, f: Fix):
        self.buf.append(f)
        while self.buf and f.t - self.buf[0].t > self.BUFFER_S:
            self.buf.popleft()
        if f.acc > self.MAX_ACC:
            return self.status(f.t)
        if self.active:
            self._update_active(f)
        if not self.active:
            self._try_match(f)
        return self.status(f.t)

    def status(self, t):
        a = self.active
        if not a: return None
        s = a['seg']; L = s.poly.length
        el = t - a['t_entry']
        if el <= 0: return None
        avg = a['progress'] / el * 3.6
        thr = fine_threshold(s.maxspeed)
        rem_time_at_limit = L / (s.maxspeed/3.6) - el   # s nog beschikbaar om limiet te halen
        rest = L - a['progress']
        max_rest = (rest / rem_time_at_limit * 3.6) if rem_time_at_limit > 0 else 0.0
        return dict(segment=s.id, name=s.name, limit=s.maxspeed, threshold=round(thr, 1),
                    avg=round(avg, 1), max_rest=round(max_rest, 1), remaining_m=round(rest),
                    estimated=a['estimated'],
                    color='groen' if avg <= s.maxspeed else ('oranje' if avg <= thr else 'rood'))

    # ---------- intern ----------
    def _heading(self, f):
        if f.course is not None: return f.course
        if len(self.buf) >= 2:
            p = self.buf[-2]
            if (p.lat, p.lon) != (f.lat, f.lon):
                a, b = (p.lat, p.lon), (f.lat, f.lon)
                x, y = xy(b, a)
                return (math.degrees(math.atan2(x, y)) + 360) % 360
        return None

    def _try_match(self, f):
        hd = self._heading(f)
        if hd is None or f.speed < 2.0:
            return   # zonder rijrichting nooit matchen: voorkomt match op de tegenoverliggende rijbaan
        for s in self.segs.values():
            d, prog, segh = s.poly.project((f.lat, f.lon))
            if d > self.MATCH_DIST or prog < 0 or prog > s.poly.length: continue
            if angdiff(hd, segh) > 45: continue
            t_entry, est = self._backfill_entry(s, f)
            self._activate(s, t_entry, prog, est)
            return

    def _backfill_entry(self, s, f):
        """Zoek in de historiek het moment waarop voortgang 0 werd gepasseerd (regressie rond de doorgang)."""
        hist = []
        for g in reversed(self.buf):
            if g.acc > self.MAX_ACC: continue
            d, prog, _ = s.poly.project((g.lat, g.lon))
            if d > self.LEAVE_DIST: break
            hist.append((g.t, prog))
            if prog < -60: break
        hist.reverse()
        if hist and hist[0][1] <= 0:
            near = [h for h in hist if -80 <= h[1] <= 80][-8:] or hist[-2:]
            t0 = fit_time_at(near, 0.0)
            if t0 is not None: return t0, False
        # historiek reikt niet tot het begin -> schatten met recente snelheid
        _, prog, _ = s.poly.project((f.lat, f.lon))
        return f.t - prog / max(f.speed, 1.0), True

    def _activate(self, s, t_entry, prog, est):
        self.active = dict(seg=s, t_entry=t_entry, progress=max(0.0, prog), estimated=est, off_since=None)
        self.on_event(('START', s.id, s.name, round(t_entry, 2), 'geschat' if est else 'exact'))

    def _update_active(self, f):
        a = self.active; s = a['seg']; L = s.poly.length
        d, prog, _ = s.poly.project((f.lat, f.lon))
        if d > self.LEAVE_DIST:
            a['off_since'] = a['off_since'] or f.t
            if f.t - a['off_since'] > self.LEAVE_TIME:
                self.on_event(('VERLATEN', s.id))
                self.active = None
            return
        a['off_since'] = None
        a.setdefault('hist', []).append((f.t, prog))
        if prog >= L:
            # eindpunt gepasseerd: uittijd via regressie op de laatste fixes rond het einde
            near = [h for h in a['hist'] if h[1] >= L - 80][-8:]
            t_exit = fit_time_at(near, L)
            if t_exit is None or t_exit > f.t or t_exit < a['t_entry']:
                p_prev = a['progress']; t_prev = a.get('t_last', f.t)
                frac = (L - p_prev) / (prog - p_prev) if prog > p_prev else 1.0
                t_exit = t_prev + frac*(f.t - t_prev)
            avg = L / (t_exit - a['t_entry']) * 3.6
            res = dict(segment=s.id, name=s.name, limit=s.maxspeed, avg=round(avg, 2),
                       t_entry=a['t_entry'], t_exit=t_exit, estimated=a['estimated'])
            self.results.append(res)
            self.on_event(('EINDE', s.id, round(avg, 1), round(t_exit, 2)))
            self.active = None
            # koppeling: volgend traject start exact op t_exit
            for nid in s.next_ids:
                n = self.segs.get(nid)
                if not n: continue
                dn, pn, _ = n.poly.project((f.lat, f.lon))
                if dn <= self.MATCH_DIST and pn >= -50:
                    self._activate(n, t_exit, pn, a['estimated'])
                    self.active['t_last'] = f.t
                    self.active['hist'] = [(f.t, pn)]
                    return
            return
        a['progress'] = max(a['progress'], prog)   # monotoon
        a['t_last'] = f.t
