#!/usr/bin/env python3
"""Thin, sequential lifecycle driver for the bounded Atlas Logos campaign."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import random
import math
import signal
import threading
import sys
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any
from types import SimpleNamespace

sys.path.insert(0, str(Path(__file__).resolve().parent))
import heterogeneous as hetero
from integrity import EvidenceGuard

ROOT = Path(__file__).resolve().parents[2]
CONDITIONS = (
    "maintained",
    "assignment-only",
    "mixed",
    "bootstrap",
    "single-exercise",
    "lecture-maintenance",
)
FORMAL_CONFIRMATION = "RUN-ATLAS-LOGOS-FORMAL"
HTTP = urllib.request.build_opener(urllib.request.HTTPCookieProcessor())


def now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def root_for(campaign: str) -> Path:
    configured = os.environ.get("ATLAS_BENCHMARK_EVIDENCE_DIR")
    return Path(configured).expanduser().resolve() if configured else ROOT / "build/atlas-logos-benchmark/evidence" / campaign


def prices() -> Path:
    return (
        Path(
            os.environ.get(
                "ATLAS_BENCHMARK_PRICING_FILE",
                ROOT / "docker/atlas-logos-benchmark/pricing.json",
            )
        )
        .expanduser()
        .resolve()
    )


def digest(value: Any) -> str:
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def code_fingerprint(price_file: Path) -> str:
    paths = set()
    for folder in (
        "src/main/java/de/tum/cit/aet/artemis/atlas",
        "src/main/java/de/tum/cit/aet/artemis/core/benchmark",
        "src/main/resources/prompts/atlas",
        "scripts/atlas-logos-benchmark",
        "docker/atlas-logos-benchmark",
    ):
        paths.update(path for path in (ROOT / folder).rglob("*") if path.suffix in {".java", ".st", ".py", ".sh", ".json", ".yml", ".yaml"})
    paths.update(
        (
            ROOT / "src/main/resources/config/application-atlas-logos-benchmark.yml",
            price_file,
        )
    )
    return digest(
        {
            str(path.relative_to(ROOT)) if path.is_relative_to(ROOT) else path.name: hashlib.sha256(path.read_bytes()).hexdigest()
            for path in sorted(paths)
        }
    )


def git_head() -> str:
    result = subprocess.run(
        ["git", "rev-parse", "HEAD"],
        cwd=ROOT,
        capture_output=True,
        text=True,
        check=False,
    )
    return result.stdout.strip() if result.returncode == 0 else "unknown"


def load(path: Path, default: Any = None) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError:
        return default


def write(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, temporary = tempfile.mkstemp(dir=path.parent, prefix=f".{path.name}.")
    try:
        with open(fd, "w", encoding="utf-8") as stream:
            json.dump(value, stream, indent=2, sort_keys=True)
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def append(path: Path, value: dict[str, Any]) -> None:
    with path.open("a", encoding="utf-8") as stream:
        stream.write(json.dumps(value, sort_keys=True, separators=(",", ":")) + "\n")
        stream.flush()
        os.fsync(stream.fileno())


def mixed_driver():
    """Expose only shared lifecycle operations to the mixed-content adapter."""
    return SimpleNamespace(api=api, now=now, digest=digest, entity_id=entity_id,
                           course_payload=course_payload, set_automation=set_automation)


def mixed_content_reviewed(data):
    review = load(Path(__file__).with_name("content-review.json"), {})
    return review.get("status") == "reviewed" and review.get("fixtureSha256") == digest(data)


def fixture() -> dict[str, Any]:
    selection = os.environ.get("ATLAS_BENCHMARK_FIXTURE", "baseline")
    if selection not in {"baseline", "heterogeneous"}:
        raise ValueError("ATLAS_BENCHMARK_FIXTURE must be baseline or heterogeneous")
    if selection == "heterogeneous":
        return hetero.validate(load(Path(__file__).with_name("fixture-heterogeneous.json")))
    value = load(Path(__file__).with_name("fixture.json"))
    if not isinstance(value, dict) or len(value.get("topics", [])) != 12 or sum(len(topic["exercises"]) for topic in value["topics"]) != 60:
        raise RuntimeError("fixture.json must contain 12 topics and 60 exercises")
    return value


def plans() -> dict[str, dict[str, Any]]:
    anchors = list(range(0, 60, 5))
    return {
        "maintained": {
            "competencies": 12,
            "anchors": anchors,
            "background": 48,
            "changed": anchors,
            "trigger": "automatic",
        },
        "assignment-only": {
            "competencies": 12,
            "anchors": [],
            "background": 48,
            "changed": anchors,
            "trigger": "automatic",
        },
        "mixed": {
            "competencies": 6,
            "anchors": [],
            "background": 24,
            "changed": anchors,
            "trigger": "automatic",
        },
        "bootstrap": {
            "competencies": 0,
            "anchors": [],
            "background": 0,
            "changed": list(range(60)),
            "trigger": "automatic",
        },
        "single-exercise": {
            "competencies": 12,
            "anchors": anchors,
            "background": 48,
            "changed": [0],
            "trigger": "manual",
        },
        "lecture-maintenance": {
            "competencies": 12,
            "anchors": anchors,
            "background": 48,
            "changed": [],
            "trigger": "automatic",
            "lecture": True,
        },
    }


def schedule(mode: str, seed: int, repetitions: int) -> list[dict[str, Any]]:
    data = fixture()
    if hetero.enabled(data):
        return hetero.schedule(data, CONDITIONS, mode, seed, repetitions, os.environ.get("ATLAS_BENCHMARK_REHEARSAL") == "1")
    if mode == "smoke" and os.environ.get("ATLAS_BENCHMARK_REHEARSAL") != "1":
        return [
            {
                "scheduleIndex": i,
                "observationId": f"smoke-{condition}-s1",
                "condition": condition,
                "repetition": 1,
                "stage": 1,
                "courseKey": f"smoke-{condition}",
                "invocationId": f"smoke-{i + 1}",
                "trigger": plans()[condition]["trigger"],
                "changedExerciseIndexes": plans()[condition]["changed"],
                "requiresObservationId": None,
            }
            for i, condition in enumerate(("single-exercise", "lecture-maintenance"))
        ]
    if mode == "smoke":
        repetitions = 1
    result, index = [], 0
    for repetition in range(1, repetitions + 1):
        order = list(CONDITIONS)
        random.Random(seed + repetition * 1_000_003).shuffle(order)
        for condition in order:
            changed = plans()[condition]["changed"]
            if condition == "bootstrap":
                changed = changed[:12]
            result.append(
                {
                    "scheduleIndex": index,
                    "observationId": f"r{repetition:02d}-{condition}-s1",
                    "condition": condition,
                    "repetition": repetition,
                    "stage": 1,
                    "courseKey": f"r{repetition:02d}-{condition}",
                    "invocationId": f"invocation-{index + 1:03d}",
                    "trigger": plans()[condition]["trigger"],
                    "changedExerciseIndexes": changed,
                    "requiresObservationId": None,
                }
            )
            index += 1
            if condition == "bootstrap":
                prior = f"r{repetition:02d}-bootstrap-s1"
                for stage, changed in (
                    (2, list(range(12, 36))),
                    (3, list(range(36, 60))),
                ):
                    observation_id = f"r{repetition:02d}-bootstrap-s{stage}"
                    result.append(
                        {
                            "scheduleIndex": index,
                            "observationId": observation_id,
                            "condition": condition,
                            "repetition": repetition,
                            "stage": stage,
                            "courseKey": f"r{repetition:02d}-bootstrap",
                            "invocationId": f"invocation-{index + 1:03d}",
                            "trigger": "automatic",
                            "changedExerciseIndexes": changed,
                            "requiresObservationId": prior,
                        }
                    )
                    prior, index = observation_id, index + 1
    return result


def link_indexes(condition: str) -> set[int]:
    if condition in {"maintained", "single-exercise", "lecture-maintenance"}:
        return set(range(60))
    links = set(plans()[condition]["anchors"])
    if condition == "assignment-only":
        links.update(index for index in range(60) if index % 5)
    if condition == "mixed":
        links.update(index for topic in range(6) for index in range(topic * 5 + 1, topic * 5 + 5))
    return links


def manifest(
    mode: str,
    campaign: str,
    seed: int,
    repetitions: int,
    price_sha: str,
    items: list[dict[str, Any]],
    data: dict[str, Any],
    code_sha: str,
) -> dict[str, Any]:
    return {
        "schemaVersion": 1,
        "campaignId": campaign,
        "campaignVersion": "atlas-logos-benchmark-v5-heterogeneous" if hetero.enabled(data) else "atlas-logos-benchmark-v4-256",
        "fixtureVersion": data["fixtureVersion"],
        "bootstrapStages": 4 if hetero.enabled(data) else 3,
        "budgetEur": campaign_budget(mode),
        "retryPolicy": "native SDK retries; retain uncertain reservations; continue independent observations",
        "mode": mode,
        "seed": seed,
        "repetitions": 1 if mode == "smoke" else repetitions,
        "toolBudget": {"sharedCallbacks": 256, "wrapUpAt": 224, "finalSlotForCompletion": True},
        "fullRehearsal": mode == "smoke" and os.environ.get("ATLAS_BENCHMARK_REHEARSAL") == "1",
        "fixtureSha256": digest(data),
        "scheduleSha256": digest(items),
        "pricingSha256": price_sha,
        "codeSha256": code_sha,
        "logosRevision": os.environ.get(
            "LOGOS_REVISION",
            "unknown; record the deployed revision before formal execution",
        ),
        "logosHost": os.environ.get("LOGOS_BASE_URL", "http://127.0.0.1:18090/v1"),
        "gitHead": git_head(),
        "observationCount": len(items),
        "createdAt": now(),
    }


def campaign_budget(mode: str) -> float:
    """Freeze a positive allowance no larger than the mode ceiling, after carry-forward."""
    ceiling = 1 if mode == "smoke" else 20
    value = float(os.environ.get("ATLAS_BENCHMARK_BUDGET_EUR", ceiling))
    if not math.isfinite(value) or not 0 < value <= ceiling:
        raise ValueError(f"Campaign budget must be positive and at most EUR {ceiling}")
    return value


def show_progress(root: Path, item: dict, done: threading.Event, interval: float = 10) -> None:
    """Read passive evidence while API calls block. This thread never changes execution."""
    started = time.monotonic()
    while not done.wait(interval):
        try:
            events = []
            path = root / "provider-events.jsonl"
            for line in path.read_text().splitlines() if path.exists() else []:
                try:
                    event = json.loads(line)
                except json.JSONDecodeError:
                    continue  # A concurrently appended last line may not yet be complete.
                if event.get("invocationId") == item["invocationId"]:
                    events.append(event)
            measured = sum(event.get("costEur") or 0 for event in events)
            unknown = sum(not event.get("usageAvailable", False) for event in events)
            retries = sum((event.get("sdkRetryCount") or 0) > 0 for event in events)
            phase = events[-1].get("phase", "waiting") if events else "waiting for first response"
            print(
                f"  {item['observationId']} | {time.monotonic() - started:.0f}s | "
                f"HTTP={len(events)} retries={retries} | last={phase} | measured EUR {measured:.4f} | unknown={unknown}",
                flush=True,
            )
        except OSError as error:
            print(f"  Progress unavailable: {type(error).__name__}", flush=True)


def active(campaign: str, mode: str, manifest_sha: str, price_sha: str) -> dict[str, Any]:
    return {
        "schemaVersion": 1,
        "campaignId": campaign,
        "manifestSha256": manifest_sha,
        "startedAt": now(),
        "updatedAt": now(),
        "status": "prepared",
        "mode": mode,
        "budgetEur": campaign_budget(mode),
        "pricingSha256": price_sha,
        "logosHost": os.environ.get("LOGOS_BASE_URL", "http://127.0.0.1:18090/v1"),
        "currentObservationId": None,
        "currentCondition": None,
        "currentRepetition": None,
        "currentStage": None,
        "currentCourseId": None,
        "currentInvocationId": None,
        "stopRequestedAt": None,
        "stopReason": None,
    }


def api(base: str, method: str, path: str, payload: Any = None, multipart: bool | str = False) -> Any:
    headers = {"Accept": "application/json"}
    if os.environ.get("ATLAS_BENCHMARK_COOKIE"):
        headers["Cookie"] = os.environ["ATLAS_BENCHMARK_COOKIE"]
    body = None
    if multipart:
        part_name = "course" if multipart is True else multipart
        if part_name not in {"course", "exercise", "attachmentVideoUnit"}:
            raise ValueError("Unsupported multipart JSON part")
        boundary = "atlas-benchmark-" + digest(payload)[:20]
        body = (
            f'--{boundary}\r\nContent-Disposition: form-data; name="{part_name}"\r\nContent-Type: application/json\r\n\r\n{json.dumps(payload, separators=(",", ":"))}\r\n--{boundary}--\r\n'
        ).encode()
        headers["Content-Type"] = f"multipart/form-data; boundary={boundary}"
    elif payload is not None:
        body = json.dumps(payload, separators=(",", ":")).encode()
        headers["Content-Type"] = "application/json"
    request = urllib.request.Request(base.rstrip("/") + path, body, headers, method=method)
    attempts = 3 if method == "GET" else 1
    timeout = (
        float(os.environ.get("ATLAS_BENCHMARK_INVOCATION_TIMEOUT_SECONDS", "1800"))
        if path.endswith("/run")
        else float(os.environ.get("ATLAS_BENCHMARK_API_TIMEOUT_SECONDS", "90"))
    )
    for attempt in range(attempts):
        try:
            with HTTP.open(request, timeout=timeout) as response:
                raw = response.read()
            return json.loads(raw) if raw else None
        except (OSError, urllib.error.URLError) as error:
            retryable = not isinstance(error, urllib.error.HTTPError) or error.code in {
                408,
                429,
                502,
                503,
                504,
            }
            if attempt + 1 < attempts and retryable:
                time.sleep(attempt + 1)
                continue
            detail = f"HTTP {error.code}" if isinstance(error, urllib.error.HTTPError) else type(error).__name__
            raise RuntimeError(f"{method} {path}: {detail}") from error


def course_payload(key: str, enabled: bool, debounce: int, campaign: str = "") -> dict[str, Any]:
    short_name = "a" + hashlib.sha256(f"{campaign}/{key}".encode()).hexdigest()[:20]
    return fixture()["courseDefaults"] | {
        "title": f"Atlas Logos {key}",
        "shortName": short_name,
        "startDate": now(),
        "endDate": (datetime.now(timezone.utc) + timedelta(days=30)).isoformat(),
        "autoOrchestratorEnabled": enabled,
        "debounceWindowSecondsOverride": debounce,
    }


def entity_id(value: Any) -> int | str:
    if isinstance(value, dict):
        if value.get("id") is not None:
            return value["id"]
        for key in ("course", "exercise", "competency", "lecture", "textUnit"):
            if isinstance(value.get(key), dict) and value[key].get("id") is not None:
                return value[key]["id"]
    raise RuntimeError("normal API response contained no entity id")


def exercise_payload(data: dict[str, Any], index: int, course_id: int | str, competency: int | str | None) -> dict[str, Any]:
    topic, exercise = (
        data["topics"][index // 5],
        data["topics"][index // 5]["exercises"][index % 5],
    )
    return data["exerciseDefaults"] | {
        "title": f"{topic['title']} - {exercise[0]}",
        "channelName": f"exercise-{course_id}-{index + 1:02d}",
        "shortName": f"exercise{index + 1:02d}",
        "problemStatement": exercise[1],
        "exampleSolution": exercise[2],
        "courseId": course_id,
        "releaseDate": now(),
        "competencyLinks": [] if competency is None else [{"competency": {"id": competency}, "weight": 1.0}],
    }


def create_fixtures(base: str, items: list[dict[str, Any]], debounce: int, campaign: str) -> dict[str, Any]:
    data, result = fixture(), {}
    if hetero.enabled(data):
        return hetero.create_fixtures(mixed_driver(), data, base, items, debounce, campaign)
    for key in dict.fromkeys(item["courseKey"] for item in items):
        condition = next(value for value in CONDITIONS if key.endswith(value))
        plan = plans()[condition]
        course = api(
            base,
            "POST",
            "/api/admin/courses",
            course_payload(key, False, debounce, campaign),
            True,
        )
        course_id = entity_id(course)
        competency_ids = [
            entity_id(
                api(
                    base,
                    "POST",
                    f"/api/atlas/courses/{course_id}/competencies",
                    {
                        "type": "competency",
                        "title": topic["title"],
                        "description": topic["description"],
                        "masteryThreshold": 100,
                        "taxonomy": topic["taxonomy"],
                    },
                )
            )
            for topic in data["topics"][: plan["competencies"]]
        ]
        links = link_indexes(condition)
        exercise_competencies, exercise_ids = [], []
        for index in range(60):
            competency = competency_ids[index // 5] if index in links and index // 5 < len(competency_ids) else None
            payload = exercise_payload(data, index, course_id, competency)
            payload["id"] = entity_id(api(base, "POST", "/api/text/text-exercises", payload))
            exercise_ids.append(payload["id"])
            exercise_competencies.append(competency)
        lecture = api(
            base,
            "POST",
            "/api/lecture/lectures",
            {
                "course": {"id": course_id},
                "title": data["lecture"]["title"],
                "description": data["lecture"]["description"],
                "startDate": now(),
                "endDate": (datetime.now(timezone.utc) + timedelta(days=30)).isoformat(),
            },
        )
        lecture_id = entity_id(lecture)
        unit_payload = {
            "type": "text",
            "name": data["lecture"]["textUnit"]["name"],
            "content": data["lecture"]["textUnit"]["content"],
            "releaseDate": now(),
            "competencyLinks": [] if not competency_ids else [{"competency": {"id": competency_ids[0]}, "weight": 1.0}],
        }
        unit_payload["id"] = entity_id(
            api(
                base,
                "POST",
                f"/api/lecture/lectures/{lecture_id}/text-units",
                unit_payload,
            )
        )
        result[key] = {
            "courseId": course_id,
            "course": course_payload(key, False, debounce, campaign) | {"id": course_id},
            "competencyIds": competency_ids,
            "exerciseIds": exercise_ids,
            "exerciseCompetencyIds": exercise_competencies,
            "lectureId": lecture_id,
            "textUnitId": unit_payload["id"],
            "automationEnabled": False,
        }
        initial = persisted_state(base, result[key])
        if initial["competencyCount"] != plan["competencies"] or initial["exerciseLinkCount"] != len(links):
            raise RuntimeError("prepared fixture mappings do not match the frozen condition")
        result[key]["initialState"] = initial
        print(
            f"Prepared {key}: course {course_id}, {len(result)}/{len({item['courseKey'] for item in items})} courses",
            flush=True,
        )
    return {"fixtureSha256": digest(data), "courses": result}


def set_automation(base: str, record: dict[str, Any], enabled: bool, debounce: int) -> None:
    if enabled:
        record["automationEnabled"] = True  # An uncertain enable must still be cleaned up.
    path = f"/api/course/courses/{record['courseId']}"
    last_error = None
    for attempt in range(3):
        current = api(base, "GET", path)
        if current.get("courseConfiguration") is None:
            raise RuntimeError("Course configuration was not loaded; automation state is unknown")
        if current.get("courseConfiguration") is not None and current.get("autoOrchestratorEnabled") == enabled and (not enabled or current.get("debounceWindowSecondsOverride") == debounce):
            record["automationEnabled"] = enabled
            return
        update = dict(current)
        update.update(
            {
                "id": record["courseId"],
                "autoOrchestratorEnabled": enabled,
                "debounceWindowSecondsOverride": debounce,
            }
        )
        try:
            api(base, "PUT", path, update, True)
            # Success and timeout both require authoritative readback. Never replay a trigger.
            last_error = None
        except (OSError, RuntimeError) as error:
            last_error = error
        time.sleep(attempt + 1)
    current = api(base, "GET", path)
    if current.get("courseConfiguration") is not None and current.get("autoOrchestratorEnabled") == enabled and (not enabled or current.get("debounceWindowSecondsOverride") == debounce):
        record["automationEnabled"] = enabled
        return
    raise RuntimeError(f"automation readback failed for course {record['courseId']}: {last_error or 'setting not persisted'}")


def persisted_state(base, record):
    """Read persisted content and mappings; model-selected counts are unrestricted."""
    if "objects" in record:
        return hetero.persisted_state(mixed_driver(), fixture(), base, record)
    course = api(
        base,
        "GET",
        f"/api/course/courses/{record['courseId']}/with-exercises-lectures-competencies",
    )
    if course["id"] != record["courseId"] or {x["id"] for x in course.get("exercises", [])} != set(record["exerciseIds"]):
        raise RuntimeError("fixture course or exercise ownership changed")
    exercises = [api(base, "GET", f"/api/text/text-exercises/{eid}") for eid in record["exerciseIds"]]
    unit = api(
        base,
        "GET",
        f"/api/lecture/lectures/{record['lectureId']}/text-units/{record['textUnitId']}",
    )
    if any(x["courseId"] != record["courseId"] for x in exercises) or unit["id"] != record["textUnitId"]:
        raise RuntimeError("learning object ownership changed")

    def links(obj):
        return sorted((link["competency"]["id"], link["weight"]) for link in obj.get("competencyLinks", []))

    mapping = {
        "competencies": sorted((c["id"], digest([c["title"], c.get("description")])) for c in course.get("competencies", [])),
        "exerciseLinks": sorted((x["id"], links(x)) for x in exercises),
        "lectureLinks": links(unit),
    }
    return {
        "courseId": course["id"],
        "exerciseCount": len(exercises),
        "competencyCount": len(mapping["competencies"]),
        "exerciseLinkCount": sum(len(links(x)) for x in exercises),
        "lectureLinkCount": len(links(unit)),
        "mappingSha256": digest(mapping),
        "contentSha256": digest([(x["id"], x.get("problemStatement")) for x in exercises] + [(unit["id"], unit.get("content"))]),
    }


def wait_for_invocation(path: Path, invocation_id: str, timeout: float) -> dict[str, Any] | None:
    terminal = {
        "completed",
        "complete",
        "success",
        "succeeded",
        "partial",
        "failure",
        "failed",
        "error",
        "interrupted",
        "timeout",
    }
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if path.exists():
            for line in path.read_text(encoding="utf-8").splitlines():
                try:
                    event = json.loads(line)
                except json.JSONDecodeError:
                    continue
                if event.get("invocationId") == invocation_id and str(event.get("terminalStatus", "")).lower() in terminal:
                    return event
        time.sleep(0.5)
    return None


def observation(item: dict[str, Any], event_type: str, status: str, **extra: Any) -> dict[str, Any]:
    return {
        "schemaVersion": 1,
        "eventType": event_type,
        "observationId": item["observationId"],
        "condition": item["condition"],
        "repetition": item["repetition"],
        "stage": item["stage"],
        "invocationId": item["invocationId"],
        "status": status,
        "at": now(),
        **extra,
    }


def skip(
    item: dict[str, Any],
    reason: str,
    log_path: Path,
    state: dict[str, Any],
    active_path: Path,
    terminal: dict[str, str],
) -> None:
    if item["observationId"] in terminal:
        raise RuntimeError(f"observation already terminal: {item['observationId']}")
    append(log_path, observation(item, "schedule_skipped", "skipped", skipReason=reason))
    terminal[item["observationId"]] = "skipped"

    state["updatedAt"] = now()
    write(active_path, state)


def prepare(mode: str, offline: bool) -> int:
    campaign = os.environ.get("ATLAS_BENCHMARK_CAMPAIGN_ID", f"atlas-logos-heterogeneous-{mode}-20260913" if os.environ.get("ATLAS_BENCHMARK_FIXTURE") == "heterogeneous" else f"atlas-logos-{mode}-20260910")
    seed, repetitions = (
        int(os.environ.get("ATLAS_BENCHMARK_SEED", "20260910")),
        1 if mode == "smoke" else 10,
    )
    price_file, data = prices(), fixture()
    raw_pricing = price_file.read_bytes() if price_file.exists() else b""
    price_sha = hashlib.sha256(raw_pricing).hexdigest() if raw_pricing else ""
    items, root = schedule(mode, seed, repetitions), root_for(campaign)
    root.mkdir(parents=True, exist_ok=True)
    candidate = manifest(
        mode,
        campaign,
        seed,
        repetitions,
        price_sha,
        items,
        data,
        code_fingerprint(price_file),
    )
    existing = load(root / "manifest.json")
    if (root / "observations.jsonl").exists() and (root / "observations.jsonl").read_text(encoding="utf-8").strip():
        print(
            "READY-STATE BLOCKER: observations already started; prepare refuses replacement",
            file=sys.stderr,
        )
        return 2
    if existing:
        if {key: existing.get(key) for key in candidate if key != "createdAt"} != {
            key: candidate[key] for key in candidate if key != "createdAt"
        }:
            print("READY-STATE BLOCKER: immutable manifest differs", file=sys.stderr)
            return 2
        candidate = existing
    else:
        write(root / "manifest.json", candidate)
    schedule_file = root / "schedule.json"
    if schedule_file.exists():
        stored = load(schedule_file, {})
        if stored.get("manifestSha256") != digest(candidate) or digest(stored.get("schedule", [])) != candidate["scheduleSha256"]:
            print("READY-STATE BLOCKER: immutable schedule differs", file=sys.stderr)
            return 2
    else:
        write(schedule_file, {"manifestSha256": digest(candidate), "schedule": items})
    if not (root / "fixture-definition.json").exists():
        write(root / "fixture-definition.json", data)
    evidence_price = root / "pricing.json"
    if evidence_price.exists() and evidence_price.read_bytes() != raw_pricing:
        print(
            "READY-STATE BLOCKER: evidence pricing differs from canonical pricing",
            file=sys.stderr,
        )
        return 2
    if raw_pricing and not evidence_price.exists():
        evidence_price.write_bytes(raw_pricing)
    if not (root / "active-run.json").exists():
        write(
            root / "active-run.json",
            active(campaign, mode, digest(candidate), price_sha),
        )
    username, password = (
        os.environ.get("ARTEMIS_USERNAME"),
        os.environ.get("ARTEMIS_PASSWORD"),
    )
    blockers = []
    if hetero.enabled(data) and not mixed_content_reviewed(data):
        blockers.append("heterogeneous content review is missing or belongs to a different fixture")
    if not price_sha:
        blockers.append(f"pricing unavailable: {price_file}")
    if offline or not username or not password:
        blockers.append("live fixture CRUD skipped; schedule produced for offline preparation")
        if not (root / "fixtures.json").exists():
            write(root / "fixtures.json", {"fixtureSha256": digest(data), "courses": {}})
    elif not load(root / "fixtures.json", {}).get("courses"):
        if (root / "fixture-setup-started").exists():
            raise RuntimeError("fixture setup was interrupted; inspect its courses before creating another campaign")
        (root / "fixture-setup-started").write_text(now())
        try:
            base = os.environ.get("ATLAS_BENCHMARK_BASE_URL", "http://127.0.0.1:8083")
            api(
                base,
                "POST",
                "/api/core/public/authenticate",
                {"username": username, "password": password, "rememberMe": True},
            )
            api(base, "GET", "/api/core/public/account")
            enable_atlas_features(base)
            write(
                root / "fixtures.json",
                create_fixtures(
                    base,
                    items,
                    int(os.environ.get("ATLAS_BENCHMARK_DEBOUNCE_SECONDS", "5")),
                    campaign,
                ),
            )
        except (OSError, RuntimeError, ValueError, json.JSONDecodeError) as error:
            blockers.append(f"live fixture preparation failed: {error}")
    print(
        json.dumps(
            {
                "campaignId": campaign,
                "mode": mode,
                "scheduleCount": len(items),
                "evidenceDir": str(root),
                "readyStateBlockers": blockers,
            },
            indent=2,
        )
    )
    return 2 if blockers and not offline else 0


def enable_atlas_features(base: str) -> None:
    """Enable the benchmark's global AtlasAgent path while keeping AtlasML disabled."""
    toggles = {"AtlasAgent": True, "AtlasML": False}
    if os.environ.get("ATLAS_BENCHMARK_FIXTURE") == "heterogeneous":
        toggles["ProgrammingExercises"] = True
    enabled_features = api(base, "PUT", "/api/admin/feature-toggle", toggles)
    if not isinstance(enabled_features, list):
        raise RuntimeError("feature-toggle preflight returned no enabled-feature list")
    if "AtlasAgent" not in enabled_features:
        raise RuntimeError("feature-toggle preflight did not enable AtlasAgent")
    if toggles.get("ProgrammingExercises") and "ProgrammingExercises" not in enabled_features:
        raise RuntimeError("feature-toggle preflight did not enable ProgrammingExercises")
    if "AtlasML" in enabled_features:
        raise RuntimeError("feature-toggle preflight did not keep AtlasML disabled")


