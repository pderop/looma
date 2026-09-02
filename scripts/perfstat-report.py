#!/usr/bin/env python3
"""Per-request analysis of a run of run-http-bench.sh.

    scripts/perfstat-report.py [run-dir] [--baseline=jdk] [--focus=CONFIG] [--lang=en|fr]

Reads every <config>-<family>.perfstat.txt in the directory together with its matching
<config>-<family>.client.txt, groups the runs by family (the stateKiB point), and prints one
comparison table per family against the baseline configuration.

A raw perf counter is a total over the capture window and two runs never serve the same number
of requests in that window, so every figure here is per request: the counter divided by
rps x window, where rps comes from the client file and window from "seconds time elapsed".
The method, and how to read the result, are in docs/perfstat.md.
"""

import os
import re
import sys

# L3 hit ~40-50 cycles, L2 hit ~15 (docs/benchmark.md). Used only for the order-of-magnitude
# check on the L3->L2 story -- deliberately crude, it exists to catch a shift that is in the
# right direction but far too small to explain the cycles it is being credited with.
L3_L2_GAP_CYCLES = 30
FLAT_PCT = 2.0      # below this a change is reported as "~0%"
MOVE_PCT = 5.0      # above this a cache-level shift is called a shift

LABELS = {
    "en": {
        "gain": "the gain", "cost": "the cost", "cause": "the cause",
        "more_work": "more work per request", "less_work": "less work per request",
        "no_dram": "DRAM is not involved", "less_dram": "less DRAM traffic",
        "more_dram": "more DRAM traffic",
        "closer_a": "data served", "closer_b": "closer: L3 -> L2",
        "further_a": "data served", "further_b": "further: L2 -> L3",
        "same_time": "same cost, in CPU-time",
        "metric": "metric", "ratio": "ratio",
    },
    "fr": {
        "gain": "le gain", "cost": "le coût", "cause": "la cause",
        "more_work": "du travail en plus par requête", "less_work": "du travail en moins",
        "no_dram": "la DRAM n'y est pour rien", "less_dram": "moins de trafic DRAM",
        "more_dram": "plus de trafic DRAM",
        "closer_a": "les données remontent", "closer_b": "de L3 vers L2",
        "further_a": "les données descendent", "further_b": "de L2 vers L3",
        "same_time": "le même coût, en CPU-time",
        "metric": "métrique", "ratio": "ratio",
    },
}


def counter(text, event):
    m = re.search(r"([\d,]+)\s+" + re.escape(event), text)
    return None if m is None else int(m.group(1).replace(",", ""))


def load(d, label):
    """Every per-request figure for one run, or None if perf produced nothing."""
    try:
        p = open(os.path.join(d, label + ".perfstat.txt")).read()
        c = open(os.path.join(d, label + ".client.txt")).read()
    except OSError:
        return None
    cyc, ins = counter(p, "cpu_core/cycles/"), counter(p, "cpu_core/instructions/")
    win = re.search(r"([\d.]+) seconds time elapsed", p)
    rps = re.search(r"Requests/sec:\s+([\d.]+)", c)
    ms = re.search(r"([\d.]+) msec task-clock", p)
    cpus = re.search(r"#\s+([\d.]+) CPUs utilized", p)
    if not (cyc and ins and win and rps and ms and cpus):
        return None                      # perf_event_paranoid, or a run with no steady window
    rps, win, ms, cpus = float(rps.group(1)), float(win.group(1)), float(ms.group(1)), float(cpus.group(1))
    req = rps * win                      # requests served inside the perf window
    return {
        "rps": rps, "req": req, "window": win, "cpus": cpus,
        "ghz": cyc / (ms / 1000) / 1e9,
        "cycles/req": cyc / req,
        "IPC": ins / cyc,
        "instructions/req": ins / req,
        "llc_miss/req": counter(p, "cpu_core/longest_lat_cache.miss/") / req,
        "l3_hit/req": counter(p, "cpu_core/mem_load_retired.l3_hit/") / req,
        "l2_hit/req": counter(p, "cpu_core/mem_load_retired.l2_hit/") / req,
        "cpu_us/req": ms * 1000 / req,
    }


def pct(a, b):
    return (b - a) / a * 100.0


def pctstr(a, b):
    p = pct(a, b)
    return "~0%" if abs(p) < FLAT_PCT else f"{p:+.0f}%"


def note(metric, a, b, L):
    """The annotation is derived from the numbers, never fixed: it is only ever true of a
    particular pair of runs."""
    p = pct(a[metric], b[metric])
    if metric == "cycles/req":
        return L["gain"] if p <= -FLAT_PCT else L["cost"] if p >= FLAT_PCT else ""
    if metric == "IPC":
        # cycles/req = (instructions/req) / IPC exactly, so in log space the two terms are the
        # complete and exclusive account of the cycles/req move. Name the dominant one.
        import math
        di, dn = abs(math.log(b["IPC"] / a["IPC"])), abs(math.log(b["instructions/req"] / a["instructions/req"]))
        return L["cause"] if di > 1.5 * dn else ""
    if metric == "instructions/req":
        return L["more_work"] if p >= FLAT_PCT else L["less_work"] if p <= -FLAT_PCT else ""
    if metric == "llc_miss/req":
        return L["no_dram"] if abs(p) < MOVE_PCT else L["less_dram"] if p < 0 else L["more_dram"]
    return ""


