#!/usr/bin/env bash
# scripts/verify-sbom-strict.sh
#
# R-VER-005 follow-up (2026-10-11): validates that the
# CycloneDX 1.5 strict SBOM emitted by scripts/generate-sbom.sh has
# all mandatory fields (serialNumber, metadata.component, purl +
# bom-ref on every component, dependencies[] graph). The output is
# consumable by Dependency-Track / GUAC / Syft / Grype without
# manual repair.
#
# Usage:
#   bash scripts/verify-sbom-strict.sh [path/to/sbom.cdx.json]
#   default input: dist/sbom.cdx.json
#
# Exit codes:
#   0 = the SBOM is strict (or has only documented subset gaps)
#   1 = the SBOM is missing a mandatory CycloneDX 1.5 field

set -euo pipefail

IN="${1:-dist/sbom.cdx.json}"

say() { printf '%s\n' "$*"; }
ok()   { say "  ✅ $*"; }
fail() { say "  ❌ $*"; exit 1; }

[ -f "$IN" ] || fail "SBOM not found at $IN; run scripts/generate-sbom.sh first"

# We use python3 for JSON parsing because grep/sed on multi-line JSON
# is fragile. python3 is ubiquitous in CI images (GH actions ubuntu
# runner ships it; Fedora Atomic has it; macOS brew has it).
command -v python3 >/dev/null 2>&1 || fail "python3 required for JSON validation"

say "=== AAT-REL CycloneDX 1.5 strict SBOM validator ($IN) ==="

python3 - "$IN" <<'PY'
import json, sys, re

with open(sys.argv[1]) as f:
    doc = json.load(f)

errors = []
warnings = []

# --- top-level mandatory fields (CycloneDX 1.5 §"Document") ---
if doc.get("bomFormat") != "CycloneDX":
    errors.append("bomFormat must be 'CycloneDX'")
if doc.get("specVersion") != "1.5":
    errors.append("specVersion must be '1.5'")
if not isinstance(doc.get("serialNumber"), str) \
        or not re.match(r"^urn:uuid:[0-9a-fA-F-]{36}$", doc.get("serialNumber", "")):
    errors.append("serialNumber must be 'urn:uuid:<rfc4122>'")
if not isinstance(doc.get("version"), int) or doc["version"] != 1:
    errors.append("version must be the integer 1")

# --- metadata ---
md = doc.get("metadata")
if not isinstance(md, dict):
    errors.append("metadata is mandatory")
else:
    if not re.match(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$", md.get("timestamp", "")):
        errors.append("metadata.timestamp must be ISO-8601 Z-suffixed UTC")
    if not isinstance(md.get("tools"), list) or len(md["tools"]) == 0:
        errors.append("metadata.tools must be a non-empty array")
    if not isinstance(md.get("component"), dict):
        errors.append("metadata.component is mandatory in CycloneDX 1.5 strict")

# --- components: every one needs purl + bom-ref ---
comps = doc.get("components", [])
if not isinstance(comps, list) or len(comps) == 0:
    errors.append("components must be a non-empty array")
else:
    for c in comps:
        if c.get("type") != "library":
            errors.append(f"component {c.get('group')}/{c.get('name')} must have type='library'")
        if not c.get("purl"):
            errors.append(f"component {c.get('group')}/{c.get('name')}@{c.get('version')} missing purl")
        if not c.get("bom-ref"):
            errors.append(f"component {c.get('group')}/{c.get('name')}@{c.get('version')} missing bom-ref")
        # CycloneDX purl convention: bom-ref == purl. Catch drift.
        if c.get("purl") and c.get("bom-ref") and c["purl"] != c["bom-ref"]:
            errors.append(f"component {c['purl']} has purl != bom-ref ({c['bom-ref']})")

# --- dependencies graph: every component ref MUST appear in dependencies[] ---
deps = doc.get("dependencies", [])
if not isinstance(deps, list):
    errors.append("dependencies must be an array")
else:
    dep_refs = {d.get("ref") for d in deps}
    comp_refs = {c.get("bom-ref") for c in comps}
    md_component = md.get("component", {}).get("bom-ref") if md else None
    if md_component:
        comp_refs.add(md_component)
    missing = comp_refs - dep_refs
    if missing:
        errors.append(f"dependencies[] missing entries for: {sorted(missing)}")

# --- hashes[] is desirable but optional in this shell subset (the
#     distExtras task at B5.1 emits hashes[]). Warn, don't fail. ---
for c in comps:
    if not c.get("hashes"):
        warnings.append(f"component {c['purl']} has no hashes[] (use distExtras for SHA-256 provenance)")

# --- print report ---
print(f"  components: {len(comps)}")
print(f"  dependencies: {len(deps)}")
if warnings:
    print("  warnings:")
    for w in warnings[:5]:
        print(f"    - {w}")
    if len(warnings) > 5:
        print(f"    ... and {len(warnings)-5} more")

if errors:
    print("  errors:")
    for e in errors:
        print(f"    ❌ {e}")
    sys.exit(1)
sys.exit(0)
PY

say "✅ SBOM is CycloneDX 1.5 strict (bomFormat + serialNumber + purl + bom-ref + dependencies present)"