#!/usr/bin/env python3
"""Generate public synthetic packages; preserve already existing packages."""
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1] / "demo-data"
CASES = [
    ("structure/case-good", "STRUCTURE", "BIW_TORSION", "V37", 42, 1.0, False),
    ("structure/case-001", "STRUCTURE", "BIW_TORSION", "V37", 43, 1.02, False),
    ("structure/case-baseline", "STRUCTURE", "BIW_TORSION", "V36", 11, 0.95, False),
    ("structure/case-current", "STRUCTURE", "BIW_TORSION", "V37", 11, 1.05, False),
    ("structure/case-missing-include", "STRUCTURE", "BIW_TORSION", "V37", 51, 1.0, True),
    ("nvh/case-001", "NVH", "CABIN_NVH", "P1", 61, 1.0, False),
    ("crash/case-001", "CRASH", "FRONTAL_CRASH", "P1", 71, 1.0, False),
    ("cfd/case-001", "CFD", "DRAG", "P1", 81, 1.0, False),
]

for name, discipline, condition, revision, seed, scale, missing in CASES:
    folder = ROOT / name
    if folder.exists() and any(folder.iterdir()):
        print(f"preserved {name}")
        continue
    folder.mkdir(parents=True, exist_ok=True)
    includes = ["missing_material.inc" if missing else "materials.inc", "properties.inc", "loads.inc"]
    files = {
        "model.fem": "$ SYNTHETIC INPUT: public workflow fixture, not a commercial solver deck\n" + "\n".join(f"INCLUDE '{n}'" for n in includes) + "\n",
        "properties.inc": f"$ SYNTHETIC properties\nTHICKNESS_SCALE {scale}\n",
        "loads.inc": f"$ SYNTHETIC load case\nCONDITION {condition}\nSUBCASE 1\n",
        "metadata.json": json.dumps({"synthetic": True, "modelRevision": revision, "seed": seed, "origin": "public-generated-fixture"}, indent=2) + "\n",
    }
    if not missing:
        files["materials.inc"] = "$ SYNTHETIC material\nYOUNG_MODULUS 210000\nDENSITY 7850\n"
    checksums = {}
    for filename, text in files.items():
        payload = text.encode()
        (folder / filename).write_bytes(payload)
        checksums[filename] = hashlib.sha256(payload).hexdigest()
    manifest = {
        "caseId": name.upper().replace("/", "-"),
        "entryDeck": "model.fem",
        "includes": includes,
        "analysisType": discipline,
        "conditionCode": condition,
        "modelRevision": revision,
        "parameters": {"seed": seed, "thicknessScale": scale, "subcase": 1},
        "checksums": checksums,
    }
    (folder / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print(f"created {name}")
