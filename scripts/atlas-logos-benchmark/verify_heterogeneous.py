#!/usr/bin/env python3
"""Export fixture requests and verify DTO/extractor compatibility without inference."""

import argparse
from copy import deepcopy
import hashlib
import json
import os
from pathlib import Path
import subprocess

import heterogeneous as h
import run


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--classpath-file",
        type=Path,
        required=True,
        help="Classpath of the compiled Artemis runtime to verify",
    )
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    os.environ["ATLAS_BENCHMARK_FIXTURE"] = "heterogeneous"
    data = run.fixture()
    args.output.mkdir(parents=True, exist_ok=True)
    cases = []
    for obj in h.objects(data):
        for phase in ("initial", "semantic-update"):
            value = deepcopy(obj)
            if phase == "semantic-update":
                value.update(value["semanticUpdate"])
            cases.append(
                {
                    "key": obj["id"],
                    "kind": obj["kind"],
                    "type": obj["type"],
                    "phase": phase,
                    "payload": h.payload(run, value, 1),
                }
            )
    request_file = args.output / "dto-cases.json"
    run.write(request_file, cases)
    classpath = args.classpath_file.resolve().read_text().strip()
    paths = classpath.split(os.pathsep)
    missing = [p for p in paths if not Path(p).exists()]
    if missing:
        raise RuntimeError(f"Runtime classpath contains {len(missing)} missing paths")
    java = (
        str(Path(os.environ["JAVA_HOME"]) / "bin/java")
        if os.environ.get("JAVA_HOME")
        else "java"
    )
    result = subprocess.run(
        [
            java,
            "-cp",
            classpath,
            str(Path(__file__).with_name("VerifyHeterogeneousFixture.java")),
            str(request_file.resolve()),
            str((args.output / "extraction.json").resolve()),
        ],
        check=False,
    )
    if result.returncode:
        return result.returncode
    extraction = run.load(args.output / "extraction.json")
    if len(extraction) != 144:
        raise RuntimeError("Expected 72 initial and 72 revised extraction cases")
    runtime_classes = {}
    for path in paths:
        root = Path(path)
        if root.is_dir():
            for module in (
                "atlas",
                "quiz",
                "programming",
                "text",
                "modeling",
                "fileupload",
                "lecture",
            ):
                for file in (root / "de/tum/cit/aet/artemis" / module).rglob("*.class"):
                    runtime_classes[str(file.relative_to(root))] = hashlib.sha256(
                        file.read_bytes()
                    ).hexdigest()
    run.write(
        args.output / "verification.json",
        {
            "fixtureSha256": run.digest(data),
            "cases": len(extraction),
            "classpathFile": str(args.classpath_file.resolve()),
            "compiledClassHashes": runtime_classes,
            "status": "offline-dto-extraction-passed",
            "limits": [
                "No database persistence or HTTP CRUD",
                "No event/scheduler execution",
                "No provider calls or flavor stripping",
                "Content review is not expert-reviewed mapping-quality evaluation",
            ],
        },
    )
    print(
        json.dumps(
            {
                "status": "offline-dto-extraction-passed",
                "cases": len(extraction),
                "fixtureSha256": run.digest(data),
            }
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
