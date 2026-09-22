"""Ordinary Artemis HTTP API adapter for the unchanged heterogeneous fixture."""
import hashlib
import json
import os
import time
import http.cookiejar
import urllib.request
import urllib.error
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any

import heterogeneous as h
from meter import now

COOKIE_JAR = http.cookiejar.CookieJar()
HTTP = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(COOKIE_JAR))
LAST_RESULT = None
JOURNAL = None

def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(",", ":")).encode()).hexdigest()

def fixture():
    return h.validate(json.loads(Path(__file__).with_name("fixture-heterogeneous.json").read_text()))

def api(base: str, method: str, path: str, payload = None, multipart: bool | str = False):
    global LAST_RESULT
    headers = {"Accept": "application/json"}
    if JOURNAL is not None and method != "GET":
        from meter import append
        append(JOURNAL, {"event": "write-started", "at": now(), "method": method, "path": path})
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
            result = json.loads(raw) if raw else None
            if path.endswith("/run"):
                LAST_RESULT = result
            if JOURNAL is not None and method != "GET":
                from meter import append
                append(JOURNAL, {"event": "write-finished", "at": now(), "method": method, "path": path, "response": result})
            return result
        except (OSError, urllib.error.URLError) as error:
            if isinstance(error, urllib.error.HTTPError) and path.endswith('/run'):
                raw_error = error.read()
                try:
                    result = json.loads(raw_error)
                except (ValueError, TypeError):
                    result = None
                if isinstance(result, dict) and result.get('status') in {'SUCCESS', 'PARTIAL', 'FAILED', 'NO_OP', 'IN_PROGRESS'}:
                    LAST_RESULT = result
                    if JOURNAL is not None:
                        from meter import append
                        append(JOURNAL, {'event': 'write-finished', 'at': now(), 'method': method, 'path': path,
                                         'httpStatus': error.code, 'response': result})
                    return result
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

def entity_id(value) -> int | str:
    if isinstance(value, dict):
        if value.get("id") is not None:
            return value["id"]
        for key in ("course", "exercise", "competency", "lecture", "textUnit"):
            if isinstance(value.get(key), dict) and value[key].get("id") is not None:
                return value[key]["id"]
    raise RuntimeError("normal API response contained no entity id")

def set_automation(base: str, record: dict[str, Any], enabled: bool, debounce: int) -> None:
    if enabled:
        record["automationEnabled"] = True  # An uncertain enable must still be cleaned up.
    path = f"/api/course/courses/{record['courseId']}"
    last_error = None
    for attempt in range(3):
        current = api(base, "GET", path)
        if current.get("courseConfiguration") is None:
            raise RuntimeError("Course configuration was not loaded; automation state is unknown")
        if current.get("courseConfiguration") is not None and current["courseConfiguration"].get("autoOrchestratorEnabled") == enabled and current["courseConfiguration"].get("maxDailyOrchestrationOverride") == record.get("dailyCap", 1) and (not enabled or current["courseConfiguration"].get("debounceWindowSecondsOverride") == debounce):
            record["automationEnabled"] = enabled
            return
        update = dict(current)
        update.update(
            {
                "id": record["courseId"],
                "autoOrchestratorEnabled": enabled,
                "debounceWindowSecondsOverride": debounce,
                "maxDailyOrchestrationOverride": record.get("dailyCap", 1),
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
    if current.get("courseConfiguration") is not None and current["courseConfiguration"].get("autoOrchestratorEnabled") == enabled and current["courseConfiguration"].get("maxDailyOrchestrationOverride") == record.get("dailyCap", 1) and (not enabled or current["courseConfiguration"].get("debounceWindowSecondsOverride") == debounce):
        record["automationEnabled"] = enabled
        return
    raise RuntimeError(f"automation readback failed for course {record['courseId']}: {last_error or 'setting not persisted'}")


def login(base):
    api(base, "POST", "/api/core/public/authenticate", {
        "username": os.environ.get("ARTEMIS_USERNAME", "artemis_admin"),
        "password": os.environ.get("ARTEMIS_PASSWORD", "artemis_admin"), "rememberMe": True})
    api(base, "GET", "/api/core/public/account")


def enable_features(base):
    enabled = api(base, "PUT", "/api/admin/feature-toggle", {
        "AtlasAgent": True, "AtlasML": False, "ProgrammingExercises": True})
    if not isinstance(enabled, list) or "AtlasAgent" not in enabled or "ProgrammingExercises" not in enabled or "AtlasML" in enabled:
        raise RuntimeError("Feature gate verification failed")
