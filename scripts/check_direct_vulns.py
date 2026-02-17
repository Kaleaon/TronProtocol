#!/usr/bin/env python3
"""Fail build if any direct Maven dependency has known CRITICAL vulnerabilities in OSV."""

from __future__ import annotations

import json
import time
import re
import sys
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CATALOG = ROOT / "gradle" / "libs.versions.toml"
GRADLE_FILES = list(ROOT.glob("**/build.gradle")) + list(ROOT.glob("**/build.gradle.kts"))
OSV_QUERY = "https://api.osv.dev/v1/query"
OSV_RETRIES = 3

STRING_DEP_RE = re.compile(r"(?:implementation|api|compileOnly|runtimeOnly|testImplementation|androidTestImplementation|\w+Implementation)\s+['\"]([^'\"]+)['\"]")
ALIAS_DEP_RE = re.compile(r"(?:implementation|api|compileOnly|runtimeOnly|testImplementation|androidTestImplementation|\w+Implementation)\s+libs\.([A-Za-z0-9_.]+)")
LIB_DEF_RE = re.compile(r"^([A-Za-z0-9\-_.]+)\s*=\s*\{\s*module\s*=\s*\"([^\"]+)\"(?:,\s*version\.ref\s*=\s*\"([^\"]+)\")?\s*\}")
VER_DEF_RE = re.compile(r"^([A-Za-z0-9\-_.]+)\s*=\s*\"([^\"]+)\"")


def normalize_alias(alias: str) -> str:
    return re.sub(r"[-_.]+", ".", alias)


def load_catalog() -> dict[str, str]:
    if not CATALOG.exists():
        return {}

    versions: dict[str, str] = {}
    libs: dict[str, str] = {}
    section = None

    for raw in CATALOG.read_text().splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if line.startswith("[") and line.endswith("]"):
            section = line[1:-1]
            continue
        if section == "versions":
            m = VER_DEF_RE.match(line)
            if m:
                versions[m.group(1)] = m.group(2)
        elif section == "libraries":
            m = LIB_DEF_RE.match(line)
            if m:
                alias, module, version_ref = m.groups()
                if version_ref and version_ref in versions:
                    libs[normalize_alias(alias)] = f"{module}:{versions[version_ref]}"
                else:
                    libs[normalize_alias(alias)] = module

    return libs


def collect_direct_dependencies(alias_map: dict[str, str]) -> set[str]:
    deps: set[str] = set()
    for gradle_file in GRADLE_FILES:
        if not gradle_file.exists():
            continue
        text = gradle_file.read_text()
        for dep in STRING_DEP_RE.findall(text):
            parts = dep.split(":")
            if len(parts) >= 3:
                deps.add(":".join(parts[:3]))
        for alias in ALIAS_DEP_RE.findall(text):
            resolved = alias_map.get(normalize_alias(alias))
            if resolved and resolved.count(":") >= 2:
                deps.add(resolved)
    return deps


def _parse_cvss_score(value: object) -> float | None:
    if value is None:
        return None
    if isinstance(value, (int, float)):
        return float(value)
    text = str(value)
    match = re.search(r"(\d+\.\d+|\d+)", text)
    if not match:
        return None
    try:
        return float(match.group(1))
    except ValueError:
        return None


def is_critical(vuln: dict) -> bool:
    for item in vuln.get("severity", []):
        score = item.get("score")
        numeric = _parse_cvss_score(score)
        if numeric is not None and numeric >= 9.0:
            return True
        if "CRITICAL" in str(score).upper():
            return True

    database_specific = vuln.get("database_specific", {}) or {}
    cvss_info = database_specific.get("cvss") or {}
    if isinstance(cvss_info, dict):
        ds_score = _parse_cvss_score(cvss_info.get("score"))
        if ds_score is not None and ds_score >= 9.0:
            return True

    severity = str(database_specific.get("severity", "")).upper()
    return severity == "CRITICAL"


def osv_query(dep: str) -> list[dict]:
    group, artifact, version = dep.split(":", 2)
    payload = {
        "version": version,
        "package": {"name": f"{group}:{artifact}", "ecosystem": "Maven"},
    }
    req = urllib.request.Request(
        OSV_QUERY,
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json"},
    )
    for attempt in range(1, OSV_RETRIES + 1):
        try:
            with urllib.request.urlopen(req, timeout=20) as resp:  # nosec B310
                data = json.loads(resp.read().decode("utf-8"))
                return data.get("vulns", [])
        except urllib.error.URLError:
            if attempt == OSV_RETRIES:
                raise
            time.sleep(attempt)
    return []


def main() -> int:
    alias_map = load_catalog()
    direct_deps = sorted(collect_direct_dependencies(alias_map))

    if not direct_deps:
        print("No direct dependencies found.")
        return 0

    print(f"Checking {len(direct_deps)} direct Maven dependencies against OSV...")
    critical_hits: list[tuple[str, str, str]] = []

    for dep in direct_deps:
        try:
            vulns = osv_query(dep)
        except urllib.error.URLError as exc:
            print(f"ERROR: Could not query OSV for {dep}: {exc}")
            return 2

        for vuln in vulns:
            if is_critical(vuln):
                critical_hits.append((dep, vuln.get("id", "UNKNOWN"), vuln.get("summary", "")))

    if critical_hits:
        print("\nCRITICAL vulnerabilities detected in direct dependencies:")
        for dep, vuln_id, summary in critical_hits:
            print(f" - {dep} :: {vuln_id} :: {summary}")
        return 1

    print("No CRITICAL vulnerabilities detected in direct dependencies.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
