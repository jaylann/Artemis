#!/usr/bin/env python3
"""Seed one reviewed course through ordinary APIs, without invoking Atlas."""

import argparse
from copy import deepcopy
import json
import os
from pathlib import Path
from types import SimpleNamespace
from urllib.parse import urlparse

import heterogeneous as h
import run


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:8083")
    parser.add_argument("--state-dir", type=Path, required=True)
    parser.add_argument(
        "--refresh-content",
        action="store_true",
        help="Apply a newly reviewed fixture to the existing exploratory course",
    )
    args = parser.parse_args()
    if urlparse(args.base_url).hostname not in {"localhost", "127.0.0.1"}:
        raise RuntimeError(
            "This seed command is restricted to the isolated local server"
        )
    os.environ["ATLAS_BENCHMARK_FIXTURE"] = "heterogeneous"
    data = run.fixture()
    if not run.mixed_content_reviewed(data):
        raise RuntimeError("The content review is missing or stale")
    args.state_dir.mkdir(parents=True, exist_ok=True)
    state_file = args.state_dir / "seed-state.json"
    state = run.load(
        state_file,
        {
            "fixtureSha256": run.digest(data),
            "baseUrl": args.base_url,
            "createdAt": run.now(),
            "created": {},
            "pending": None,
        },
    )
    if state["baseUrl"] != args.base_url:
        raise RuntimeError("Existing seed state belongs to different content or server")
    if state["fixtureSha256"] != run.digest(data) and not (
        args.refresh_content and state.get("result")
    ):
        raise RuntimeError(
            "Changed content requires --refresh-content and an already seeded course"
        )
    if state["pending"]:
        raise RuntimeError(
            "An earlier write is unresolved; inspect its response and course before resuming"
        )

    def save():
        temp = state_file.with_suffix(".tmp")
        run.write(temp, state)
        temp.replace(state_file)

    def api(base, method, path, body=None, multipart=False):
        if method != "POST":
            return run.api(base, method, path, body, multipart)
        stable = deepcopy(body)
        for key in ("startDate", "endDate", "releaseDate"):
            stable.pop(key, None)
        key = run.digest([method, path, stable])
        if key in state["created"]:
            return state["created"][key]["response"]
        state["pending"] = {"key": key, "path": path, "payload": body, "at": run.now()}
        save()
        try:
            response = run.api(base, method, path, body, multipart)
        except RuntimeError as error:
            cause = error.__cause__
            state["pending"]["error"] = str(error)
            if hasattr(cause, "read"):
                state["pending"]["responseBody"] = cause.read().decode(errors="replace")
            save()
            raise
        state["created"][key] = state["pending"] | {"response": response}
        state["pending"] = None
        save()
        print(f"Created {path}: {run.entity_id(response)}", flush=True)
        return response

    run.api(
        args.base_url,
        "POST",
        "/api/core/public/authenticate",
        {
            "username": os.environ.get("ARTEMIS_USERNAME", "artemis_admin"),
            "password": os.environ.get("ARTEMIS_PASSWORD", "artemis_admin"),
            "rememberMe": True,
        },
    )
    run.api(args.base_url, "GET", "/api/core/public/account")
    run.enable_atlas_features(args.base_url)

    def course_payload(key, enabled, debounce, campaign):
        return run.course_payload(key, False, debounce, campaign) | {
            "title": "Introduction to Computer Science — Heterogeneous Coursework",
            "description": data["authorship"],
        }

    driver = SimpleNamespace(
        api=api,
        now=lambda: state["createdAt"],
        digest=run.digest,
        entity_id=run.entity_id,
        course_payload=course_payload,
        set_automation=run.set_automation,
    )
    result = state.get("result") or h.create_fixtures(
        driver,
        data,
        args.base_url,
        [{"courseKey": "coursework", "condition": "maintained"}],
        5,
        "atlas-heterogeneous-seed-20260913",
    )
    record = result["courses"]["coursework"]
    run.set_automation(args.base_url, record, False, 5)
    if args.refresh_content:
        archive = args.state_dir / ("seed-before-" + state["fixtureSha256"] + ".json")
        if not archive.exists():
            run.write(archive, state)
        for obj in h.objects(data):
            current = h.read_object(
                driver, args.base_url, obj, record["objects"][obj["id"]]
            )
            expected = h.content(h.payload(driver, obj, record["courseId"]))
            differs = any(
                h.quiz_projection(current.get(key, [])) != h.quiz_projection(value)
                if key == "quizQuestions"
                else current.get(key) != value
                for key, value in expected.items()
            )
            if not differs:
                continue
            state["refreshPending"] = obj["id"]
            save()
            try:
                h.update_object(
                    driver,
                    args.base_url,
                    obj | {"semanticUpdate": expected},
                    record["objects"][obj["id"]],
                    True,
                )
            except RuntimeError as error:
                state.setdefault("refreshFailures", []).append(
                    {
                        "object": obj["id"],
                        "error": str(error),
                        "at": run.now(),
                        "response": error.__cause__.read().decode(errors="replace")
                        if hasattr(error.__cause__, "read")
                        else None,
                    }
                )
                save()
                raise
            state["refreshPending"] = None
            save()
            print(f"Refreshed {obj['id']}", flush=True)
    expected_links = {
        obj["id"]: record["competencyIds"][obj["topicIndex"]] for obj in h.objects(data)
    }
    h.verify_content(driver, data, args.base_url, record, expected_links)
    record["initialState"] = h.persisted_state(driver, data, args.base_url, record)
    state["fixtureSha256"] = result["fixtureSha256"] = run.digest(data)
    state["result"] = result
    state["verifiedAt"] = run.now()
    state["status"] = "seeded-and-readback-verified"
    save()
    print(
        json.dumps(
            {
                "status": state["status"],
                "courseId": record["courseId"],
                "state": record["initialState"],
                "url": args.base_url + f"/course-management/{record['courseId']}",
            },
            indent=2,
        )
    )


if __name__ == "__main__":
    main()
