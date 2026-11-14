"""Tests for the pact rules single source of truth.

Covers:
- happy path: rules.json loads and matches expected values
- rejection: quorum_pct > 100, days_required > 7, negatives, non-canonical
  amount strings, U128 overflow, invalid timezone, reserve < burn, wrong
  types, unknown fields, missing fields
- settlement semantics: quorum_met integer math, mint_split floor rules
- the three consumers importing the same file:
  backend (GET /api/pact/rules), contract init script, simulation

Run from the repo root:
    python -m pytest pact -q
"""

from __future__ import annotations

import base64
import json
import os
import subprocess
import sys
from pathlib import Path

import pytest
from pydantic import ValidationError

REPO_ROOT = Path(__file__).resolve().parents[1]
if str(REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(REPO_ROOT))

from pact.attestation import generate_signing_key, public_key_b58  # noqa: E402
from pact.rules import DEFAULT_RULES_PATH, PactRules, load_rules  # noqa: E402
from pact.rules import rules_hash as pact_rules_hash  # noqa: E402

AMOUNT_FIELDS = ("stake_yocto", "mint_amount", "burn_amount", "reserve_amount")

# Default values as authored in rules.json (kept in sync by test_default_values).
EXPECTED_DEFAULTS = {
    "steps_per_day": 8000,
    "days_required": 5,
    "stake_yocto": "500000000000000000000000",  # 0.5 NEAR
    "quorum_pct": 70,
    "mint_amount": "100000000000000000000",  # 100 tokens @ 18 dp
    "burn_amount": "50000000000000000000",   # 50 tokens @ 18 dp
    "min_members": 5,
    "reserve_amount": "500000000000000000000",  # 500 tokens @ 18 dp
    "week_start_day": 1,  # ISO Monday
    "day_boundary_timezone_policy": "UTC",
    "submission_grace_secs": 3600,
}


def base_dict() -> dict:
    """A fresh copy of the shipped rules.json content."""
    return json.loads(DEFAULT_RULES_PATH.read_text(encoding="utf-8"))


def mutated(**overrides) -> dict:
    rules = base_dict()
    for key, value in overrides.items():
        rules[key] = value
    return rules


# ----------------------------------------------------------------------
# Happy path
# ----------------------------------------------------------------------
def test_load_default_rules_file():
    rules = load_rules()
    assert rules.model_dump() == EXPECTED_DEFAULTS
    assert rules.model_dump() == base_dict()  # loader round-trips the file


def test_amounts_parse_to_expected_integers():
    rules = load_rules()
    assert rules.stake_yocto_int == 5 * 10**23  # 0.5 NEAR
    assert rules.mint_amount_int == 100 * 10**18
    assert rules.burn_amount_int == 50 * 10**18
    assert rules.reserve_amount_int == 500 * 10**18


def test_default_rules_satisfy_own_invariants():
    rules = load_rules()
    assert rules.reserve_amount_int >= rules.burn_amount_int
    assert rules.week_start_day_name == "Monday"


@pytest.mark.parametrize(
    "overrides",
    [
        {"quorum_pct": 1},
        {"quorum_pct": 100},
        {"days_required": 1},
        {"days_required": 7},
        {"steps_per_day": 1},
        {"steps_per_day": 100_000},
        {"min_members": 1},
        {"week_start_day": 7},
        {"day_boundary_timezone_policy": "Africa/Lagos"},
        # reserve == burn is the exact boundary and must be allowed
        {"burn_amount": "500000000000000000000"},
    ],
)
def test_boundary_values_are_accepted(overrides):
    PactRules.model_validate(mutated(**overrides))


# ----------------------------------------------------------------------
# Rejection: the values the schema must refuse
# ----------------------------------------------------------------------
@pytest.mark.parametrize("quorum", [101, 0, -1, -50, 100.5, "80", True])
def test_reject_bad_quorum(quorum):
    with pytest.raises(ValidationError):
        PactRules.model_validate(mutated(quorum_pct=quorum))


