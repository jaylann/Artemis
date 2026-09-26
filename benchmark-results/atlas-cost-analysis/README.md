# Atlas cost campaign analysis

`analyze.py` recomputes the archived campaign `../atlas-cost-formal-20260926-v1/` offline. It reads only that package and never imports the live runner or meter.

```bash
python3 -m venv .venv && .venv/bin/pip install -r benchmark-results/atlas-cost-analysis/requirements.txt
.venv/bin/python benchmark-results/atlas-cost-analysis/analyze.py \
  --evidence benchmark-results/atlas-cost-formal-20260926-v1 --output /tmp/atlas-cost-analysis
```

`--no-figures` runs the accounting with the standard library only.

## What it checks and derives

- **Package integrity:** the package must match its frozen `checksums.json`.
- **Frozen setup:** the frozen schedule (seeded order, bootstrap chains, 60 isolated courses), pricing, phase configuration, and the matching smoke campaign in `smoke/`.
- **Ledger:** every attempt in `provider-attempts.jsonl` has one reservation and one terminal event. Every settled cost is recomputed from recorded usage and the frozen USD rates and exchange rate, and must equal the meter's value.
- **Observations:** every observation completed with complete evidence, starts from the state recorded before it (bootstrap continuations), and issued only recognized tools.
- **Outputs:** `summary.json`, `observations.json`/`.csv`, `audit.json`, Typst tables and the observation appendix, and three figures.

An attempt that returned an HTTP error without usage is never estimated. It is carried at its recorded worst-case reservation as an explicit upper bound (`upperBoundCostEur`). The acceptance criterion (EUR 15.00 per invocation) is evaluated on that upper bound. In this campaign, one orchestration request of `r06-bootstrap-s4` returned HTTP 500 (`req_a6d04ac600db407babfea7de6eba547c`). The SDK retried it, the observation completed, and the operator resume (`operator/resume.py`) carried the reservation forward. See `../atlas-cost-formal-20260926-v1/archive-provenance.json`.

## Definitions that differ from the 14 September analyzer

- **Tool calls (`callbacks`):** the function calls issued by the model in settled responses, as recorded by the request meter. There is no in-application tool counter.
- **`executionSeconds`:** the model-active span, from the first provider request to the last provider response of an observation.
- **`eventToCompletionSeconds`:** the time from the harness trigger to observed completion. It includes the 10-second inactivity window for automatic runs.
- **Phase key:** the ledger phase `flavor-strip` is reported as `flavor_strip` to keep the earlier summary layout.
