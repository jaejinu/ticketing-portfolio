#!/usr/bin/env python3
"""Audit exact local Compose image IDs; never publish Docker inspect/config data."""
import argparse
import collections
import datetime
import json
import os
from pathlib import Path
import subprocess


def output(*args):
    return subprocess.check_output(args, text=True).strip()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", default="ticketing")
    parser.add_argument("--output", type=Path, default=Path("build/container-audit"))
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output / "summary.json").unlink(missing_ok=True)
    ids = output("docker", "ps", "-q", "--filter", f"label=com.docker.compose.project={args.project}").split()
    if not ids:
        raise SystemExit("No running containers found; audit incomplete")
    containers = json.loads(output("docker", "inspect", *ids))
    env = os.environ.copy()
    if not env.get("DOCKER_HOST") and not env.get("DOCKER_CONTEXT"):
        env["DOCKER_HOST"] = output("docker", "context", "inspect", "--format", "{{.Endpoints.docker.Host}}")
    summary = {
        "scannedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(),
        "scanner": json.loads(output("trivy", "version", "--format", "json")),
        "scope": "Running Compose image IDs; OS and bundled language packages. Findings are package/advisory matches, not proven exploits.",
        "images": [],
    }
    # Sequential scans avoid Trivy's shared cache lock contention.
    for i, container in enumerate(containers):
        report = args.output / f"image-{i}.json"
        subprocess.run([
            "trivy", "image", "--image-src", "docker", "--scanners", "vuln",
            "--timeout", "10m", "--format", "json", "--output", str(report),
            container["Image"],
        ], env=env, check=True)
        data = json.loads(report.read_text())
        findings = []
        for result in data.get("Results", []):
            for vuln in result.get("Vulnerabilities", []):
                findings.append({
                    "class": result.get("Class"), "type": result.get("Type"),
                    "id": vuln["VulnerabilityID"], "package": vuln["PkgName"],
                    "installed": vuln["InstalledVersion"], "fixed": vuln.get("FixedVersion", ""),
                    "severity": vuln["Severity"],
                })
        summary["images"].append({
            "service": container["Config"]["Labels"]["com.docker.compose.service"],
            "image": container["Config"]["Image"], "imageId": container["Image"],
            "os": data.get("Metadata", {}).get("OS"),
            "counts": dict(collections.Counter(v["severity"] for v in findings)),
            "findings": findings,
        })
    # Only the allowlisted fields above are suitable for publication. Raw reports
    # can contain image environment/build metadata and stay in ignored build/.
    (args.output / "summary.json").write_text(json.dumps(summary, indent=2) + "\n")
    count = sum(len(i["findings"]) for i in summary["images"])
    print(f"{len(containers)} images, {count} package/advisory matches")
    raise SystemExit(1 if count else 0)


if __name__ == "__main__":
    main()
