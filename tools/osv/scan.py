"""Dependency vulnerability check for NEXIO SOCIAL AI.

Reads build/osv/gradle.lockfile (the resolved :app release runtime graph written by
tools/osv/resolved-deps.init.gradle.kts), scans it with OSV-Scanner, and
  * fails (exit 1) on a CRITICAL advisory in a direct dependency,
  * lists every HIGH and CRITICAL advisory, direct or transitive, in build/osv/report.md.

Usage (see docs/BUILD.md):
  python tools/osv/scan.py --download-db   # first run: fetches the OSV database for Maven
  python tools/osv/scan.py                 # later runs: offline, against that local copy

The scanner binary is found via --scanner, the OSV_SCANNER environment variable, or PATH.
Suppressions live in tools/osv/suppressions.txt; each line needs an advisory id, a written
reason and a link, or the script refuses to run.
"""
import argparse
import json
import os
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "build" / "osv"
LOCKFILE = OUT / "gradle.lockfile"
DIRECT = OUT / "direct.txt"
SUPPRESSIONS = Path(__file__).resolve().parent / "suppressions.txt"


def load_suppressions():
    allowed = {}
    if not SUPPRESSIONS.exists():
        return allowed
    for number, raw in enumerate(SUPPRESSIONS.read_text(encoding="utf-8").splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        parts = [p.strip() for p in line.split("|")]
        if len(parts) != 3 or not all(parts) or not parts[2].startswith("https://"):
            sys.exit(f"{SUPPRESSIONS.name}:{number}: expected 'ADVISORY-ID | reason | https://advisory-link'")
        allowed[parts[0]] = (parts[1], parts[2])
    return allowed


def severity_of(vuln, group_scores):
    score = group_scores.get(vuln["id"])
    if score is not None:
        if score >= 9.0:
            return "CRITICAL", score
        if score >= 7.0:
            return "HIGH", score
        if score >= 4.0:
            return "MODERATE", score
        return "LOW", score
    label = str((vuln.get("database_specific") or {}).get("severity", "")).upper()
    return (label or "UNKNOWN"), None


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--scanner", default=os.environ.get("OSV_SCANNER") or shutil.which("osv-scanner"))
    parser.add_argument("--download-db", action="store_true", help="fetch or refresh the local OSV database first")
    args = parser.parse_args()

    if not args.scanner:
        sys.exit("OSV-Scanner not found. Pass --scanner or set OSV_SCANNER (see docs/BUILD.md).")
    if not LOCKFILE.exists() or not DIRECT.exists():
        sys.exit("Run first: ./gradlew -I tools/osv/resolved-deps.init.gradle.kts :app:osvLockfile")

    command = [args.scanner, "scan", "source", "--format", "json", "--offline-vulnerabilities"]
    if args.download_db:
        command.append("--download-offline-databases")
    command += ["-L", str(LOCKFILE)]
    run = subprocess.run(command, capture_output=True, text=True, encoding="utf-8")
    # 0 = clean, 1 = advisories found; anything else is a scanner failure, not a result.
    if run.returncode not in (0, 1):
        sys.stderr.write(run.stderr)
        sys.exit(f"OSV-Scanner failed with exit code {run.returncode}")
    data = json.loads(run.stdout or "{}")

    direct = set(DIRECT.read_text(encoding="utf-8").split())
    scanned = sum(1 for line in LOCKFILE.read_text(encoding="utf-8").splitlines() if "=" in line and not line.startswith(("#", "empty=")))
    suppressed = load_suppressions()
    findings = []
    for result in data.get("results") or []:
        for pkg in result.get("packages") or []:
            info = pkg["package"]
            coordinate = f"{info['name']}:{info['version']}"
            scores = {}
            for group in pkg.get("groups") or []:
                try:
                    score = float(group.get("max_severity") or "")
                except ValueError:
                    continue
                for vid in group.get("ids") or []:
                    scores[vid] = score
            for vuln in pkg.get("vulnerabilities") or []:
                level, score = severity_of(vuln, scores)
                findings.append({
                    "id": vuln["id"],
                    "aliases": vuln.get("aliases") or [],
                    "package": coordinate,
                    "direct": coordinate in direct,
                    "severity": level,
                    "score": score,
                    "summary": vuln.get("summary", ""),
                    "suppressed": suppressed.get(vuln["id"]),
                })

    blocking = [f for f in findings if f["direct"] and f["severity"] == "CRITICAL" and not f["suppressed"]]
    lines = [
        "# OSV dependency scan",
        "",
        f"Scanner: `{' '.join(Path(c).name if i == 0 else c for i, c in enumerate(command))}`",
        f"Modules scanned: {scanned} (direct: {len(direct)})",
        f"Advisories: {len(findings)}; blocking (critical, direct, unsuppressed): {len(blocking)}",
        "",
    ]
    if findings:
        lines += ["| Advisory | Package | Direct | Severity | Score | Summary | Suppressed |", "|---|---|---|---|---|---|---|"]
        for f in sorted(findings, key=lambda f: (-(f["score"] or 0), f["package"])):
            note = f"{f['suppressed'][0]} ({f['suppressed'][1]})" if f["suppressed"] else ""
            lines.append(f"| {f['id']} | {f['package']} | {'yes' if f['direct'] else 'no'} | {f['severity']} | {f['score'] or ''} | {f['summary']} | {note} |")
    else:
        lines.append("No known advisories.")
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / "report.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    (OUT / "report.json").write_text(json.dumps(findings, indent=2), encoding="utf-8")
    print("\n".join(lines))
    if blocking:
        print(f"\nFAILED: {len(blocking)} critical advisory(ies) in direct dependencies.")
        sys.exit(1)
    print("\nPASSED: no critical advisory in a direct dependency.")


if __name__ == "__main__":
    main()
