"""Provision and inspect only the isolated Logos database; never dispatch inference.

The official webservice applies its own migrations. This bootstrap inserts local
configuration afterwards, and keeps secrets out of argv, generated reports and
terminal output. An OpenAI secret file is parsed as data, never sourced as shell.
"""

import json
import os
from pathlib import Path
import subprocess
import sys
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
STATE = ROOT / "build/atlas-logos-benchmark"
MODELS = ("gpt-5.6-luna",)
COMPOSE = ["docker", "compose", "--env-file", str(STATE / "stack.env"), "-f", str(ROOT / "docker/atlas-logos-benchmark/compose.yaml")]


def env_file(path):
    """Read literal KEY=value lines without executing substitutions."""
    values = {}
    for line in Path(path).read_text().splitlines():
        line = line.strip()
        if line.startswith("export "):
            line = line[7:]
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        values[key.strip()] = value.strip().strip("\"'")
    return values


def sql_literal(value):
    """Quote a PostgreSQL literal sent over stdin, never through shell argv."""
    return "'" + str(value).replace("'", "''") + "'"


def query(sql, quiet_error=False):
    """Use the fixed local Compose database and suppress errors containing SQL."""
    result = subprocess.run(
        COMPOSE + ["exec", "-T", "logos-db", "psql", "-X", "-qAt", "-v", "ON_ERROR_STOP=1", "-U", "postgres", "-d", "logosdb"],
        input=sql,
        text=True,
        capture_output=True,
    )
    if result.returncode:
        if quiet_error:
            return None
        raise RuntimeError("Local Logos SQL failed; inspect schema, not secret-bearing SQL output.")
    return result.stdout.strip()


def wait_schema():
    """Wait at most five minutes for official schema ownership to finish startup."""
    deadline = time.monotonic() + 300
    while time.monotonic() < deadline:
        result = query(
            "SELECT to_regclass('public.api_keys') IS NOT NULL AND to_regclass('public.databasechangeloglock') IS NOT NULL;", True
        )
        if result == "t" and query("SELECT NOT locked FROM databasechangeloglock WHERE id=1;", True) == "t":
            print("Logos schema ready")
            return
        time.sleep(3)
    raise RuntimeError("Logos webservice has not completed migrations within five minutes.")