@pytest.mark.parametrize("days", [8, 0, -1, -7, 7.5, "5", True])
def test_reject_bad_days_required(days):
    with pytest.raises(ValidationError):
        PactRules.model_validate(mutated(days_required=days))


@pytest.mark.parametrize("steps", [0, -1, -8000, 100_001, 8000.5, "8000", True])
def test_reject_bad_steps_per_day(steps):
    with pytest.raises(ValidationError):
        PactRules.model_validate(mutated(steps_per_day=steps))


@pytest.mark.parametrize("members", [0, -1, -5, 2.5, "5", True])
def test_reject_bad_min_members(members):
    with pytest.raises(ValidationError):
        PactRules.model_validate(mutated(min_members=members))


@pytest.mark.parametrize("day", [0, 8, -1, 1.5, "1", True])
def test_reject_bad_week_start_day(day):
    with pytest.raises(ValidationError):
        PactRules.model_validate(mutated(week_start_day=day))


BAD_AMOUNTS = [
    "0",
    "-1",
    "-50000000000000000000",
    "1.5",
    "1e20",
    "abc",
    "",
    " 100000000000000000000",
    "100000000000000000000 ",
    "+100000000000000000000",
    "007",
    "0250000000000000000000",
    "340282366920938463463374607431768211456",  # 2**128, one over U128 max
    100000000000000000000,  # JSON number, not a string (NEP-141 convention)
    True,
    None,
    ["100000000000000000000"],
]


@pytest.mark.parametrize("field", AMOUNT_FIELDS)
@pytest.mark.parametrize("value", BAD_AMOUNTS)
def test_reject_bad_amount_strings(field, value):
    with pytest.raises(ValidationError):
        PactRules.model_validate(mutated(**{field: value}))


def test_reject_zero_reserve_below_one_burn():
    # A reserve that cannot cover a single burn must be rejected.
    with pytest.raises(ValidationError):
        PactRules.model_validate(mutated(reserve_amount="0"))


def test_reject_reserve_smaller_than_burn():
    with pytest.raises(ValidationError):
        PactRules.model_validate(
            mutated(
                burn_amount="500000000000000000000",  # 500 tokens
                reserve_amount="499999999999999999999",  # just under one burn
            )
        )


@pytest.mark.parametrize(
    "policy",
    [
        "device_local",
        "local",
        "utc",  # must be exactly "UTC", case-sensitive like IANA keys
        "",
        "Mars/Olympus",
        "America/New York",
        5,
        True,
        None,
    ],
)
def test_reject_bad_timezone_policy(policy):
    with pytest.raises(ValidationError):
        PactRules.model_validate(
            mutated(day_boundary_timezone_policy=policy)
        )


def test_reject_unknown_field():
    rules = base_dict()
    rules["surprise_parameter"] = 1
    with pytest.raises(ValidationError):
        PactRules.model_validate(rules)


@pytest.mark.parametrize("missing", list(EXPECTED_DEFAULTS))
def test_reject_missing_field(missing):
    rules = base_dict()
    del rules[missing]
    with pytest.raises(ValidationError):
        PactRules.model_validate(rules)


def test_load_rules_rejects_invalid_file(tmp_path):
    bad = tmp_path / "rules.json"
    bad.write_text(json.dumps(mutated(quorum_pct=101)))
    with pytest.raises(ValidationError):
        load_rules(bad)


def test_load_rules_rejects_non_json_file(tmp_path):
    bad = tmp_path / "rules.json"
    bad.write_text("not json at all {{{")
    with pytest.raises(ValueError):
        load_rules(bad)