def verify_models(root):
    base = os.environ.get("LOGOS_BASE_URL", "http://127.0.0.1:18090/v1").rstrip("/")
    key = os.environ.get("LOGOS_API_KEY")
    if not key and base == "http://127.0.0.1:18090/v1":
        local = ROOT / "build/atlas-logos-benchmark/stack.env"
        if local.exists():
            key = next(
                (line.split("=", 1)[1] for line in local.read_text().splitlines() if line.startswith("LOGOS_API_KEY=")),
                None,
            )
    if not key:
        raise RuntimeError("Set LOGOS_API_KEY for your Logos instance")
    request = urllib.request.Request(base + "/models", headers={"Authorization": "Bearer " + key})
    with urllib.request.urlopen(request, timeout=30) as response:
        available = {model["id"] for model in json.load(response)["data"]}
    required = {"gpt-5.6-luna"}
    if not required <= available:
        raise RuntimeError("Logos key cannot access required models: " + ", ".join(sorted(required - available)))
    write(
        root / "model-inventory.json",
        {
            "checkedAt": now(),
            "models": sorted(required),
            "baseUrl": base,
            "inferenceDispatched": False,
        },
    )


def await_local_connectivity(root):
    """Wait before mutating a course when the isolated gateway cannot reach OpenAI."""
    if os.environ.get("ATLAS_BENCHMARK_LOCAL_NETWORK_PROBE") != "1":
        return
    script = (
        "import httpx; "
        "r=httpx.get('https://api.openai.com/v1/models',timeout=4); "
        "raise SystemExit(0 if r.status_code in (200,401) else 1)"
    )
    container = os.environ.get("ATLAS_BENCHMARK_LOGOS_CONTAINER", "atlas-thesis-logos-20260910-logos-orchestrator-1")
    command = ["/usr/local/bin/docker", "exec", container, "python", "-c", script]
    deadline = time.monotonic() + 600
    waiting = False
    while not (root / "STOP").exists():
        try:
            healthy = subprocess.run(command, capture_output=True, timeout=10).returncode == 0
        except (OSError, subprocess.TimeoutExpired):
            healthy = False
        if healthy:
            if waiting:
                print("Gateway connectivity restored; continuing with untouched observation", flush=True)
            return
        if not waiting:
            print("Gateway connectivity unavailable; waiting before any course mutation (maximum ten minutes)", flush=True)
            waiting = True
        if time.monotonic() >= deadline:
            break
        time.sleep(15)
    raise RuntimeError("Gateway connectivity unavailable or stop requested; observation not dispatched")


