"""Versioned mixed-content fixture, schedule, and ordinary Artemis API adapters.

Authoring metadata and semantic revisions never enter initial API payloads. Object
keys are fixture identities; numeric Artemis IDs belong only to isolated courses.
"""

from collections import Counter
from copy import deepcopy
import random
import time

EXERCISE_TYPES = ("programming", "text", "modeling", "file-upload", "quiz")
UNIT_TYPES = ("text", "online", "attachment-video")
ANCHOR_TYPES = (
    "programming",
    "text",
    "modeling",
    "file-upload",
    "quiz",
    "lecture",
    "lecture",
    "lecture",
    "programming",
    "text",
    "modeling",
    "quiz",
)
EXERCISE_PATHS = {
    "programming": "/api/programming/programming-exercises",
    "text": "/api/text/text-exercises",
    "modeling": "/api/modeling/modeling-exercises",
    "file-upload": "/api/fileupload/file-upload-exercises",
    "quiz": "/api/quiz/quiz-exercises",
}
CONTENT_FIELDS = (
    "title",
    "name",
    "problemStatement",
    "exampleSolution",
    "exampleSolutionExplanation",
    "quizQuestions",
    "content",
    "description",
    "source",
    "videoSource",
)


def enabled(data):
    return data.get("fixtureVersion") == "atlas-logos-fixture-v2-heterogeneous"


def wire_type(obj):
    """Attachment/video is exposed as `attachment` by Artemis response DTOs."""
    return (
        "attachment"
        if obj["kind"] == "lecture" and obj["type"] == "attachment-video"
        else obj["type"]
    )


def objects(data):
    return [
        obj | {"kind": kind, "topicIndex": i}
        for i, topic in enumerate(data["topics"])
        for kind, obj in [("exercise", e) for e in topic["exercises"]]
        + [("lecture", topic["lectureUnit"])]
    ]


def anchors(data):
    return [
        next(
            obj["id"]
            for obj in objects(data)
            if obj["topicIndex"] == i
            and (
                obj["kind"] == "lecture"
                if wanted == "lecture"
                else obj["kind"] == "exercise" and obj["type"] == wanted
            )
        )
        for i, wanted in enumerate(ANCHOR_TYPES)
    ]


def validate(data):
    if not isinstance(data, dict) or len(data.get("topics", [])) != 12:
        raise ValueError("heterogeneous fixture needs twelve topics")
    all_objects = objects(data)
    if len(all_objects) != 72 or len({obj["id"] for obj in all_objects}) != 72:
        raise ValueError("fixture must contain 72 unique stable object IDs")
    for topic in data["topics"]:
        if Counter(e["type"] for e in topic["exercises"]) != Counter(EXERCISE_TYPES):
            raise ValueError("each topic needs one exercise of each type")
        if topic["lectureUnit"]["type"] not in UNIT_TYPES:
            raise ValueError("unsupported lecture unit type")
    if Counter(t["lectureUnit"]["type"] for t in data["topics"]) != Counter(
        {t: 4 for t in UNIT_TYPES}
    ):
        raise ValueError("fixture needs four lecture units of each type")
    for obj in all_objects:
        field = (
            "quizQuestions"
            if obj["kind"] == "exercise" and obj["type"] == "quiz"
            else (
                "problemStatement"
                if obj["kind"] == "exercise"
                else "content"
                if obj["type"] == "text"
                else "description"
            )
        )
        if not obj.get(field) or not obj.get("semanticUpdate", {}).get(field):
            raise ValueError(f"{obj['id']} lacks content or a semantic revision")
        if obj[field] == obj["semanticUpdate"][field]:
            raise ValueError(f"{obj['id']} has an unchanged semantic revision")
        if field == "quizQuestions":
            for question in obj[field] + obj["semanticUpdate"][field]:
                validate_question(question)
    coverage = {(o["kind"], o["type"]) for o in all_objects if o["id"] in anchors(data)}
    if len(coverage) != 8:
        raise ValueError("anchors must cover all eight learning-object types")
    return data


def linked_keys(data, condition):
    all_objects, selected = objects(data), set(anchors(data))
    if condition in {"maintained", "single-exercise", "lecture-maintenance"}:
        return {o["id"] for o in all_objects}
    if condition == "assignment-only":
        return {o["id"] for o in all_objects} - selected
    if condition == "mixed":
        return {o["id"] for o in all_objects if o["topicIndex"] < 6} - selected
    return set()