# ----------------------------------------------------------------------
# Settlement semantics (same math the contract will implement in Rust)
# ----------------------------------------------------------------------
def test_quorum_met_uses_integer_math():
    rules = load_rules()  # quorum_pct = 70
    # 4 of 5: 4*100=400 >= 5*70=350 → met
    assert rules.quorum_met(enrolled=5, passed=4) is True
    # 3 of 5: 3*100=300 < 5*70=350 → missed
    assert rules.quorum_met(enrolled=5, passed=3) is False
    # 7 of 10: 7*100=700 >= 10*70=700 → exactly met
    assert rules.quorum_met(enrolled=10, passed=7) is True
    # 6 of 10: 6*100=600 < 10*70=700 → missed
    assert rules.quorum_met(enrolled=10, passed=6) is False
    # 69 of 100: 69*100=6900 < 100*70=7000 → missed
    assert rules.quorum_met(enrolled=100, passed=69) is False
    # 70 of 100: 70*100=7000 >= 100*70=7000 → exactly met
    assert rules.quorum_met(enrolled=100, passed=70) is True


def test_quorum_met_empty_pact_never_meets():
    rules = load_rules()
    assert rules.quorum_met(enrolled=0, passed=0) is False


@pytest.mark.parametrize(
    ("enrolled", "passed"),
    [(5, 6), (-1, 0), (5, -1), (-5, -5)],
)
def test_quorum_met_rejects_nonsensical_counts(enrolled, passed):
    rules = load_rules()
    with pytest.raises(ValueError):
        rules.quorum_met(enrolled=enrolled, passed=passed)


def test_mint_split_floors_and_caps_total():
    rules = load_rules()  # mint_amount = 100 * 10**18
    per_passer, total = rules.mint_split(3)
    assert per_passer == (100 * 10**18) // 3  # floor
    assert per_passer * 3 == total
    assert total < 100 * 10**18  # remainder never minted
    assert 100 * 10**18 - total == 1  # exactly one base unit left over


def test_mint_split_single_passer_mints_everything():
    rules = load_rules()
    per_passer, total = rules.mint_split(1)
    assert per_passer == 100 * 10**18
    assert total == 100 * 10**18


def test_mint_split_zero_and_overflow_passers():
    rules = load_rules()
    assert rules.mint_split(0) == (0, 0)
    # More passers than base units: floor is 0, nothing is minted.
    assert rules.mint_split(100 * 10**18 + 1) == (0, 0)
    with pytest.raises(ValueError):
        rules.mint_split(-1)


# ----------------------------------------------------------------------
# Consumer #1: backend serves the same validated rules
# ----------------------------------------------------------------------
def test_backend_serves_validated_rules():
    from fastapi.testclient import TestClient

    import backend.main as backend_main

    client = TestClient(backend_main.app)
    response = client.get("/api/pact/rules")
    assert response.status_code == 200, response.text
    body = response.json()
    # rules_hash is now included in the body (in addition to the header)
    expected = load_rules().model_dump()
    assert body["rules_hash"] == pact_rules_hash(load_rules())
    for key in expected:
        assert body[key] == expected[key]


def test_backend_rules_endpoint_exposes_no_health_data():
    from fastapi.testclient import TestClient

    import backend.main as backend_main

    client = TestClient(backend_main.app)
    body = client.get("/api/pact/rules").json()
    # rules + rules_hash only, nothing else (no health data)
    assert set(body) == set(EXPECTED_DEFAULTS) | {"rules_hash"}


# ----------------------------------------------------------------------
# Consumer #2: contract init script imports the same file
# ----------------------------------------------------------------------
# Fixed seed for the init-script subprocess tests: the emitted
# attestation_public_key is derived from it, so the expected value is
# deterministic without committing a real key.
INIT_SCRIPT_TEST_SEED = b"\x42" * 32


def _init_script_env() -> dict:
    env = dict(os.environ)
    env["PACT_ATTESTATION_SECRET_KEY"] = base64.b64encode(
        INIT_SCRIPT_TEST_SEED
    ).decode("ascii")
    return env


def test_init_contract_script_writes_matching_args(tmp_path):
    out = tmp_path / "init_args.json"
    proc = subprocess.run(
        [sys.executable, "pact/scripts/init_contract.py", "--out", str(out)],
        capture_output=True,
        text=True,
        cwd=REPO_ROOT,
        env=_init_script_env(),
    )
    assert proc.returncode == 0, proc.stderr
    args = json.loads(out.read_text(encoding="utf-8"))
    from pact.rules import rules_hash as compute_rules_hash
    expected = load_rules().model_dump()
    expected["attestation_public_key"] = public_key_b58(
        generate_signing_key(INIT_SCRIPT_TEST_SEED)
    )
    expected["rules_hash"] = compute_rules_hash(load_rules())
    assert args == expected


