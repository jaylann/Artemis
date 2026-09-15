"""Offline tests exercise experiment invariants and failure accounting."""

import contextlib
import hashlib
import importlib.util
import io
import json
import os
import tempfile
import unittest
from decimal import Decimal
from pathlib import Path
from unittest.mock import Mock, patch

HERE = Path(__file__).parent


def module(name):
    spec = importlib.util.spec_from_file_location(name, HERE / f"{name}.py")
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


run, report = module("run"), module("report")


class ExperimentTests(unittest.TestCase):
    def test_full_rehearsal_covers_every_condition_and_both_continuations(self):
        with patch.dict(os.environ, {"ATLAS_BENCHMARK_REHEARSAL": "1"}):
            schedule = run.schedule("smoke", 42, 10)
        self.assertEqual(len(schedule), 8)
        self.assertEqual({x["condition"] for x in schedule}, set(run.CONDITIONS))
        self.assertEqual([x["stage"] for x in schedule if x["condition"] == "bootstrap"], [1, 2, 3])

    def test_cleanup_timeout_is_resolved_by_readback_without_duplicate_write(self):
        record = {"courseId": 1, "automationEnabled": True}
        requests = []
        enabled = True

        def api(base, method, path, payload=None, multipart=False):
            nonlocal enabled
            requests.append(method)
            if method == "GET":
                self.assertEqual(path, "/api/course/courses/1")
                return {"id": 1, "courseConfiguration": {"id": 1}, "autoOrchestratorEnabled": enabled}
            enabled = False
            raise RuntimeError("PUT response timed out after persistence")

        with patch.object(run, "api", side_effect=api), patch.object(run.time, "sleep"):
            run.set_automation("local", record, False, 5)
        self.assertFalse(record["automationEnabled"])
        self.assertEqual(requests.count("PUT"), 1)

    def test_unloaded_course_configuration_cannot_confirm_cleanup(self):
        record = {"courseId": 1, "automationEnabled": True}
        with patch.object(run, "api", return_value={"autoOrchestratorEnabled": False}) as api:
            with self.assertRaisesRegex(RuntimeError, "configuration was not loaded"):
                run.set_automation("local", record, False, 5)
        self.assertTrue(record["automationEnabled"])
        self.assertEqual(api.call_count, 1)

    def test_uncertain_enable_retains_cleanup_intent(self):
        record = {"courseId": 1, "automationEnabled": False}
        with patch.object(run, "api", side_effect=RuntimeError("unavailable")):
            with self.assertRaises(RuntimeError):
                run.set_automation("local", record, True, 5)
        self.assertTrue(record["automationEnabled"])

    def test_get_retries_transport_failure_but_trigger_never_replays(self):
        import urllib.error

        with (
            patch.object(run.HTTP, "open", side_effect=urllib.error.URLError("timeout")) as request,
            patch.object(run.time, "sleep"),
        ):
            with self.assertRaises(RuntimeError):
                run.api("http://local", "GET", "/course")
            self.assertEqual(request.call_count, 3)
            request.reset_mock()
            with self.assertRaises(RuntimeError):
                run.api("http://local", "POST", "/run")
            self.assertEqual(request.call_count, 1)

    def test_frozen_design_has_all_primary_and_continuation_inputs(self):
        rows = run.schedule("formal", 17, 10)
        self.assertEqual(len(rows), 80)
        self.assertEqual(sum(r["stage"] == 1 for r in rows), 60)
        self.assertEqual(len({r["invocationId"] for r in rows}), 80)
        for repetition in range(1, 11):
            bootstrap = [r for r in rows if r["condition"] == "bootstrap" and r["repetition"] == repetition]
            self.assertEqual([len(r["changedExerciseIndexes"]) for r in bootstrap], [12, 24, 24])
            self.assertEqual(
                sum((r["changedExerciseIndexes"] for r in bootstrap), []),
                list(range(60)),
            )
            self.assertEqual(bootstrap[2]["requiresObservationId"], bootstrap[1]["observationId"])
        self.assertEqual(rows, run.schedule("formal", 17, 10))
        self.assertNotEqual(rows, run.schedule("formal", 18, 10))
        self.assertEqual(
            {r["trigger"] for r in run.schedule("smoke", 17, 1)},
            {"manual", "automatic"},
        )

    def test_input_links_are_distinct_from_expected_model_outputs(self):
        anchors = set(range(0, 60, 5))
        self.assertEqual(run.link_indexes("maintained"), set(range(60)))
        for condition, count in (("assignment-only", 48), ("mixed", 24)):
            self.assertEqual(len(run.link_indexes(condition)), count)
            self.assertFalse(run.link_indexes(condition) & anchors)
            self.assertEqual(set(run.plans()[condition]["changed"]), anchors)
        data = run.fixture()
        self.assertEqual(sum(len(t["exercises"]) for t in data["topics"]), 60)
        self.assertIn("breadth-first", data["exerciseRevision"]["problemStatement"])
        self.assertRegex(
            run.course_payload("r01-assignment-only", False, 5)["shortName"],
            r"^[A-Za-z][A-Za-z0-9]{2,23}$",
        )

    def test_prepare_freezes_prices_and_never_calls_an_api(self):
        with (
            tempfile.TemporaryDirectory() as tmp,
            patch.dict(os.environ, {"ATLAS_BENCHMARK_EVIDENCE_DIR": tmp}),
            patch.object(run, "api") as api,
            contextlib.redirect_stdout(io.StringIO()),
        ):
            self.assertEqual(run.prepare("formal", True), 0)
            root = Path(tmp)
            self.assertEqual(run.load(root / "active-run.json")["budgetEur"], 20)
            self.assertEqual(run.active("smoke", "smoke", "manifest", "price")["budgetEur"], 1)
            self.assertEqual(run.load(root / "manifest.json")["toolBudget"], {"sharedCallbacks": 256, "wrapUpAt": 224, "finalSlotForCompletion": True})
            first = (root / "manifest.json").read_bytes()
            self.assertEqual(run.prepare("formal", True), 0)
            self.assertEqual(first, (root / "manifest.json").read_bytes())
            self.assertEqual((root / "pricing.json").read_bytes(), run.prices().read_bytes())
            api.assert_not_called()
            output = report.build_report(root)
            self.assertEqual(output["criterion"], "incomplete")
            self.assertEqual(len(output["observations"]), 80)
            self.assertTrue(all(r["costEur"] is None for r in output["observations"]))

    def test_started_observation_is_never_replayed(self):
        with (
            tempfile.TemporaryDirectory() as tmp,
            patch.dict(
                os.environ,
                {
                    "ATLAS_BENCHMARK_EVIDENCE_DIR": tmp,
                    "ARTEMIS_USERNAME": "local",
                    "ARTEMIS_PASSWORD": "local",
                },
            ),
            contextlib.redirect_stdout(io.StringIO()),
        ):
            run.prepare("smoke", True)
            root = Path(tmp)
            item = run.schedule("smoke", 1, 1)[0]
            run.write(
                root / "fixtures.json",
                {
                    "fixtureSha256": run.digest(run.fixture()),
                    "courses": {item["courseKey"]: {"courseId": 7}},
                },
            )
            run.append(
                root / "observations.jsonl",
                run.observation(item, "schedule_started", "started"),
            )
            with (
                patch.object(run, "api", return_value={}) as api,
                patch.object(run, "verify_models"),
                patch.object(run, "enable_atlas_features"),
            ):
                run.run("smoke")
            self.assertFalse(any("/run" in call.args[2] for call in api.call_args_list))
            self.assertTrue((root / "STOP").exists())
            rows = report.records(root / "observations.jsonl")
            self.assertEqual([r["status"] for r in rows], ["started", "interrupted"])

    def test_run_enables_atlas_before_first_course_update_after_restart(self):
        with (
            tempfile.TemporaryDirectory() as tmp,
            patch.dict(
                os.environ,
                {
                    "ATLAS_BENCHMARK_EVIDENCE_DIR": tmp,
                    "ARTEMIS_USERNAME": "local",
                    "ARTEMIS_PASSWORD": "local",
                },
            ),
            contextlib.redirect_stdout(io.StringIO()),
        ):
            run.prepare("smoke", True)
            root = Path(tmp)
            items = run.load(root / "schedule.json")["schedule"]
            courses = {
                item["courseKey"]: {
                    "courseId": index + 1,
                    "exerciseIds": list(range(60)),
                    "competencyIds": [],
                    "lectureId": 1,
                    "textUnitId": 1,
                }
                for index, item in enumerate(items)
            }
            run.write(
                root / "fixtures.json",
                {"fixtureSha256": run.digest(run.fixture()), "courses": courses},
            )
            calls = []

            def api(base, method, path, payload=None, multipart=False):
                calls.append((method, path, payload))
                if path == "/api/admin/feature-toggle":
                    self.assertEqual(payload, {"AtlasAgent": True, "AtlasML": False})
                    return ["AtlasAgent"]
                if method == "PUT" and path.startswith("/api/course/courses/"):
                    automation[path] = payload
                if method == "GET" and path.startswith("/api/course/courses/"):
                    return automation.get(
                        path,
                        {"autoOrchestratorEnabled": False, "courseConfiguration": {"id": 1}},
                    )
                return {}

            automation = {}
            with (
                patch.object(run.time, "sleep"),
                patch.object(run, "api", side_effect=api),
                patch.object(run, "verify_models"),
                patch.object(run, "persisted_state", return_value={}),
                patch.object(
                    run,
                    "wait_for_invocation",
                    return_value={"terminalStatus": "completed", "usageComplete": True},
                ),
            ):
                self.assertEqual(run.run("smoke"), 0)

            toggle_index = next(index for index, (_, path, _) in enumerate(calls) if path == "/api/admin/feature-toggle")
            first_mutation = next(
                index
                for index, (method, path, _) in enumerate(calls)
                if method in {"POST", "PUT"} and path not in {"/api/core/public/authenticate", "/api/admin/feature-toggle"}
            )
            self.assertLess(toggle_index, first_mutation)

    def test_rejected_atlas_toggle_starts_no_observation(self):
        with (
            tempfile.TemporaryDirectory() as tmp,
            patch.dict(
                os.environ,
                {
                    "ATLAS_BENCHMARK_EVIDENCE_DIR": tmp,
                    "ARTEMIS_USERNAME": "local",
                    "ARTEMIS_PASSWORD": "local",
                },
            ),
            contextlib.redirect_stdout(io.StringIO()),
            contextlib.redirect_stderr(io.StringIO()) as error_output,
        ):
            run.prepare("smoke", True)
            root = Path(tmp)
            items = run.load(root / "schedule.json")["schedule"]
            courses = {
                item["courseKey"]: {
                    "courseId": index + 1,
                    "exerciseIds": list(range(60)),
                    "competencyIds": [],
                    "lectureId": 1,
                    "textUnitId": 1,
                }
                for index, item in enumerate(items)
            }
            run.write(
                root / "fixtures.json",
                {"fixtureSha256": run.digest(run.fixture()), "courses": courses},
            )
            with (
                patch.object(run, "api", return_value={}),
                patch.object(run, "verify_models"),
            ):
                self.assertEqual(run.run("smoke"), 2)
            self.assertFalse((root / "observations.jsonl").exists())
            self.assertIn(
                "READY-STATE BLOCKER: Atlas feature preflight failed",
                error_output.getvalue(),
            )

    def test_progress_reports_retries_and_unknown_usage_without_changing_evidence(self):
        with (
            tempfile.TemporaryDirectory() as tmp,
            contextlib.redirect_stdout(io.StringIO()) as output,
        ):
            root = Path(tmp)
            run.append(
                root / "provider-events.jsonl",
                {
                    "invocationId": "inv",
                    "phase": "flavor_strip",
                    "usageAvailable": False,
                    "sdkRetryCount": 0,
                },
            )
            run.append(
                root / "provider-events.jsonl",
                {
                    "invocationId": "inv",
                    "phase": "flavor_strip",
                    "usageAvailable": True,
                    "sdkRetryCount": 1,
                    "costEur": 0.02,
                },
            )
            before = (root / "provider-events.jsonl").read_bytes()
            done = Mock()
            done.wait.side_effect = [False, True]
            run.show_progress(root, {"invocationId": "inv", "observationId": "obs"}, done)
            self.assertIn("HTTP=2 retries=1", output.getvalue())
            self.assertIn("unknown=1", output.getvalue())
            self.assertEqual(before, (root / "provider-events.jsonl").read_bytes())

    def test_budget_carry_forward_is_frozen_and_cannot_raise_ceiling(self):
        with patch.dict(os.environ, {"ATLAS_BENCHMARK_BUDGET_EUR": "19.2"}):
            self.assertEqual(run.active("campaign", "formal", "hash", "price")["budgetEur"], 19.2)
            with self.assertRaises(ValueError):
                run.campaign_budget("smoke")
        for invalid in ("nan", "inf", "21", "0", "-1"):
            with (
                patch.dict(os.environ, {"ATLAS_BENCHMARK_BUDGET_EUR": invalid}),
                self.assertRaises(ValueError),
            ):
                run.campaign_budget("formal")

    def test_incomplete_usage_does_not_cancel_next_observation(self):
        with (
            tempfile.TemporaryDirectory() as tmp,
            patch.dict(
                os.environ,
                {
                    "ATLAS_BENCHMARK_EVIDENCE_DIR": tmp,
                    "ARTEMIS_USERNAME": "local",
                    "ARTEMIS_PASSWORD": "local",
                },
            ),
            contextlib.redirect_stdout(io.StringIO()) as output,
        ):
            run.prepare("smoke", True)
            root = Path(tmp)
            items = run.load(root / "schedule.json")["schedule"]
            courses = {
                item["courseKey"]: {
                    "courseId": index + 1,
                    "exerciseIds": list(range(60)),
                    "competencyIds": [],
                    "lectureId": 1,
                    "textUnitId": 1,
                }
                for index, item in enumerate(items)
            }
            run.write(
                root / "fixtures.json",
                {"fixtureSha256": run.digest(run.fixture()), "courses": courses},
            )
            with (
                patch.object(run, "api", return_value={}),
                patch.object(run, "verify_models"),
                patch.object(run, "enable_atlas_features"),
                patch.object(run, "set_automation"),
                patch.object(run, "persisted_state", return_value={}),
                patch.object(
                    run,
                    "wait_for_invocation",
                    return_value={
                        "terminalStatus": "completed",
                        "usageComplete": False,
                    },
                ) as wait,
            ):
                self.assertEqual(run.run("smoke"), 0)
            self.assertEqual(wait.call_count, 2)
            self.assertFalse((root / "STOP").exists())
            self.assertIn("usage incomplete", output.getvalue())
            self.assertIn("2/2 terminal observations", output.getvalue())

    def test_first_interrupt_during_cleanup_retries_only_course_disable(self):
        with (
            tempfile.TemporaryDirectory() as tmp,
            patch.dict(
                os.environ,
                {
                    "ATLAS_BENCHMARK_EVIDENCE_DIR": tmp,
                    "ARTEMIS_USERNAME": "local",
                    "ARTEMIS_PASSWORD": "local",
                },
            ),
            contextlib.redirect_stdout(io.StringIO()),
        ):
            run.prepare("smoke", True)
            root = Path(tmp)
            items = run.load(root / "schedule.json")["schedule"]
            courses = {
                item["courseKey"]: {
                    "courseId": index + 1,
                    "exerciseIds": list(range(60)),
                    "competencyIds": [],
                    "lectureId": 1,
                    "textUnitId": 1,
                }
                for index, item in enumerate(items)
            }
            run.write(
                root / "fixtures.json",
                {"fixtureSha256": run.digest(run.fixture()), "courses": courses},
            )
            disables = []

            def automation(base, record, enabled, debounce):
                if not enabled:
                    disables.append(record["courseId"])
                    if len(disables) == 1:
                        raise KeyboardInterrupt
                record["automationEnabled"] = enabled

            with (
                patch.object(run, "api", return_value={}),
                patch.object(run, "verify_models"),
                patch.object(run, "enable_atlas_features"),
                patch.object(run, "set_automation", side_effect=automation),
                patch.object(run, "persisted_state", return_value={}),
                patch.object(
                    run,
                    "wait_for_invocation",
                    return_value={"terminalStatus": "completed", "usageComplete": True},
                ) as wait,
            ):
                self.assertEqual(run.run("smoke"), 2)
            self.assertEqual(len(disables), 2)
            self.assertEqual(wait.call_count, 2)
            terminal = [row for row in report.records(root / "observations.jsonl") if row["eventType"] == "schedule_terminal"]
            self.assertEqual(terminal[-1]["status"], "interrupted")
            self.assertTrue((root / "STOP").exists())

    def test_malformed_evidence_cannot_be_silently_ignored(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "evidence.jsonl"
            path.write_text('{"id":1}\n{broken\n')
            with self.assertRaises(ValueError):
                report.records(path)


class CostTests(unittest.TestCase):
    def setUp(self):
        self.pricing = {
            "models": {
                "model": {
                    "inputEurPerMillion": "1",
                    "cachedInputEurPerMillion": "0.1",
                    "outputEurPerMillion": "2",
                    "maxSupportedInputTokens": 272000,
                    "maxOutputTokens": 128000,
                }
            }
        }
        self.event = {
            "eventId": "a",
            "invocationId": "inv",
            "logosRequestId": "req",
            "requestedModel": "model",
            "returnedModel": "model",
            "usageAvailable": True,
            "inputTokens": 1000,
            "cachedInputTokens": 200,
            "outputTokens": 100,
            "reasoningTokens": 50,
            "httpStatus": 200,
            "phase": "worker",
        }
        self.exports = {
            "req": {
                "requestId": "req",
                "model": "model",
                "status": "success",
                "usage": {
                    "prompt_tokens": 1000,
                    "prompt_cached_tokens": 200,
                    "completion_tokens": 100,
                },
            }
        }

    def test_cached_input_is_subset_and_reasoning_is_not_added_twice(self):
        self.assertEqual(
            report.attempt_cost(self.event, self.pricing, self.exports),
            (Decimal("0.00102"), "matched"),
        )
        self.event["cachedInputTokens"] = 1001
        self.assertIsNone(report.attempt_cost(self.event, self.pricing, self.exports)[0])

    def test_cache_write_cost_matches_four_token_buckets(self):
        pricing = {"models": {"model": self.pricing["models"]["model"] | {"cacheWriteInputEurPerMillion": "1.25"}}}
        event = self.event | {"cacheWriteInputTokens": 300}
        exports = {"req": self.exports["req"] | {"usage": self.exports["req"]["usage"] | {"prompt_cache_write_tokens": 300}}}
        self.assertEqual(
            report.attempt_cost(event, pricing, exports),
            (Decimal("0.001095"), "matched"),
        )

    def test_cache_write_requires_rate_and_valid_counts(self):
        event = self.event | {"cacheWriteInputTokens": 1}
        self.assertIsNone(report.attempt_cost(event, self.pricing, self.exports)[0])
        pricing = {"models": {"model": self.pricing["models"]["model"] | {"cacheWriteInputEurPerMillion": "1.25"}}}
        self.assertIsNone(report.attempt_cost(self.event, pricing, self.exports)[0])
        self.assertIsNone(report.attempt_cost(event | {"cacheWriteInputTokens": 801}, pricing, self.exports)[0])

    def test_missing_usage_or_logos_and_token_mismatch_are_unknown(self):
        self.assertIsNone(report.attempt_cost(self.event, self.pricing, {})[0])
        self.exports["req"]["usage"]["completion_tokens"] = 99
        self.assertIsNone(report.attempt_cost(self.event, self.pricing, self.exports)[0])
        self.event["usageAvailable"] = False
        self.assertIsNone(report.attempt_cost(self.event, self.pricing, self.exports)[0])

    def test_even_sample_median_uses_both_middle_values(self):
        self.assertEqual(report.stats([{"costEur": "1"}, {"costEur": "3"}])["medianEur"], "2")

    def test_retries_and_unfinished_dispatch_are_preserved(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            schedule = [
                {
                    "observationId": "obs",
                    "invocationId": "inv",
                    "condition": "mixed",
                    "stage": 1,
                    "courseKey": "course",
                }
            ]
            raw = json.dumps(self.pricing).encode()
            (root / "pricing.json").write_bytes(raw)
            run.write(
                root / "manifest.json",
                {
                    "campaignId": "test",
                    "mode": "formal",
                    "pricingSha256": hashlib.sha256(raw).hexdigest(),
                    "scheduleSha256": run.digest(schedule),
                },
            )
            run.write(root / "schedule.json", {"schedule": schedule})
            for index in (1, 2):
                run.append(
                    root / "provider-events.jsonl",
                    self.event
                    | {
                        "eventId": str(index),
                        "logosRequestId": f"req{index}",
                        "sdkRetryCount": index - 1,
                    },
                )
                run.append(
                    root / "logos.jsonl",
                    self.exports["req"] | {"requestId": f"req{index}"},
                )
            run.append(
                root / "invocation-events.jsonl",
                {
                    "observationId": "obs",
                    "invocationId": "inv",
                    "terminalStatus": "failure",
                    "usageComplete": True,
                },
            )
            run.append(
                root / "observations.jsonl",
                {"observationId": "obs", "status": "failure"},
            )
            output = report.build_report(root)
            self.assertEqual(output["observations"][0]["costEur"], "0.00204")
            self.assertEqual(output["observations"][0]["retryAttemptCount"], 1)
            self.assertEqual(output["observations"][0]["knownCostEur"], "0.00204")
            self.assertEqual(output["conditions"]["mixed"]["completed"]["count"], 0)
            self.assertEqual(output["conditions"]["mixed"]["failures"]["count"], 1)
            terminal_path = root / "invocation-events.jsonl"
            original_terminal = terminal_path.read_bytes()
            terminal_path.write_text(
                json.dumps(
                    {
                        "observationId": "obs",
                        "invocationId": "other",
                        "usageComplete": True,
                    }
                )
                + "\n"
            )
            self.assertIsNone(report.build_report(root)["observations"][0]["costEur"])
            terminal_path.write_bytes(original_terminal)
            provider_path = root / "provider-events.jsonl"
            original_provider = provider_path.read_bytes()
            provider_path.write_text("")
            self.assertIsNone(report.build_report(root)["observations"][0]["costEur"])
            provider_path.write_bytes(original_provider)
            run.append(
                root / "dispatch-journal.jsonl",
                {
                    "type": "reserve",
                    "attemptId": "unfinished",
                    "reservation": {"invocationId": "inv"},
                },
            )
            output = report.build_report(root)
            self.assertIsNone(output["observations"][0]["costEur"])
            self.assertIsNone(output["observations"][0]["reservedUnknownEur"])
            self.assertEqual(output["criterion"], "incomplete")


if __name__ == "__main__":
    unittest.main()