def schedule(data, conditions, mode, seed, repetitions, rehearsal=False):
    all_objects = objects(data)
    by_key = {obj["id"]: obj for obj in all_objects}
    anchor_keys = anchors(data)
    remainder = [obj["id"] for obj in all_objects if obj["id"] not in anchor_keys]
    result = []
    for repetition in range(1, (1 if mode == "smoke" else repetitions) + 1):
        order = list(conditions)
        random.Random(seed + repetition * 1_000_003).shuffle(order)
        # A mixed smoke exercises all types via its anchors; it is separate from formal evidence.
        if mode == "smoke" and not rehearsal:
            order = ["mixed", "single-exercise", "lecture-maintenance"]
        for condition in order:
            keys = anchor_keys
            if condition == "single-exercise":
                wanted = EXERCISE_TYPES[(repetition - 1) % 5]
                candidates = [
                    o["id"]
                    for o in all_objects
                    if o["kind"] == "exercise" and o["type"] == wanted
                ]
                keys = [candidates[(repetition - 1) // 5]]
            elif condition == "lecture-maintenance":
                wanted = UNIT_TYPES[(repetition - 1) % 3]
                candidates = [
                    o["id"]
                    for o in all_objects
                    if o["kind"] == "lecture" and o["type"] == wanted
                ]
                keys = [candidates[((repetition - 1) // 3) % len(candidates)]]
            batches = (
                [keys]
                if condition != "bootstrap"
                else [anchor_keys, remainder[:24], remainder[24:48], remainder[48:]]
            )
            prior = None
            for stage, batch in enumerate(batches, 1):
                oid = f"r{repetition:02d}-{condition}-s{stage}"
                result.append(
                    {
                        "scheduleIndex": len(result),
                        "observationId": oid,
                        "condition": condition,
                        "repetition": repetition,
                        "stage": stage,
                        "courseKey": f"r{repetition:02d}-{condition}",
                        "invocationId": f"invocation-{len(result) + 1:03d}",
                        "trigger": "manual"
                        if condition == "single-exercise"
                        else "automatic",
                        "changedObjectKeys": batch,
                        "changedObjectTypes": [
                            f"{by_key[k]['kind']}:{by_key[k]['type']}" for k in batch
                        ],
                        "requiresObservationId": prior,
                    }
                )
                prior = oid
    return result


def payload(driver, obj, course_id, competency=None):
    """Construct an allowlisted request; exclude author notes and revision answers."""
    links = (
        []
        if competency is None
        else [{"competency": {"id": competency}, "weight": 1.0}]
    )
    if obj["kind"] == "lecture":
        result = {
            k: deepcopy(obj[k])
            for k in ("name", "content", "description", "source", "videoSource")
            if k in obj
        }
        if obj["type"] == "attachment-video":
            result.setdefault(
                "videoSource", f"https://example.org/synthetic-cs/{obj['id']}.mp4"
            )
        return result | {
            "type": wire_type(obj),
            "releaseDate": driver.now(),
            "competencyLinks": links,
        }
    result = {
        "title": obj["title"],
        "channelName": f"exercise-{course_id}-{obj['id']}",
        "shortName": obj["id"].replace("-", ""),
        "courseId": course_id,
        "releaseDate": driver.now(),
        "mode": "INDIVIDUAL",
        "includedInOverallScore": "INCLUDED_COMPLETELY",
        "maxPoints": 10,
        "bonusPoints": 0,
        "difficulty": "MEDIUM",
        "competencyLinks": links,
        "categories": [],
    }
    for key in (
        "problemStatement",
        "exampleSolution",
        "exampleSolutionExplanation",
        "diagramType",
        "filePattern",
        "quizQuestions",
    ):
        if key in obj:
            result[key] = deepcopy(obj[key])
    if obj["type"] == "programming":
        result.pop(
            "exampleSolution", None
        )  # Not an input supported by the programming extractor/API.
        result.update(
            {
                "course": {"id": course_id},
                "programmingLanguage": "JAVA",
                "projectType": "PLAIN_MAVEN",
                "packageName": "de.example.course",
                "allowOnlineEditor": True,
                "allowOfflineIde": True,
                "allowOnlineIde": False,
                "staticCodeAnalysisEnabled": False,
                "assessmentType": "AUTOMATIC",
                "buildConfig": {
                    "sequentialTestRuns": False,
                    "testCheckoutPath": "test",
                },
            }
        )
    elif obj["type"] == "quiz":
        result.pop("problemStatement", None)
        result.pop("exampleSolution", None)
        result.update(
            {
                "quizMode": "INDIVIDUAL",
                "duration": 1800,
                "randomizeQuestionOrder": False,
            }
        )
    else:
        result["assessmentType"] = "MANUAL"
    return result


def endpoint(obj, record, action):
    """Return the actual type-specific CRUD endpoint and multipart part name."""
    if obj["kind"] == "lecture":
        path = f"/api/lecture/lectures/{record['lectureId']}/{obj['type']}-units"
        if action == "read" or action == "update" and obj["type"] == "attachment-video":
            path += f"/{record['id']}"
        return path, "attachmentVideoUnit" if obj[
            "type"
        ] == "attachment-video" and action != "read" else False
    path = EXERCISE_PATHS[obj["type"]]
    if action == "create":
        if obj["type"] == "programming":
            path += "/setup"
        elif obj["type"] == "quiz":
            path = f"/api/quiz/courses/{record['courseId']}/quiz-exercises"
    elif action == "read" or obj["type"] in {"file-upload", "quiz"}:
        path += f"/{record['id']}"
    return path, "exercise" if obj["type"] == "quiz" and action != "read" else False


def read_object(driver, base, obj, record):
    path, _ = endpoint(obj, record, "read")
    return driver.api(base, "GET", path)


def create_fixtures(driver, data, base, items, debounce, campaign):
    result = {}
    by_key = {obj["id"]: obj for obj in objects(data)}
    for key in dict.fromkeys(item["courseKey"] for item in items):
        condition = next(
            item["condition"] for item in items if item["courseKey"] == key
        )
        course = driver.course_payload(key, False, debounce, campaign)
        course_id = driver.entity_id(
            driver.api(base, "POST", "/api/admin/courses", course, True)
        )
        count = 0 if condition == "bootstrap" else 6 if condition == "mixed" else 12
        competency_ids = [
            driver.entity_id(
                driver.api(
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
            for topic in data["topics"][:count]
        ]
        linked = linked_keys(data, condition)
        record = {
            "courseId": course_id,
            "course": course | {"id": course_id},
            "competencyIds": competency_ids,
            "objects": {},
            "automationEnabled": False,
        }
        for topic_index, topic in enumerate(data["topics"]):
            lecture_id = driver.entity_id(
                driver.api(
                    base,
                    "POST",
                    "/api/lecture/lectures",
                    {
                        "course": {"id": course_id},
                        "title": topic["title"],
                        "description": topic["description"],
                    },
                )
            )
            for obj in [o for o in by_key.values() if o["topicIndex"] == topic_index]:
                saved = {
                    "courseId": course_id,
                    "lectureId": lecture_id,
                    "kind": obj["kind"],
                    "type": obj["type"],
                }
                competency = (
                    competency_ids[topic_index] if obj["id"] in linked else None
                )
                body = payload(driver, obj, course_id, competency)
                path, part = endpoint(obj, saved, "create")
                saved["id"] = driver.entity_id(
                    driver.api(base, "POST", path, body, part)
                )
                record["objects"][obj["id"]] = saved
        record["exerciseIds"] = [
            o["id"] for o in record["objects"].values() if o["kind"] == "exercise"
        ]
        initial = persisted_state(driver, data, base, record)
        if initial["competencyCount"] != count or initial[
            "exerciseLinkCount"
        ] + initial["lectureLinkCount"] != len(linked):
            raise RuntimeError(
                "heterogeneous fixture initial mappings differ from the condition"
            )
        expected_links = {
            obj["id"]: competency_ids[obj["topicIndex"]]
            if obj["id"] in linked
            else None
            for obj in by_key.values()
        }
        verify_content(driver, data, base, record, expected_links)
        record["initialState"] = initial
        result[key] = record
    return {"fixtureSha256": driver.digest(data), "courses": result}


def content(obj):
    return {key: obj[key] for key in CONTENT_FIELDS if key in obj}


def persisted_state(driver, data, base, record):
    course = driver.api(
        base,
        "GET",
        f"/api/course/courses/{record['courseId']}/with-exercises-lectures-competencies",
    )
    if course["id"] != record["courseId"] or {
        e["id"] for e in course.get("exercises", [])
    } != set(record["exerciseIds"]):
        raise RuntimeError("heterogeneous course or exercise ownership changed")
    expected_lectures = {o["lectureId"] for o in record["objects"].values()}
    if {lecture["id"] for lecture in course.get("lectures", [])} != expected_lectures:
        raise RuntimeError("heterogeneous lecture ownership changed")
    mappings, contents = {}, {}
    counts = Counter()
    for obj in objects(data):
        saved = record["objects"][obj["id"]]
        current = read_object(driver, base, obj, saved)
        if current.get("id") != saved["id"] or current.get("type") != wire_type(obj):
            raise RuntimeError("learning object identity changed")
        if (
            obj["kind"] == "exercise"
            and current.get("courseId", (current.get("course") or {}).get("id"))
            != record["courseId"]
        ):
            raise RuntimeError("exercise course ownership changed")
        links = sorted(
            (link["competency"]["id"], link["weight"])
            for link in current.get("competencyLinks", [])
        )
        mappings[obj["id"]] = links
        contents[obj["id"]] = content(current)
        counts[obj["kind"]] += len(links)
    competencies = sorted(
        (c["id"], driver.digest([c["title"], c.get("description")]))
        for c in course.get("competencies", [])
    )
    return {
        "courseId": course["id"],
        "exerciseCount": 60,
        "lectureUnitCount": 12,
        "competencyCount": len(competencies),
        "exerciseLinkCount": counts["exercise"],
        "lectureLinkCount": counts["lecture"],
        "mappingSha256": driver.digest([competencies, mappings]),
        "contentSha256": driver.digest(contents),
    }


def mapping_fields(question):
    if question["type"] == "drag-and-drop":
        return (
            ("dragItems", "dragItem", "dragItemTempId"),
            ("dropLocations", "dropLocation", "dropLocationTempId"),
        )
    return (
        ("solutions", "solution", "solutionTempId"),
        ("spots", "spot", "spotTempId"),
    )


def validate_question(question):
    if (
        question.get("points", 0) <= 0
        or not question.get("text")
        or not question.get("title")
    ):
        raise ValueError("quiz questions need title, text, and positive points")
    if question["type"] == "multiple-choice":
        if (
            question.get("singleChoice")
            and question.get("scoringType") != "ALL_OR_NOTHING"
        ):
            raise ValueError("single-choice questions require ALL_OR_NOTHING scoring")
        options = question.get("answerOptions", [])
        if len(options) < 2 or not any(o.get("isCorrect") for o in options):
            raise ValueError("multiple choice needs alternatives and a correct answer")
        if (
            question.get("singleChoice")
            and sum(bool(o.get("isCorrect")) for o in options) != 1
        ):
            raise ValueError("single choice must have exactly one correct answer")
    elif question["type"] in {"short-answer", "drag-and-drop"}:
        if not question.get("correctMappings"):
            raise ValueError("quiz mapping is missing")
        for collection, _, ref in mapping_fields(question):
            nodes = question.get(collection, [])
            identifiers = {node.get("tempID") for node in nodes}
            if not nodes or None in identifiers or len(identifiers) != len(nodes):
                raise ValueError("quiz nodes need unique temporary identifiers")
            if any(m.get(ref) not in identifiers for m in question["correctMappings"]):
                raise ValueError("quiz mapping references a missing node")
    else:
        raise ValueError("unsupported quiz question type")


def editor_questions(questions):
    """Convert retrieval DTO mapping objects to the editor's ID references."""
    result = deepcopy(questions)
    for question in result:
        if question["type"] not in {"short-answer", "drag-and-drop"}:
            continue
        for mapping in question["correctMappings"]:
            for _, nested, reference in mapping_fields(question):
                if nested in mapping:
                    mapping[reference] = mapping[nested]["id"]
                    del mapping[nested]
    return result


def quiz_projection(questions):
    """Compare learning text and answers independently of persisted identifiers."""
    result = []
    for question in questions:
        item = {
            key: question.get(key)
            for key in (
                "type",
                "title",
                "text",
                "hint",
                "explanation",
                "points",
                "scoringType",
            )
        }
        if question["type"] == "multiple-choice":
            item["answerOptions"] = [
                {k: option.get(k) for k in ("text", "hint", "explanation", "isCorrect")}
                for option in question["answerOptions"]
            ]
            item["singleChoice"] = question.get("singleChoice")
        else:
            fields = mapping_fields(question)
            index_maps = {}
            for collection, _, reference in fields:
                index_maps[reference] = {
                    node.get("tempID", node.get("id")): index
                    for index, node in enumerate(question[collection])
                }
                item[collection] = [
                    {
                        k: v
                        for k, v in node.items()
                        if k not in {"id", "tempID", "invalid"} and v is not None
                    }
                    for node in question[collection]
                ]
            mappings = []
            for mapping in question["correctMappings"]:
                pair = []
                for _, nested, reference in fields:
                    identifier = (
                        mapping[nested]["id"]
                        if nested in mapping
                        else mapping[reference]
                    )
                    pair.append(index_maps[reference][identifier])
                mappings.append(pair)
            item["correctMappings"] = sorted(mappings)
            if question["type"] == "short-answer":
                item.update(
                    {
                        key: question.get(key)
                        for key in ("similarityValue", "matchLetterCase")
                    }
                )
        result.append(item)
    return result


def verify_content(driver, data, base, record, expected_links=None):
    """Read back every submitted field; quiz identifiers may be assigned by persistence."""
    for obj in objects(data):
        current = read_object(driver, base, obj, record["objects"][obj["id"]])
        if expected_links is not None:
            competency = expected_links[obj["id"]]
            expected = [] if competency is None else [(competency, 1.0)]
            actual = sorted(
                (link["competency"]["id"], link["weight"])
                for link in current.get("competencyLinks", [])
            )
            if actual != expected:
                raise RuntimeError(
                    f"{obj['id']}: initial competency links differ from the condition"
                )
        expected = content(payload(driver, obj, record["courseId"]))
        for key, value in expected.items():
            if key == "quizQuestions":
                if quiz_projection(current.get(key, [])) != quiz_projection(value):
                    raise RuntimeError(
                        f"{obj['id']}: persisted quiz content or correct mappings changed"
                    )
            elif current.get(key) != value:
                raise RuntimeError(f"{obj['id']}: persisted {key} differs from fixture")


def update_object(driver, base, obj, saved, semantic):
    current = read_object(driver, base, obj, saved)
    body = payload(driver, obj, saved["courseId"])
    body.update(current)
    body["courseId"] = saved["courseId"]
    if obj["kind"] == "lecture" and obj["type"] == "attachment-video":
        # Response DTOs omit the required update intent. Only descriptions change here.
        body["attachmentUpdateIntent"] = "NO_FILE_CHANGE"
    if obj["kind"] == "exercise" and obj["type"] == "programming":
        build_config = body.get("programmingExerciseBuildConfig")
        if build_config is not None:
            body["buildConfig"] = deepcopy(build_config)
        solution = body.get("solutionParticipation") or {}
        if solution.get("repositoryUri"):
            body["solutionRepositoryUri"] = solution["repositoryUri"]
    if semantic:
        changes = {
            k: deepcopy(v)
            for k, v in obj["semanticUpdate"].items()
            if k in CONTENT_FIELDS
        }
        if obj["kind"] == "exercise" and obj["type"] == "programming":
            changes.pop("exampleSolution", None)
        body.update(changes)
    elif obj["kind"] == "exercise" and obj["type"] == "quiz":
        body["quizQuestions"] = editor_questions(current["quizQuestions"])
        # Quiz snapshots omit stems and titles normalize whitespace. Toggle title
        # punctuation for a meaning-preserving versioned edit, preserving answers.
        body["title"] = current["title"].removesuffix(".") if current["title"].endswith(".") else current["title"] + "."
    else:
        field = (
            "problemStatement"
            if obj["kind"] == "exercise"
            else "content"
            if obj["type"] == "text"
            else "description"
        )
        body[field] = current[field] + "\n"
    path, part = endpoint(obj, saved, "update")
    driver.api(base, "PUT", path, body, part)


def trigger(driver, data, base, record, item, debounce):
    by_key = {obj["id"]: obj for obj in objects(data)}
    keys = item["changedObjectKeys"]
    semantic = item["condition"] in {"single-exercise", "lecture-maintenance"}
    if item["trigger"] == "automatic":
        driver.set_automation(base, record, True, 3600)
    started = time.monotonic()
    for key in keys:
        update_object(driver, base, by_key[key], record["objects"][key], semantic)
    if item["trigger"] == "manual":
        driver.api(
            base,
            "POST",
            f"/api/atlas/orchestrator/exercises/{record['objects'][keys[0]]['id']}/run",
        )
    else:
        driver.set_automation(base, record, True, debounce)
    return started