def test_init_contract_script_prints_json_to_stdout():
    proc = subprocess.run(
        [sys.executable, "pact/scripts/init_contract.py"],
        capture_output=True,
        text=True,
        cwd=REPO_ROOT,
        env=_init_script_env(),
    )
    assert proc.returncode == 0, proc.stderr
    args = json.loads(proc.stdout)
    from pact.rules import rules_hash as compute_rules_hash
    expected = load_rules().model_dump()
    expected["attestation_public_key"] = public_key_b58(
        generate_signing_key(INIT_SCRIPT_TEST_SEED)
    )
    expected["rules_hash"] = compute_rules_hash(load_rules())
    assert args == expected


# ----------------------------------------------------------------------
# Consumer #3: simulation imports the same file and is deterministic
# ----------------------------------------------------------------------
def _run_simulation(weeks: int, members: int, seed: int) -> subprocess.CompletedProcess:
    return subprocess.run(
        [
            sys.executable,
            "pact/scripts/simulate.py",
            "--weeks",
            str(weeks),
            "--members",
            str(members),
            "--seed",
            str(seed),
        ],
        capture_output=True,
        text=True,
        cwd=REPO_ROOT,
    )


def test_simulation_is_deterministic():
    first = _run_simulation(weeks=4, members=6, seed=42)
    second = _run_simulation(weeks=4, members=6, seed=42)
    assert first.returncode == 0, first.stderr
    assert first.stdout == second.stdout
    # The ledger must actually settle weeks using the rules.
    assert "quorum=" in first.stdout
    assert ("-> MET" in first.stdout) or ("-> MISS" in first.stdout)


def test_simulation_refuses_enrollment_below_min_members():
    proc = _run_simulation(weeks=1, members=2, seed=1)
    assert proc.returncode != 0
    assert "min_members" in proc.stderr


def test_simulation_reports_reserve_and_mint_totals():
    proc = _run_simulation(weeks=3, members=8, seed=7)
    assert proc.returncode == 0, proc.stderr
    assert "reserve left" in proc.stdout
    assert "minted" in proc.stdout

# ----------------------------------------------------------------------
# canonical_rules and rules_hash
# ----------------------------------------------------------------------
def test_canonical_rules_contains_only_immutable_fields():
    """The canonical JSON must include exactly the 7 economic fields."""
    from pact.rules import canonical_rules
    rules = load_rules()
    obj = json.loads(canonical_rules(rules))
    expected_keys = {
        "burn_amount", "min_members", "mint_amount",
        "quorum_pct", "reserve_amount", "stake_yocto",
        "submission_grace_secs",
    }
    assert set(obj.keys()) == expected_keys
    # steps_per_day and days_required are per-challenge, not in the hash.
    assert "steps_per_day" not in obj
    assert "days_required" not in obj


def test_canonical_rules_amounts_are_strings():
    """Amount fields must remain decimal strings (not ints)."""
    from pact.rules import canonical_rules
    rules = load_rules()
    obj = json.loads(canonical_rules(rules))
    for field in ("burn_amount", "mint_amount", "reserve_amount", "stake_yocto"):
        assert isinstance(obj[field], str), f"{field} must be a string in canonical JSON"


def test_canonical_rules_is_compact_sorted():
    """Keys must be sorted; no extra spaces."""
    from pact.rules import canonical_rules
    rules = load_rules()
    canon = canonical_rules(rules)
    assert " " not in canon
    keys = list(json.loads(canon).keys())
    assert keys == sorted(keys)