def run(mode: str) -> int:
    if os.environ.get("ATLAS_BENCHMARK_FIXTURE") == "heterogeneous" and (
        os.environ.get("ATLAS_BENCHMARK_PAID_CONFIRMATION") != "RUN-ATLAS-HETEROGENEOUS"
        or not os.environ.get("ATLAS_BENCHMARK_BUDGET_EUR")
    ):
        print("REFUSED: heterogeneous inference requires explicit paid confirmation and budget", file=sys.stderr)
        return 2
    if os.environ.get("ATLAS_BENCHMARK_FIXTURE") == "heterogeneous" and not mixed_content_reviewed(fixture()):
        print("REFUSED: heterogeneous content review is missing or stale", file=sys.stderr)
        return 2
    if mode == "formal" and os.environ.get("ATLAS_BENCHMARK_FORMAL_CONFIRMATION") != FORMAL_CONFIRMATION:
        print(
            "REFUSED: formal execution requires ATLAS_BENCHMARK_FORMAL_CONFIRMATION=RUN-ATLAS-LOGOS-FORMAL",
            file=sys.stderr,
        )
        return 2
    campaign = os.environ.get("ATLAS_BENCHMARK_CAMPAIGN_ID", f"atlas-logos-heterogeneous-{mode}-20260913" if os.environ.get("ATLAS_BENCHMARK_FIXTURE") == "heterogeneous" else f"atlas-logos-{mode}-20260910")
    root, prepared, fixtures, manifest_data = (
        root_for(campaign),
        load(root_for(campaign) / "schedule.json"),
        load(root_for(campaign) / "fixtures.json"),
        load(root_for(campaign) / "manifest.json"),
    )
    price_file = prices()
    if not prepared or not fixtures or not manifest_data or not fixtures.get("courses"):
        print(
            "READY-STATE BLOCKER: prepare must create fixtures before run",
            file=sys.stderr,
        )
        return 2
    if (
        prepared.get("manifestSha256") != digest(manifest_data)
        or digest(prepared.get("schedule", [])) != manifest_data.get("scheduleSha256")
        or digest(fixture()) != manifest_data.get("fixtureSha256")
        or fixtures.get("fixtureSha256") != manifest_data.get("fixtureSha256")
        or not price_file.exists()
        or hashlib.sha256(price_file.read_bytes()).hexdigest() != manifest_data.get("pricingSha256")
        or code_fingerprint(price_file) != manifest_data.get("codeSha256")
        or git_head() != manifest_data.get("gitHead")
        or os.environ.get("LOGOS_BASE_URL", "http://127.0.0.1:18090/v1") != manifest_data.get("logosHost")
    ):
        print(
            "READY-STATE BLOCKER: frozen manifest, schedule, fixture, or pricing changed",
            file=sys.stderr,
        )
        return 2
    username, password = (
        os.environ.get("ARTEMIS_USERNAME"),
        os.environ.get("ARTEMIS_PASSWORD"),
    )
    if not username or not password:
        print("READY-STATE BLOCKER: Artemis credentials missing", file=sys.stderr)
        return 2
    base = os.environ.get("ATLAS_BENCHMARK_BASE_URL", "http://127.0.0.1:8083")
    try:
        api(
            base,
            "POST",
            "/api/core/public/authenticate",
            {"username": username, "password": password, "rememberMe": True},
        )
        api(base, "GET", "/api/core/public/account")
    except (OSError, RuntimeError, ValueError, json.JSONDecodeError) as error:
        print(f"READY-STATE BLOCKER: authentication failed: {error}", file=sys.stderr)
        return 2
    try:
        verify_models(root)
    except (OSError, RuntimeError, ValueError, json.JSONDecodeError) as error:
        print(f"READY-STATE BLOCKER: model verification failed: {error}", file=sys.stderr)
        return 2
    try:
        enable_atlas_features(base)
    except (OSError, RuntimeError, ValueError, json.JSONDecodeError) as error:
        print(
            f"READY-STATE BLOCKER: Atlas feature preflight failed: {error}",
            file=sys.stderr,
        )
        return 2
    active_path, log_path = root / "active-run.json", root / "observations.jsonl"
    state = load(
        active_path,
        active(campaign, mode, digest(manifest_data), manifest_data["pricingSha256"]),
    )
    if state.get("budgetEur") != manifest_data.get("budgetEur", state.get("budgetEur")):
        raise RuntimeError("Active allowance differs from the frozen campaign budget")
    defaults = active(campaign, mode, digest(manifest_data), manifest_data["pricingSha256"])
    for key, value in defaults.items():
        state.setdefault(key, value)
    state.update({"status": "running", "updatedAt": now()})
    write(active_path, state)
    terminal, started = {}, set()
    for line in log_path.read_text(encoding="utf-8").splitlines() if log_path.exists() else []:
        try:
            record = json.loads(line)
            if record.get("eventType") in {"schedule_terminal", "schedule_skipped"}:
                terminal[record["observationId"]] = record["status"]
            if record.get("eventType") == "schedule_started":
                started.add(record["observationId"])
        except json.JSONDecodeError as error:
            raise RuntimeError("corrupt observation evidence; execution refused") from error
    debounce, timeout = (
        int(os.environ.get("ATLAS_BENCHMARK_DEBOUNCE_SECONDS", "5")),
        float(os.environ.get("ATLAS_BENCHMARK_INVOCATION_TIMEOUT_SECONDS", "1800")),
    )
    print(
        f"Campaign {campaign}: {len(prepared['schedule'])} planned observations, budget EUR {state['budgetEur']:.4f}",
        flush=True,
    )
    evidence_guard = EvidenceGuard(root)
    evidence_guard.checkpoint()
    for position, item in enumerate(prepared["schedule"], 1):
        oid = item["observationId"]
        if oid in terminal:
            continue
        if oid in started:
            append(
                log_path,
                observation(
                    item,
                    "schedule_terminal",
                    "interrupted",
                    error="already started; replay refused",
                ),
            )
            terminal[oid] = "interrupted"

            write(active_path, state)
            (root / "STOP").write_text("an observation was interrupted; no replay or further dispatch\n")
            break
        if (root / "STOP").exists():
            break
        dependency = item.get("requiresObservationId")
        if dependency and terminal.get(dependency) != "completed":
            skip(
                item,
                f"dependency {dependency} did not complete",
                log_path,
                state,
                active_path,
                terminal,
            )
            continue
        record = fixtures["courses"].get(item["courseKey"])
        if not record:
            skip(item, "fixture missing", log_path, state, active_path, terminal)
            continue
        await_local_connectivity(root)
        if (root / "STOP").exists():
            break
        evidence_guard.checkpoint()
        append(log_path, observation(item, "schedule_started", "started"))
        started.add(oid)

        state.update(
            {
                "currentObservationId": oid,
                "currentCondition": item["condition"],
                "currentRepetition": item["repetition"],
                "currentStage": item["stage"],
                "currentCourseId": record["courseId"],
                "currentInvocationId": item["invocationId"],
                "updatedAt": now(),
            }
        )
        write(active_path, state)
        print(
            f"[{position}/{len(prepared['schedule'])}] {oid} START ({item['trigger']})",
            flush=True,
        )
        progress_done = threading.Event()
        progress_thread = threading.Thread(target=show_progress, args=(root, item, progress_done), daemon=True)
        progress_thread.start()
        event = None
        cleanup_error = None
        status, error, before, after, event_started, event_elapsed = (
            "failure",
            None,
            None,
            None,
            None,
            None,
        )
        try:
            before = persisted_state(base, record)
            event_started = time.monotonic()
            condition, indexes = item["condition"], item.get("changedExerciseIndexes", [])
            if "changedObjectKeys" in item:
                event_started = hetero.trigger(mixed_driver(), fixture(), base, record, item, debounce)
            elif condition == "single-exercise":
                index = indexes[0]
                payload = api(
                    base,
                    "GET",
                    f"/api/text/text-exercises/{record['exerciseIds'][index]}",
                )
                payload = {
                    key: payload.get(key, default) for key, default in exercise_payload(fixture(), index, record["courseId"], None).items()
                } | {"id": record["exerciseIds"][index]}
                payload.update(fixture()["exerciseRevision"])
                event_started = time.monotonic()
                api(base, "PUT", "/api/text/text-exercises", payload)
                api(
                    base,
                    "POST",
                    f"/api/atlas/orchestrator/exercises/{record['exerciseIds'][index]}/run",
                )
            elif condition in {"maintained", "assignment-only", "mixed", "bootstrap"}:
                set_automation(base, record, True, 3600)
                for index in indexes:
                    payload = api(
                        base,
                        "GET",
                        f"/api/text/text-exercises/{record['exerciseIds'][index]}",
                    )
                    payload = {
                        key: payload.get(key, default)
                        for key, default in exercise_payload(fixture(), index, record["courseId"], None).items()
                    } | {"id": record["exerciseIds"][index]}
                    payload["problemStatement"] += "\n"
                    if index == indexes[0]:
                        event_started = time.monotonic()
                    api(base, "PUT", "/api/text/text-exercises", payload)
                set_automation(base, record, True, debounce)
                time.sleep(debounce)
            else:
                set_automation(base, record, True, 3600)
                unit = fixture()["lecture"]["textUnit"]
                payload = {
                    "id": record["textUnitId"],
                    "type": "text",
                    "name": unit["name"],
                    "content": fixture()["lectureRevision"],
                    "releaseDate": now(),
                    "competencyLinks": []
                    if not record["competencyIds"]
                    else [
                        {
                            "competency": {"id": record["competencyIds"][0]},
                            "weight": 1.0,
                        }
                    ],
                }
                event_started = time.monotonic()
                api(
                    base,
                    "PUT",
                    f"/api/lecture/lectures/{record['lectureId']}/text-units",
                    payload,
                )
                set_automation(base, record, True, debounce)
            event_started = time.monotonic() if event_started is None else event_started
            event = wait_for_invocation(root / "invocation-events.jsonl", item["invocationId"], timeout)
            event_elapsed = round((time.monotonic() - event_started) * 1000)
            status = event.get("terminalStatus", "failure") if event else "interrupted"
            if event:
                evidence_guard.verify_terminal(event)
            if event and not event.get("usageComplete"):
                print(
                    f"  {oid}: usage incomplete, reservation retained; continuing after this observation",
                    flush=True,
                )
        except KeyboardInterrupt:
            (root / "STOP").write_text("runner interrupted\n")
            status, error = "interrupted", "runner interrupted; replay refused"
        except (OSError, RuntimeError, ValueError, KeyError, IndexError) as failure:
            error = str(failure)
            event = wait_for_invocation(root / "invocation-events.jsonl", item["invocationId"], min(timeout, 30))
            if event:
                status = event.get("terminalStatus", "failure")
        finally:
            print(f"  {oid}: cleaning up course automation", flush=True)
            if record.get("automationEnabled"):
                try:
                    set_automation(base, record, False, debounce)
                except KeyboardInterrupt:
                    (root / "STOP").write_text("operator interrupted cleanup\n")
                    status, error = "interrupted", "operator interrupted cleanup"
                    try:
                        set_automation(base, record, False, debounce)
                    except (OSError, RuntimeError, ValueError) as failure:
                        cleanup_error = str(failure)
                        status, error = (
                            "infrastructure_failure",
                            "failed to disable automation",
                        )
                except (OSError, RuntimeError, ValueError) as failure:
                    cleanup_error = str(failure)
                    status, error = (
                        "infrastructure_failure",
                        "failed to disable automation",
                    )
            progress_done.set()
            progress_thread.join(timeout=1)
        try:
            after = persisted_state(base, record)
        except (OSError, RuntimeError, ValueError) as failure:
            error = error or str(failure)
        if error and status != "interrupted":
            status = "infrastructure_failure"
        append(
            log_path,
            observation(
                item,
                "schedule_terminal",
                status,
                error=error,
                beforeState=before,
                afterState=after,
                eventToCompletionMs=event_elapsed,
                modelStatus=event.get("terminalStatus") if event else None,
                terminalDetails=event.get("details", {}) if event else {},
                cleanupError=cleanup_error,
            ),
        )
        if status in {"interrupted", "infrastructure_failure"}:
            (root / "STOP").write_text("observation interrupted or isolation failed; no further dispatch\n")
        terminal[oid] = status
        if status not in {"interrupted", "infrastructure_failure"}:
            evidence_guard.checkpoint()
        print(
            f"[{position}/{len(prepared['schedule'])}] {oid} {status.upper()}" + (f" | {error}" if error else ""),
            flush=True,
        )

        state.update(
            {
                "currentObservationId": None,
                "currentCondition": None,
                "currentRepetition": None,
                "currentStage": None,
                "currentCourseId": None,
                "currentInvocationId": None,
                "updatedAt": now(),
            }
        )
        write(active_path, state)
    state["status"] = "stopped" if (root / "STOP").exists() else "ready"
    if (root / "STOP").exists():
        state["stopReason"] = (root / "STOP").read_text().strip()
    write(active_path, state)
    print(
        f"Campaign {state['status']}: {len(terminal)}/{len(prepared['schedule'])} terminal observations. "
        f"{state.get('stopReason') or 'Export Logos records and run report for measured results.'}",
        flush=True,
    )
    return 2 if state["status"] == "stopped" else 0