def cache_shift(a, b, L):
    """The L3/L2 pair is one statement, so it gets one bracketed two-line annotation."""
    p3, p2 = pct(a["l3_hit/req"], b["l3_hit/req"]), pct(a["l2_hit/req"], b["l2_hit/req"])
    if p3 <= -MOVE_PCT and p2 >= MOVE_PCT:
        return L["closer_a"], L["closer_b"]
    if p3 >= MOVE_PCT and p2 <= -MOVE_PCT:
        return L["further_a"], L["further_b"]
    return "", ""


ROWS = [("rps", "{:.0f}"), ("cycles/req", "{:.0f}"), ("IPC", "{:.3f}"),
        ("instructions/req", "{:.0f}"), ("llc_miss/req", "{:.0f}"),
        ("l3_hit/req", "{:.0f}"), ("l2_hit/req", "{:.0f}"), ("cpu_us/req", "{:.1f}")]


def table(base_name, base, others, L, focus):
    w = max(len(m) for m, _ in ROWS) + 1
    cw = max(11, max(len(n) for n in [base_name] + [n for n, _ in others]) + 2)
    head = f"{L['metric']:<{w}}{base_name:>{cw}}"
    for n, _ in others:
        head += f"{n:>{cw}}{L['ratio']:>9}"
    print(head)
    for metric, fmt in ROWS:
        line = f"{metric:<{w}}" + f"{fmt.format(base[metric]):>{cw}}"
        for _, o in others:
            line += f"{fmt.format(o[metric]):>{cw}}{pctstr(base[metric], o[metric]):>9}"
        first = focus
        if metric == "l3_hit/req":
            top, _bot = cache_shift(base, first, L)
            line += f"   ┐ {top}" if top else ""
        elif metric == "l2_hit/req":
            _top, bot = cache_shift(base, first, L)
            line += f"   ┘ {bot}" if bot else ""
        elif metric == "cpu_us/req":
            line += f"   ← {L['same_time']}"
        else:
            n = note(metric, base, first, L)
            line += f"   ← {n}" if n else ""
        print(line)


def checks(base_name, base, others, carriers):
    """What has to hold before the table above means anything (docs/perfstat.md section 7)."""
    low = False
    for n, o in [(base_name, base)] + others:
        occ = o["cpus"] / carriers * 100 if carriers else float("nan")
        flag = carriers and occ < 90
        low = low or flag
        print(f"  {n:<16} {o['ghz']:.3f} GHz   occupancy {occ:5.1f}%{' *' if flag else '  '}  "
              f"window {o['window']:.1f}s   {o['req']:,.0f} requests")
    if low:
        print("  * under ~90% occupancy the carriers idled on the upstream: read cycles/req for "
              "those runs, not rps")
    spread = max(o["ghz"] for _, o in [(base_name, base)] + others) / \
        min(o["ghz"] for _, o in [(base_name, base)] + others)
    if spread > 1.02:
        print(f"  !! clocks differ by {(spread - 1) * 100:.1f}% -- you are comparing frequencies, "
              f"not code (check no_turbo/governor in env.txt)")


def attribution(base_name, base, others, L):
    """rps = (CPUs utilized x GHz) / (cycles/req), exactly. Factorised between two runs it splits
    a throughput gain into the only three things that can produce one."""
    for n, o in others:
        f_cyc = base["cycles/req"] / o["cycles/req"]
        f_occ = o["cpus"] / base["cpus"]
        f_clk = o["ghz"] / base["ghz"]
        print(f"  {n} vs {base_name}:  rps x{o['rps'] / base['rps']:.3f}"
              f"  =  cheaper work x{f_cyc:.3f}"
              f"  x  occupancy x{f_occ:.3f}"
              f"  x  clock x{f_clk:.3f}   (= x{f_cyc * f_occ * f_clk:.3f})")


def ipc_counterfactual(base_name, base, others):
    for n, o in others:
        if (o["cycles/req"] < base["cycles/req"] * (1 - FLAT_PCT / 100)
                and o["IPC"] > base["IPC"] * 1.02
                and o["instructions/req"] > base["instructions/req"]):
            cf = o["instructions/req"] / base["IPC"]
            print(f"  {n} runs {pct(base['instructions/req'], o['instructions/req']):+.1f}% more "
                  f"instructions per request; at {base_name}'s IPC of {base['IPC']:.3f} they would "
                  f"cost {cf:,.0f} cycles/req vs {base['cycles/req']:,.0f} -- worse than "
                  f"{base_name}. The whole gain is IPC.")