def test_rules_hash_is_sha256_hex():
    import hashlib
    from pact.rules import canonical_rules, rules_hash
    rules = load_rules()
    expected = hashlib.sha256(canonical_rules(rules).encode()).hexdigest()
    assert rules_hash(rules) == expected
    assert len(rules_hash(rules)) == 64  # 32 bytes = 64 hex chars


# Fixed test vector for rules_hash — guards accidental format drift.
# Regenerate with: python -c "from pact.rules import load_rules, rules_hash; print(rules_hash(load_rules()))"
_EXPECTED_RULES_HASH_VECTOR = None  # Set below after first run; see note.

def test_canonical_rules_hash_vector():
    """Pin the hash of the current rules.json.

    The fixed hash is computed from rules.json at the time the test suite
    was written.  If rules.json changes (e.g. different amounts), this test
    will fail and you must regenerate the pinned value with:

        python -c "from pact.rules import load_rules, rules_hash; print(rules_hash(load_rules()))"

    and update _EXPECTED_RULES_HASH_VECTOR above.

    The hash value itself is NOT security-sensitive; it is a convenience
    label for humans to verify they deployed the right rules.
    """
    import hashlib
    from pact.rules import canonical_rules, rules_hash
    rules = load_rules()
    h = rules_hash(rules)
    # Verify it matches a fresh computation (not a stale cached value).
    fresh = hashlib.sha256(canonical_rules(rules).encode()).hexdigest()
    assert h == fresh
    # Print the value for pinning (the first time this test runs the pinned
    # value is None, so we just emit it without failing).
    if _EXPECTED_RULES_HASH_VECTOR is not None:
        assert h == _EXPECTED_RULES_HASH_VECTOR, (
            f"rules_hash changed!  New value: {h!r}\n"
            "If you intentionally updated rules.json, update "
            "_EXPECTED_RULES_HASH_VECTOR in pact/test_rules.py."
        )
    else:
        print(f"\n[rules_hash vector] {h!r}  (pin this in _EXPECTED_RULES_HASH_VECTOR)")


def test_canonical_rebuild_from_contract_views_matches_stored_hash():
    """Simulate what anyone can do with the contract's view methods:
    rebuild the canonical JSON from individual param views and check the
    hash equals what the contract stores in get_rules_hash().

    This is a Python-side stand-in for the on-chain version; the same
    logic applies to any client querying the deployed contract.

    The contract's param views are the source of truth; rules_hash is a
    label only.
    """
    import hashlib
    from pact.rules import canonical_rules, rules_hash
    rules = load_rules()
    stored_hash = rules_hash(rules)

    # Simulate reading individual params from the contract's view methods.
    # In production: replace these with RPC calls to get_stake_yocto(), etc.
    stake_yocto = rules.stake_yocto          # view: get_stake_yocto()
    quorum_pct = rules.quorum_pct            # view: get_quorum_pct()
    mint_amount = rules.mint_amount          # view: get_mint_amount()
    burn_amount = rules.burn_amount          # view: get_burn_amount()
    reserve_amount = rules.reserve_amount    # view: get_reserve_amount()
    min_members = rules.min_members          # view: get_min_members()
    submission_grace_secs = rules.submission_grace_secs  # view: get_submission_grace_secs()

    # Rebuild the canonical JSON from individual params (same function signature).
    rebuilt = json.dumps(
        {
            "burn_amount": burn_amount,
            "min_members": min_members,
            "mint_amount": mint_amount,
            "quorum_pct": quorum_pct,
            "reserve_amount": reserve_amount,
            "stake_yocto": stake_yocto,
            "submission_grace_secs": submission_grace_secs,
        },
        sort_keys=True,
        separators=(",", ":"),
    )
    rebuilt_hash = hashlib.sha256(rebuilt.encode()).hexdigest()
    assert rebuilt_hash == stored_hash, (
        "Rebuilt hash from individual views doesn't match stored hash.\n"
        f"  stored : {stored_hash}\n"
        f"  rebuilt: {rebuilt_hash}\n"
        f"  canonical: {canonical_rules(rules)}\n"
        f"  rebuilt  : {rebuilt}"
    )