def provision():
    """Register exactly the requested models and one OpenAI upstream in local Logos."""
    values = env_file(STATE / "stack.env")
    upstream = os.environ.get("OPENAI_API_KEY", "")
    if not upstream and os.environ.get("OPENAI_SECRET_FILE"):
        upstream = env_file(os.environ["OPENAI_SECRET_FILE"]).get("OPENAI_API_KEY", "")
    if not upstream:
        raise RuntimeError("Set OPENAI_SECRET_FILE to a local env file containing OPENAI_API_KEY.")
    # Read-only availability check: no completions, embeddings, or other billable calls.
    request = urllib.request.Request("https://api.openai.com/v1/models", headers={"Authorization": "Bearer " + upstream})
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            available = {item["id"] for item in json.load(response)["data"]}
    except urllib.error.HTTPError as error:
        raise RuntimeError(f"OpenAI model inventory failed with HTTP {error.code}; no inference dispatched.") from None
    missing = sorted(set(MODELS) - available)
    if missing:
        raise RuntimeError("OpenAI key cannot access requested models: " + ", ".join(missing))
    key = sql_literal(values["LOGOS_API_KEY"])
    secret = sql_literal(upstream)
    statements = [
        "BEGIN;",
        "INSERT INTO teams(name,default_cloud_rpm_limit,default_cloud_tpm_limit,"
        "default_monthly_budget_micro_cents,team_monthly_budget_micro_cents) "
        "SELECT 'atlas-thesis-local',1000,10000000,NULL,NULL WHERE NOT EXISTS "
        "(SELECT 1 FROM teams WHERE name='atlas-thesis-local');",
        f"INSERT INTO api_keys(key_value,name,key_type,team_id,environment,log,use_custom_permissions) "
        f"SELECT {key},'atlas-thesis-local','application',id,'atlas-thesis-local','BILLING',true "
        "FROM teams WHERE name='atlas-thesis-local' "
        "ON CONFLICT(key_value) DO UPDATE SET is_active=true,log='BILLING';",
        "INSERT INTO providers(name,base_url,provider_type,cloud_provider_type,privacy_level,"
        "auth_name,auth_format,api_key) SELECT 'atlas-openai','https://api.openai.com/v1',"
        f"'cloud','openai','CLOUD_NOT_IN_EU_BY_US_PROVIDER','Authorization','Bearer {{}}',{secret} "
        "WHERE NOT EXISTS(SELECT 1 FROM providers WHERE name='atlas-openai');",
        f"UPDATE providers SET api_key={secret} WHERE name='atlas-openai';",
    ]
    for model in MODELS:
        name = sql_literal(model)
        statements.extend(
            [
                f"INSERT INTO models(name,description) SELECT {name},'Pinned thesis configuration' "
                f"WHERE NOT EXISTS(SELECT 1 FROM models WHERE name={name});",
                "INSERT INTO model_provider(model_id,provider_id,endpoint) SELECT m.id,p.id,'chat/completions' "
                f"FROM models m,providers p WHERE m.name={name} AND p.name='atlas-openai' ON CONFLICT DO NOTHING;",
                "INSERT INTO api_key_model_permissions(api_key_id,model_id) SELECT a.id,m.id FROM api_keys a,models m "
                f"WHERE a.key_value={key} AND m.name={name} ON CONFLICT DO NOTHING;",
            ]
        )
    statements += [
        "INSERT INTO api_key_provider_permissions(api_key_id,provider_id) "
        f"SELECT a.id,p.id FROM api_keys a,providers p WHERE a.key_value={key} "
        "AND p.name='atlas-openai' ON CONFLICT DO NOTHING;",
        "COMMIT;",
    ]
    query("\n".join(statements))
    (STATE / "upstream-model-access.json").write_text(
        json.dumps(
            {
                "checkedAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
                "endpoint": "https://api.openai.com/v1/models",
                "models": list(MODELS),
                "inferenceDispatched": False,
            },
            indent=2,
        )
        + "\n"
    )
    print("Local Logos provider and model permissions configured; no inference dispatched.")


def models():
    """Verify authenticated local model access without inference."""
    request = urllib.request.Request(
        "http://127.0.0.1:18090/v1/models", headers={"Authorization": "Bearer " + env_file(STATE / "stack.env")["LOGOS_API_KEY"]}
    )
    with urllib.request.urlopen(request, timeout=30) as response:
        model_ids = sorted(item["id"] for item in json.load(response)["data"])
    print(json.dumps({"models": model_ids, "expectedAvailable": set(MODELS).issubset(model_ids)}))


def export(destination):
    """Export only request IDs, identities, timing, status and token counts."""
    rows = query(
        'SELECT row_to_json(r) FROM (SELECT l.id AS "logosId",l.request_id AS "requestId",'
        'm.name AS model,l.timestamp_request AS "startedAt",l.timestamp_response AS "endedAt",'
        'l.result_status AS status,p.name AS provider,l.cost_finalized AS "costFinalized",'
        "COALESCE((SELECT jsonb_object_agg(t.name,u.token_count) FROM usage_tokens u JOIN token_types t "
        "ON t.id=u.type_id WHERE u.log_entry_id=l.id),'{}'::jsonb) AS usage FROM log_entry l "
        "LEFT JOIN models m ON m.id=l.model_id LEFT JOIN providers p ON p.id=l.provider_id "
        "JOIN api_keys a ON a.id=l.api_key_id WHERE a.name='atlas-thesis-local' ORDER BY l.id) r;"
    )
    path = Path(destination)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(rows + ("\n" if rows else ""))
    print(f"Exported {len(rows.splitlines())} Logos records to {path}")


if __name__ == "__main__":
    try:
        command = sys.argv[1]
        if command == "export":
            export(sys.argv[2])
        else:
            {"wait-schema": wait_schema, "provision": provision, "models": models}[command]()
    except (RuntimeError, urllib.error.URLError, KeyError, IndexError) as error:
        print(f"Setup blocked: {error}", file=sys.stderr)
        sys.exit(1)
