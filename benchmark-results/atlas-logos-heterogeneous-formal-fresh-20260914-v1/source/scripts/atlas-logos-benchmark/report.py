#!/usr/bin/env python3
"""Reconcile provider attempts with Logos and report every scheduled observation."""

import argparse
import hashlib
import json
import os
from collections import defaultdict
from decimal import Decimal, InvalidOperation
from pathlib import Path
from statistics import median

ROOT = Path(__file__).resolve().parents[2]


def records(path, issues=None):
    if not path.exists():
        return []
    result = []
    for number, line in enumerate(path.read_text().splitlines(), 1):
        try:
            value = json.loads(line)
            if not isinstance(value, dict):
                raise ValueError("record is not an object")
            result.append(value)
        except (ValueError, json.JSONDecodeError):
            if issues is None:
                raise ValueError(f"Invalid evidence: {path.name}:{number}") from None
            issues.append(f"Invalid evidence: {path.name}:{number}")
    return result


def attempt_cost(event, pricing, exports):
    """Unknown or unreconciled usage has no numeric cost, including failed attempts."""
    model = event.get("returnedModel")
    rate = pricing.get("models", {}).get(model)
    if not rate or model != event.get("requestedModel") and rate.get("aliasOf") != event.get("requestedModel"):
        return None, "model-unverified"
    if "cacheWriteInputTokens" not in event:
        if "cacheWriteInputEurPerMillion" in rate:
            return None, "usage-unavailable"
        cache_write = 0
    else:
        cache_write = event.get("cacheWriteInputTokens")
    values = [event.get(key) for key in ("inputTokens", "cachedInputTokens", "outputTokens")]
    if (
        event.get("usageAvailable") is not True
        or any(type(v) is not int or v < 0 for v in values)
        or type(cache_write) is not int
        or cache_write < 0
    ):
        return None, "usage-unavailable"
    inputs, cached, outputs = values
    if cached + cache_write > inputs or inputs > rate["maxSupportedInputTokens"] or outputs > rate["maxOutputTokens"]:
        return None, "usage-outside-pricing"
    row = exports.get(event.get("logosRequestId"))
    if not row:
        return None, "logos-unmatched"
    usage = row.get("usage", {})
    # Logos omits zero-valued token rows. Only raw HTTP evidence can establish zero.
    expected = {
        "prompt_tokens": inputs,
        "prompt_cached_tokens": cached,
        "prompt_cache_write_tokens": cache_write,
        "completion_tokens": outputs,
    }
    if row.get("model") != event.get("requestedModel") or any(
        usage.get(key, 0 if count == 0 else None) != count for key, count in expected.items()
    ):
        return None, "logos-usage-mismatch"
    if row.get("status") != ("success" if event.get("httpStatus", 0) in range(200, 300) else "error"):
        return None, "logos-status-mismatch"
    try:
        rates = [
            Decimal(rate[key])
            for key in (
                "inputEurPerMillion",
                "cachedInputEurPerMillion",
                "outputEurPerMillion",
            )
        ]
        if any(not r.is_finite() or r < 0 for r in rates):
            raise ValueError("invalid price")
        cache_write_rate = Decimal(rate["cacheWriteInputEurPerMillion"]) if "cacheWriteInputEurPerMillion" in rate else None
        if (
            cache_write > 0
            and cache_write_rate is None
            or cache_write_rate is not None
            and (not cache_write_rate.is_finite() or cache_write_rate < 0)
        ):
            raise ValueError("invalid cache write price")
        tier = rate.get("longContext")
        input_multiplier = output_multiplier = Decimal(1)
        if rate["maxSupportedInputTokens"] > 272_000 and tier is None:
            raise ValueError("missing long-context prices")
        if tier is not None:
            threshold = tier["thresholdTokens"]
            multipliers = [Decimal(str(tier[key])) for key in ("inputMultiplier", "outputMultiplier")]
            if type(threshold) is not int or not 0 < threshold < rate["maxSupportedInputTokens"] or any(
                not multiplier.is_finite() or multiplier < 1 for multiplier in multipliers
            ):
                raise ValueError("invalid long-context prices")
            if inputs > threshold:
                input_multiplier, output_multiplier = multipliers
        return sum(
            v * r
            for v, r in zip(
                (inputs - cached - cache_write, cached, cache_write, outputs),
                (rates[0] * input_multiplier, rates[1] * input_multiplier,
                 (cache_write_rate or Decimal(0)) * input_multiplier, rates[2] * output_multiplier),
            )
        ) / 1_000_000, "matched"
    except (InvalidOperation, ValueError, KeyError, TypeError):
        return None, "price-unverified"


def stats(rows):
    values = sorted(Decimal(row["costEur"]) for row in rows if row.get("costEur") is not None)
    return {
        "count": len(rows),
        "measuredCount": len(values),
        "medianEur": str(median(values)) if values else None,
        "rangeEur": [str(values[0]), str(values[-1])] if values else None,
        "maximumEur": str(values[-1]) if values else None,
    }