def magnitude(base_name, base, others):
    """The honest limit of these six events: check the L3->L2 shift against the cycles it is
    being asked to explain before believing the locality story."""
    for n, o in others:
        moved = base["l3_hit/req"] - o["l3_hit/req"]
        gained = base["cycles/req"] - o["cycles/req"]
        if moved <= 0 or gained <= 0:
            continue
        share = moved * L3_L2_GAP_CYCLES / gained * 100
        verdict = ("consistent in direction but only ~{:.0f}% of the magnitude -- the rest is most "
                   "plausibly coherence (HITM), which mem_load_retired cannot see: read the c2c "
                   "table below").format(share) if share < 50 else \
            "accounts for ~{:.0f}% of the gain".format(share)
        print(f"  {n} vs {base_name}: {moved:.0f} loads/req left L3, worth ~{moved * L3_L2_GAP_CYCLES:,.0f} "
              f"cycles/req against {gained:,.0f} observed -- {verdict}")


def c2c(d, configs):
    """One fixed operating point, not per family (run-http-bench.sh captures c2c once)."""
    rows = []
    for cfg in configs:
        try:
            t = open(os.path.join(d, f"c2c-{cfg}.c2c.txt")).read()
        except OSError:
            continue
        def field(name):                      # c2c prints "Label   :   value"
            m = re.search(re.escape(name) + r"\s*:\s*([\d,]+)", t)
            return int(m.group(1).replace(",", "")) if m else None
        loads = field("Load Operations")
        hitm = field("Load Local HITM") or 0
        remote = field("Load Remote HITM") or 0
        if loads:
            rows.append((cfg, loads, hitm + remote, (hitm + remote) / loads * 100))
    if not rows:
        return
    print("\n== perf c2c (one fixed operating point) "
          "-- the coherence evidence perf stat cannot give\n")
    print(f"  {'config':<12}{'load ops':>12}{'HITM':>10}{'HITM/load':>12}")
    for cfg, loads, h, p in sorted(rows, key=lambda r: r[3]):
        print(f"  {cfg:<12}{loads:>12,}{h:>10,}{p:>11.2f}%")
    print("\n  Expected order: carrier < stealing < jdk (docs/benchmark.md).")


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    opts = dict(a[2:].split("=", 1) if "=" in a else (a[2:], "") for a in sys.argv[1:] if a.startswith("--"))
    lang = LABELS.get(opts.get("lang", "en"), LABELS["en"])
    baseline = opts.get("baseline") or "jdk"

    d = args[0] if args else None
    if d is None:
        root = "target/http-bench"
        runs = sorted(os.path.join(root, x) for x in os.listdir(root)) if os.path.isdir(root) else []
        if not runs:
            sys.exit("no run directory given and none found under target/http-bench/")
        d = runs[-1]
    if not os.path.isdir(d):
        sys.exit(f"{d}: not a directory")

    labels = sorted(f[:-len(".perfstat.txt")] for f in os.listdir(d) if f.endswith(".perfstat.txt"))
    families, configs = {}, []
    for label in labels:
        if "-" not in label:
            continue
        cfg, fam = label.rsplit("-", 1)
        families.setdefault(fam, {})[cfg] = label
        if cfg not in configs:
            configs.append(cfg)

    carriers = 0
    try:
        m = re.search(r"\((\d+) carriers\)", open(os.path.join(d, "env.txt")).read())
        carriers = int(m.group(1)) if m else 0
    except OSError:
        pass

    print(f"\n{d}   baseline: {baseline}"
          f"{f'   {carriers} carriers' if carriers else ''}\n")

    for fam in sorted(families, key=lambda f: int(re.sub(r"\D", "", f) or 0)):
        runs = {cfg: load(d, lbl) for cfg, lbl in families[fam].items()}
        runs = {c: r for c, r in runs.items() if r}
        if baseline not in runs:
            print(f"== {fam}: baseline '{baseline}' missing or has no counters -- skipped\n")
            continue
        base = runs[baseline]
        others = [(c, runs[c]) for c in sorted(runs) if c != baseline]
        if not others:
            print(f"== {fam}: nothing to compare against\n")
            continue
        # The annotation column can only speak about one comparison. Default to the challenger
        # with the lowest cycles/req -- the headline of the family -- and say so.
        focus_name = opts.get("focus") or min(others, key=lambda o: o[1]["cycles/req"])[0]
        focus = dict(others)[focus_name]

        print("=" * 78)
        print(f"== {fam}          annotations: {focus_name} vs {baseline}\n")
        checks(baseline, base, others, carriers)
        print()
        table(baseline, base, others, lang, focus)
        print()
        attribution(baseline, base, others, lang)
        ipc_counterfactual(baseline, base, others)
        magnitude(baseline, base, others)
        print()

    c2c(d, configs)
    print(f"\nMethod, identities and traps: docs/perfstat.md\n")


if __name__ == "__main__":
    main()
