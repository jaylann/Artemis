"""Mixed-content experiment invariants and ordinary API transport contracts."""

from collections import Counter
from copy import deepcopy
from decimal import Decimal
import hashlib
import contextlib
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch

from test_core import run, report
import heterogeneous as h


class HeterogeneousTests(unittest.TestCase):
    def setUp(self):
        self.env = patch.dict(os.environ, {"ATLAS_BENCHMARK_FIXTURE": "heterogeneous"})
        self.env.start()
        self.addCleanup(self.env.stop)
        self.data = run.fixture()

    def test_course_and_anchor_coverage(self):
        objects = h.objects(self.data)
        self.assertEqual(
            Counter(o["type"] for o in objects if o["kind"] == "exercise"),
            Counter({t: 12 for t in h.EXERCISE_TYPES}),
        )
        anchors = set(h.anchors(self.data))
        self.assertEqual(len(anchors), 12)
        self.assertEqual(
            len({(o["kind"], o["type"]) for o in objects if o["id"] in anchors}), 8
        )
        for condition, total in (
            ("maintained", 72),
            ("assignment-only", 60),
            ("mixed", 30),
            ("bootstrap", 0),
        ):
            linked = h.linked_keys(self.data, condition)
            self.assertEqual(len(linked), total)
            if condition != "maintained":
                self.assertFalse(linked & anchors)
        self.assertEqual(
            {
                o["topicIndex"]
                for o in objects
                if o["id"] in h.linked_keys(self.data, "mixed")
            },
            set(range(6)),
        )

    def test_schedule_visits_every_object_once_in_bootstrap(self):
        rows = run.schedule("formal", 20260913, 10)
        self.assertEqual(len(rows), 90)
        self.assertEqual(sum(r["stage"] == 1 for r in rows), 60)
        self.assertEqual(len({r["invocationId"] for r in rows}), 90)
        all_keys = {o["id"] for o in h.objects(self.data)}
        for repetition in range(1, 11):
            stages = [
                r
                for r in rows
                if r["repetition"] == repetition and r["condition"] == "bootstrap"
            ]
            self.assertEqual(
                [len(r["changedObjectKeys"]) for r in stages], [12, 24, 24, 12]
            )
            visited = sum((r["changedObjectKeys"] for r in stages), [])
            self.assertEqual(len(visited), len(set(visited)))
            self.assertEqual(set(visited), all_keys)
            for previous, current in zip(stages, stages[1:]):
                self.assertEqual(
                    current["requiresObservationId"], previous["observationId"]
                )
        singles = [r for r in rows if r["condition"] == "single-exercise"]
        self.assertEqual(
            Counter(r["changedObjectTypes"][0] for r in singles),
            Counter({"exercise:" + t: 2 for t in h.EXERCISE_TYPES}),
        )
        units = [r for r in rows if r["condition"] == "lecture-maintenance"]
        self.assertEqual(
            Counter(r["changedObjectTypes"][0] for r in units),
            {"lecture:text": 4, "lecture:online": 3, "lecture:attachment-video": 3},
        )
        self.assertEqual(rows, run.schedule("formal", 20260913, 10))
        self.assertNotEqual(rows, run.schedule("formal", 20260914, 10))

    def test_smoke_targets_all_types_and_both_triggers(self):
        rows = run.schedule("smoke", 17, 10)
        self.assertEqual(len(rows), 3)
        self.assertEqual({r["trigger"] for r in rows}, {"manual", "automatic"})
        self.assertEqual(len(set(rows[0]["changedObjectTypes"])), 8)

    def test_corrupt_ids_and_quiz_references_are_rejected(self):
        data = deepcopy(self.data)
        data["topics"][1]["exercises"][0]["id"] = data["topics"][0]["exercises"][0][
            "id"
        ]
        with self.assertRaisesRegex(ValueError, "unique"):
            h.validate(data)
        questions = [
            q
            for t in self.data["topics"]
            for e in t["exercises"]
            if e["type"] == "quiz"
            for q in e["quizQuestions"]
        ]
        question = deepcopy(next(q for q in questions if q["type"] == "short-answer"))
        question["correctMappings"][0]["solutionTempId"] = -9999
        with self.assertRaisesRegex(ValueError, "missing node"):
            h.validate_question(question)

    def test_api_paths_and_multipart_part_names(self):
        records = {"courseId": 7, "lectureId": 9, "id": 11}
        actual = {}
        for obj in h.objects(self.data):
            actual[(obj["kind"], obj["type"])] = h.endpoint(obj, records, "create")
        self.assertEqual(
            actual[("exercise", "programming")],
            (
                "/api/programming/programming-exercises/setup",
                False,
            ),
        )
        self.assertEqual(
            actual[("exercise", "quiz")],
            ("/api/quiz/courses/7/quiz-exercises", "exercise"),
        )
        self.assertEqual(
            actual[("lecture", "attachment-video")],
            ("/api/lecture/lectures/9/attachment-video-units", "attachmentVideoUnit"),
        )
        for part in ("exercise", "attachmentVideoUnit", True):
            response = Mock()
            response.__enter__ = Mock(return_value=response)
            response.__exit__ = Mock(return_value=False)
            response.read.return_value = b'{"id": 1}'
            with patch.object(run.HTTP, "open", return_value=response) as request:
                run.api(
                    "http://localhost", "POST", "/unit", {"title": "Coursework"}, part
                )
            raw = request.call_args.args[0].data.decode()
            self.assertIn(f'name="{"course" if part is True else part}"', raw)

    def test_authoring_metadata_does_not_enter_payload(self):
        for obj in h.objects(self.data):
            body = h.payload(run, obj, 7)
            self.assertFalse(
                {"semanticUpdate", "authoringNotes", "topicIndex", "id"} & body.keys()
            )
            if obj["kind"] == "exercise" and obj["type"] == "programming":
                self.assertNotIn("exampleSolution", body)
            if obj["kind"] == "lecture" and obj["type"] == "attachment-video":
                self.assertEqual(body["type"], "attachment")

    def test_video_update_declares_no_file_change_for_both_edit_kinds(self):
        obj = next(o for o in h.objects(self.data) if o["kind"] == "lecture" and o["type"] == "attachment-video")
        saved = {"id": 11, "courseId": 7, "lectureId": 9}
        for semantic in (False, True):
            with self.subTest(semantic=semantic):
                current = h.payload(run, obj, 7) | {"id": 11, "attachmentUpdateIntent": None}
                driver = Mock(now=run.now)
                driver.api.return_value = current
                h.update_object(driver, "local", obj, saved, semantic)
                call = driver.api.call_args.args
                self.assertEqual(call[1], "PUT")
                self.assertEqual(call[3]["attachmentUpdateIntent"], "NO_FILE_CHANGE")
                self.assertEqual(call[4], "attachmentVideoUnit")

    def test_quiz_unchanged_edit_uses_versioned_title_without_changing_questions(self):
        for obj in (o for o in h.objects(self.data) if o["kind"] == "exercise" and o["type"] == "quiz"):
            with self.subTest(key=obj["id"]):
                current = h.payload(run, obj, 7) | {"id": 11}
                driver = Mock(now=run.now)
                driver.api.return_value = current
                h.update_object(driver, "local", obj, {"id": 11, "courseId": 7}, False)
                body = driver.api.call_args.args[3]
                self.assertEqual(body["title"], current["title"] + ".")
                self.assertNotEqual(body["title"], current["title"])
                self.assertEqual(body["quizQuestions"], current["quizQuestions"])

    def test_automatic_batch_is_accumulated_before_debounce_release(self):
        item = next(
            row
            for row in run.schedule("formal", 17, 1)
            if row["condition"] == "maintained"
        )
        objects = {obj["id"]: obj for obj in h.objects(self.data)}
        record = {
            "objects": {
                key: {"id": index, "courseId": 7, "lectureId": 9}
                for index, key in enumerate(objects, 1)
            }
        }
        calls = []
        driver = Mock()
        driver.set_automation.side_effect = lambda *args: calls.append(
            ("automation", args[-1])
        )
        with patch.object(
            h,
            "update_object",
            side_effect=lambda *args: calls.append(("update", args[2]["id"])),
        ):
            h.trigger(driver, self.data, "local", record, item, 5)
        self.assertEqual(calls[0], ("automation", 3600))
        self.assertEqual(calls[-1], ("automation", 5))
        self.assertEqual([key for kind, key in calls[1:-1]], item["changedObjectKeys"])
        driver.api.assert_not_called()

    def test_failed_update_does_not_release_or_replay_automatic_batch(self):
        item = next(
            row
            for row in run.schedule("formal", 17, 1)
            if row["condition"] == "maintained"
        )
        record = {
            "objects": {
                key: {"id": index, "courseId": 7}
                for index, key in enumerate(item["changedObjectKeys"], 1)
            }
        }
        driver = Mock()
        with patch.object(
            h, "update_object", side_effect=RuntimeError("uncertain write")
        ) as update:
            with self.assertRaisesRegex(RuntimeError, "uncertain write"):
                h.trigger(driver, self.data, "local", record, item, 5)
        self.assertEqual(update.call_count, 1)
        driver.set_automation.assert_called_once_with("local", record, True, 3600)
        # run.run's existing finally block owns disabling automation after this exception.

    def test_quiz_mapping_round_trip_and_solution_corruption(self):
        source = next(
            q
            for t in self.data["topics"]
            for e in t["exercises"]
            if e["type"] == "quiz"
            for q in e["quizQuestions"]
            if q["type"] == "short-answer"
        )
        saved = deepcopy(source)
        for collection, nested, ref in h.mapping_fields(source):
            for node in saved[collection]:
                node["id"] = node.pop("tempID") + 1000
            for mapping in saved["correctMappings"]:
                mapping[nested] = {"id": mapping.pop(ref) + 1000}
        self.assertEqual(h.quiz_projection([source]), h.quiz_projection([saved]))
        self.assertEqual(
            h.quiz_projection([source]), h.quiz_projection(h.editor_questions([saved]))
        )
        saved["solutions"][0]["text"] += " incorrect"
        self.assertNotEqual(h.quiz_projection([source]), h.quiz_projection([saved]))

    def test_prepare_records_ninety_unmeasured_observations_without_http(self):
        with (
            tempfile.TemporaryDirectory() as temp,
            patch.dict(os.environ, {"ATLAS_BENCHMARK_EVIDENCE_DIR": temp}),
            patch.object(run, "api") as api,
        ):
            with contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(run.prepare("formal", True), 0)
            api.assert_not_called()
            result = report.build_report(Path(temp))
            self.assertEqual(len(result["observations"]), 90)
            self.assertEqual(result["criterion"], "incomplete")
            self.assertTrue(
                all(row["costEur"] is None for row in result["observations"])
            )
            manifest = json.loads((Path(temp) / "manifest.json").read_text())
            self.assertEqual(manifest["bootstrapStages"], 4)

    def test_paid_dispatch_requires_explicit_new_budget(self):
        with (
            patch.dict(
                os.environ,
                {
                    "ATLAS_BENCHMARK_PAID_CONFIRMATION": "",
                    "ATLAS_BENCHMARK_BUDGET_EUR": "",
                },
            ),
            patch.object(run, "api") as api,
        ):
            with contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(run.run("smoke"), 2)
            api.assert_not_called()

    def test_review_is_invalidated_by_content_changes(self):
        review = {"status": "reviewed", "fixtureSha256": run.digest(self.data)}
        with patch.object(run, "load", return_value=review):
            self.assertTrue(run.mixed_content_reviewed(self.data))
            changed = deepcopy(self.data)
            changed["topics"][0]["exercises"][0]["problemStatement"] += " changed"
            self.assertFalse(run.mixed_content_reviewed(changed))
        with patch.object(run, "load", return_value={}):
            self.assertFalse(run.mixed_content_reviewed(self.data))

    def test_bootstrap_totals_include_fourth_stage_and_preserve_missing_cost(self):
        stages = [
            row
            for row in run.schedule("formal", 17, 1)
            if row["condition"] == "bootstrap"
        ]
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            raw = b'{"models": {}}'
            (root / "pricing.json").write_bytes(raw)
            run.write(
                root / "manifest.json",
                {
                    "campaignId": "test",
                    "mode": "formal",
                    "scheduleSha256": run.digest(stages),
                    "pricingSha256": hashlib.sha256(raw).hexdigest(),
                },
            )
            run.write(root / "schedule.json", {"schedule": stages})
            for stage in stages:
                run.append(
                    root / "provider-events.jsonl",
                    {
                        "eventId": stage["invocationId"],
                        "invocationId": stage["invocationId"],
                        "phase": "worker",
                    },
                )
                run.append(
                    root / "invocation-events.jsonl", stage | {"usageComplete": True}
                )
                run.append(
                    root / "observations.jsonl",
                    stage
                    | {"status": "completed", "beforeState": {}, "afterState": {}},
                )
            with patch.object(
                report, "attempt_cost", return_value=(Decimal("0.01"), "matched")
            ):
                self.assertEqual(
                    report.build_report(root)["bootstrapWorkflows"][0]["costEur"],
                    "0.04",
                )
            with patch.object(
                report,
                "attempt_cost",
                side_effect=[(Decimal("0.01"), "matched")] * 3 + [(None, "missing")],
            ):
                result = report.build_report(root)
                self.assertIsNone(result["bootstrapWorkflows"][0]["costEur"])
                self.assertEqual(result["criterion"], "incomplete")


if __name__ == "__main__":
    unittest.main()