def stop(mode: str, reason: str) -> int:
    campaign = os.environ.get("ATLAS_BENCHMARK_CAMPAIGN_ID", f"atlas-logos-heterogeneous-{mode}-20260913" if os.environ.get("ATLAS_BENCHMARK_FIXTURE") == "heterogeneous" else f"atlas-logos-{mode}-20260910")
    root = root_for(campaign)
    root.mkdir(parents=True, exist_ok=True)
    (root / "STOP").write_text(reason + "\n", encoding="utf-8")
    manifest_data = load(root / "manifest.json", {})
    state = load(
        root / "active-run.json",
        active(
            campaign,
            mode,
            digest(manifest_data),
            manifest_data.get("pricingSha256", ""),
        ),
    )
    defaults = active(campaign, mode, digest(manifest_data), manifest_data.get("pricingSha256", ""))
    for key, value in defaults.items():
        state.setdefault(key, value)
    state.update(
        {
            "status": "stop-requested",
            "stopRequestedAt": now(),
            "stopReason": reason,
            "updatedAt": now(),
        }
    )
    write(root / "active-run.json", state)
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("prepare", "smoke", "run", "report", "stop"))
    parser.add_argument("--offline", action="store_true")
    parser.add_argument("--mode", choices=("smoke", "formal"))
    parser.add_argument("--reason", default="requested by operator")
    args = parser.parse_args(argv)
    if args.command == "prepare":
        return prepare(args.mode or "formal", args.offline)
    if args.command in {"smoke", "run"}:
        previous_handler = signal.getsignal(signal.SIGINT)
        interrupted = False

        def interrupt_once(signum, frame):
            nonlocal interrupted
            if interrupted:
                print(
                    "Cleanup in progress. Please wait for the bounded API request to finish.",
                    flush=True,
                )
                return
            interrupted = True
            print(
                "Stop requested. Finishing evidence and disabling course automation...",
                flush=True,
            )
            raise KeyboardInterrupt

        signal.signal(signal.SIGINT, interrupt_once)
        try:
            return run("smoke" if args.command == "smoke" else "formal")
        except KeyboardInterrupt:
            stop(
                "smoke" if args.command == "smoke" else "formal",
                "operator interrupted runner",
            )
            print(
                "Stopped. Preserve evidence and inspect the active course before resuming.",
                flush=True,
            )
            return 130
        finally:
            signal.signal(signal.SIGINT, previous_handler)
    if args.command == "stop":
        return stop(args.mode or os.environ.get("ATLAS_BENCHMARK_MODE", "formal"), args.reason)
    from report import render

    return render(args.mode or os.environ.get("ATLAS_BENCHMARK_MODE", "formal"))


if __name__ == "__main__":
    raise SystemExit(main())