def build_report(root):
    issues = []
    manifest = json.loads((root / "manifest.json").read_text())
    raw_prices = (root / "pricing.json").read_bytes()
    pricing = json.loads(raw_prices)
    if hashlib.sha256(raw_prices).hexdigest() != manifest["pricingSha256"]:
        issues.append("Frozen pricing hash changed")
    schedule = json.loads((root / "schedule.json").read_text())["schedule"]
    if hashlib.sha256(json.dumps(schedule, sort_keys=True, separators=(",", ":")).encode()).hexdigest() != manifest["scheduleSha256"]:
        issues.append("Frozen schedule hash changed")
    export_rows = records(
        Path(os.environ.get("ATLAS_BENCHMARK_LOGOS_EXPORT", root / "logos.jsonl")),
        issues,
    )
    exports = {row.get("requestId"): row for row in export_rows}
    if len(exports) != len(export_rows):
        issues.append("Duplicate Logos request IDs")
    provider = records(root / "provider-events.jsonl", issues)
    invocations = records(root / "invocation-events.jsonl", issues)
    observations = records(root / "observations.jsonl", issues)
    journal = records(root / "dispatch-journal.jsonl", issues)
    attempts, terminals, lifecycle = defaultdict(list), defaultdict(list), {}
    for row in provider:
        attempts[row["invocationId"]].append(row)
    for row in invocations:
        terminals[row["observationId"]].append(row)
    for row in observations:
        lifecycle[row["observationId"]] = row
    gateway_ids = [row["logosRequestId"] for row in provider if row.get("logosRequestId")]
    if len(set(gateway_ids)) != len(gateway_ids):
        issues.append("One Logos request was attributed to multiple provider attempts")
    captured_ids = {row["eventId"] for row in provider}
    if len(captured_ids) != len(provider):
        issues.append("Duplicate provider attempt IDs")
    planned_ids = {row["invocationId"] for row in schedule}
    if any(row["invocationId"] not in planned_ids for row in provider + invocations):
        issues.append("Evidence contains unscheduled invocations")
    uncaptured = {
        row["reservation"]["invocationId"] for row in journal if row["type"] == "reserve" and row["attemptId"] not in captured_ids
    }
    unresolved = {}
    for entry in journal:
        if entry["type"] == "reserve":
            unresolved[entry["attemptId"]] = entry["reservation"]
        elif entry["type"] == "settle":
            unresolved.pop(entry["attemptId"], None)
    rows = []
    phase_costs = defaultdict(list)
    for planned in schedule:
        oid, iid = planned["observationId"], planned["invocationId"]
        terminal = terminals[oid]
        observed = lifecycle.get(oid, {})
        costs = [attempt_cost(attempt, pricing, exports) for attempt in attempts[iid]]
        complete_usage = (
            len(terminal) == 1
            and terminal[0].get("invocationId") == iid
            and terminal[0].get("usageComplete") is True
            and bool(attempts[iid])
            and iid not in uncaptured
            and not issues
        )
        measured = complete_usage and all(cost is not None for cost, _ in costs)
        status = observed.get("status", "pending")
        if status == "started":
            status = "interrupted"
        technical = observed.get("beforeState") is not None and observed.get("afterState") is not None and not observed.get("error")
        classification = (
            "valid-completed"
            if status == "completed" and measured and technical
            else "valid-failure"
            if status in {"failure", "partial"} and measured
            else "invalid-unmeasured"
        )
        total = sum((cost for cost, _ in costs), Decimal(0)) if measured else None
        holds = [entry.get("reservedCostEur") for entry in unresolved.values() if entry["invocationId"] == iid]
        rows.append(
            {
                **planned,
                "status": status,
                "classification": "infrastructure-failure"
                if status == "infrastructure_failure"
                else "valid-partial"
                if status == "partial" and measured
                else classification,
                "costEur": str(total) if total is not None else None,
                "attemptCount": len(costs),
                "retryAttemptCount": sum((a.get("sdkRetryCount") or 0) > 0 for a in attempts[iid]),
                "unknownAttemptCount": sum(cost is None for cost, _ in costs),
                "knownCostEur": str(sum((cost for cost, _ in costs if cost is not None), Decimal(0))),
                "reservedUnknownEur": None if None in holds else str(sum((Decimal(str(value)) for value in holds), Decimal(0))),
                "reconciliation": sorted({reason for _, reason in costs}),
                "reason": observed.get("error") or observed.get("skipReason") or observed.get("terminalDetails", {}).get("failureReason"),
                "modelStatus": observed.get("modelStatus"),
                "terminalDetails": observed.get("terminalDetails", {}),
                "cleanupError": observed.get("cleanupError"),
                "executionMs": terminal[0].get("durationMs") if len(terminal) == 1 else None,
                "eventToCompletionMs": observed.get("eventToCompletionMs"),
                "beforeState": observed.get("beforeState"),
                "afterState": observed.get("afterState"),
            }
        )
        for attempt, (cost, _) in zip(attempts[iid], costs):
            phase_costs[attempt["phase"]].append({"costEur": str(cost) if cost is not None else None})
    conditions = {}
    for condition in dict.fromkeys(item["condition"] for item in schedule):
        primary = [row for row in rows if row["condition"] == condition and row["stage"] == 1]
        conditions[condition] = {
            "completed": stats([r for r in primary if r["classification"] == "valid-completed"]),
            "failures": stats([r for r in primary if r["classification"] == "valid-failure"]),
            "partial": stats([r for r in primary if r["classification"] == "valid-partial"]),
            "infrastructureFailures": stats([r for r in primary if r["classification"] == "infrastructure-failure"]),
            "unmeasuredCount": sum(r["classification"] == "invalid-unmeasured" for r in primary),
        }
    workflows = []
    for course in dict.fromkeys(row["courseKey"] for row in rows if row["condition"] == "bootstrap"):
        stages = [r for r in rows if r["courseKey"] == course]
        total = (
            sum((Decimal(r["costEur"]) for r in stages), Decimal(0))
            if len(stages) == len([s for s in schedule if s["courseKey"] == course]) and all(r["costEur"] is not None for r in stages)
            else None
        )
        workflows.append({"courseKey": course, "costEur": str(total) if total is not None else None})
    exceeds = any(row["costEur"] is not None and Decimal(row["costEur"]) > Decimal("0.50") for row in rows)
    all_measured = (
        bool(rows) and all(row["costEur"] is not None and row["classification"] != "invalid-unmeasured" for row in rows) and not issues
    )
    expected = {
        ("orchestration", "gpt-5.6-luna", "xhigh"),
        ("worker", "gpt-5.6-luna", "high"),
        ("flavor_strip", "gpt-5.6-luna", "medium"),
    }
    observed_models = {(p.get("phase"), p.get("requestedModel"), p.get("reasoningEffort")) for p in provider if p.get("httpStatus") == 200}
    object_coverage = {}
    for object_type in sorted({value for row in rows for value in row.get("changedObjectTypes", [])}):
        targeted = [row for row in rows if object_type in row.get("changedObjectTypes", [])]
        object_coverage[object_type] = {
            "scheduledObservations": len(targeted),
            "completedObservations": sum(row["classification"] == "valid-completed" for row in targeted),
            "targetedObjectOccurrences": sum(row["changedObjectTypes"].count(object_type) for row in targeted),
        }
    smoke = {
        "modelAndReasoningCoverage": expected <= observed_models,
        "allScheduledCompleted": bool(rows) and all(row["classification"] == "valid-completed" for row in rows),
        "workerObserved": any(p.get("phase") == "worker" for p in provider),
        "persistedMappingChange": any(
            r["beforeState"] and r["afterState"] and r["beforeState"]["mappingSha256"] != r["afterState"]["mappingSha256"] for r in rows
        ),
        "automaticTriggerCompleted": any(
            r["condition"] == "lecture-maintenance" and r["classification"] == "valid-completed" for r in rows
        ),
    }
    return {
        "campaignId": manifest["campaignId"],
        "integrityIssues": issues,
        "criterionEur": "0.50",
        "criterion": "fail" if exceeds else "pass" if all_measured else "incomplete",
        "providerAttemptCount": len(provider),
        "smokeCoverage": smoke if manifest["mode"] == "smoke" else None,
        "smokeReady": manifest["mode"] == "smoke" and all_measured and all(smoke.values()),
        "providerAttempts": provider,
        "conditions": conditions,
        "continuations": [r for r in rows if r["stage"] > 1],
        "bootstrapWorkflows": workflows,
        "learningObjectCoverage": object_coverage or None,
        "phases": {phase: stats(values) for phase, values in phase_costs.items()},
        "observations": rows,
    }


def render(mode):
    campaign = os.environ.get("ATLAS_BENCHMARK_CAMPAIGN_ID", f"atlas-logos-heterogeneous-{mode}-20260913" if os.environ.get("ATLAS_BENCHMARK_FIXTURE") == "heterogeneous" else f"atlas-logos-{mode}-20260910")
    root = Path(
        os.environ.get(
            "ATLAS_BENCHMARK_EVIDENCE_DIR",
            ROOT / "build/atlas-logos-benchmark/evidence" / campaign,
        )
    )
    print(json.dumps(build_report(root), indent=2))
    return 0


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--mode", choices=("smoke", "formal"), default="formal")
    raise SystemExit(render(parser.parse_args().mode))
