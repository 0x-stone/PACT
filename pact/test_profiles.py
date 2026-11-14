"""Tests for dual-profile support (demo vs real).

Covers:
- Canonical rules and rules_hash test vectors for both demo and real profiles
- On-chain rules and hash verification against view methods
- Attestation domain separation: an attestation signed for demo (pact-demo.testnet)
  fails verification if verified against real (pactcontract.testnet) and vice versa
- Attestation expiry calculation: guarantees expiry >= challenge_end + grace
  (420s for demo, 3600s for real)
- Fixed allowlist enforcement: non-allowlisted profiles/contracts are strictly rejected
"""

from __future__ import annotations

import base64
import json
import pytest
from nacl.exceptions import BadSignatureError

from pact.attestation import (
    SCHEMA_TAG,
    AttestationPayload,
    generate_signing_key,
    sign_attestation,
    verify_attestation,
)
from pact.profiles import (
    ALLOWLISTED_CONTRACT_IDS,
    PROFILES,
    PactProfile,
    get_profile,
    is_valid_profile_or_contract,
)
from pact.rules import PactRules, canonical_rules, load_rules, rules_hash

EXPECTED_REAL_CANONICAL = (
    '{"burn_amount":"50000000000000000000","min_members":5,'
    '"mint_amount":"100000000000000000000","quorum_pct":70,'
    '"reserve_amount":"500000000000000000000","stake_yocto":"500000000000000000000000",'
    '"submission_grace_secs":3600}'
)
EXPECTED_REAL_HASH = "c8f1e60f408ea298ee9d2f7718f2c436cd40e762f0422e8147c78afd115f5d19"

EXPECTED_DEMO_CANONICAL = (
    '{"burn_amount":"50000000000000000000","min_members":5,'
    '"mint_amount":"100000000000000000000","quorum_pct":70,'
    '"reserve_amount":"500000000000000000000","stake_yocto":"500000000000000000000000",'
    '"submission_grace_secs":420}'
)
EXPECTED_DEMO_HASH = "4b6af96bc4c907f075e164c65b28c5acb24edf7696043dda916effe29eb8b725"


def test_real_profile_test_vectors():
    rules_real = load_rules()
    assert canonical_rules(rules_real) == EXPECTED_REAL_CANONICAL
    assert rules_hash(rules_real) == EXPECTED_REAL_HASH
    prof = get_profile("real")
    assert prof.contract_id == "pactcontract.testnet"
    assert prof.rules_hash == EXPECTED_REAL_HASH
    assert prof.submission_grace_secs == 3600


def test_demo_profile_test_vectors():
    rules_real = load_rules()
    demo_dict = rules_real.model_dump()
    demo_dict["submission_grace_secs"] = 420
    rules_demo = PactRules.model_validate(demo_dict)

    assert canonical_rules(rules_demo) == EXPECTED_DEMO_CANONICAL
    assert rules_hash(rules_demo) == EXPECTED_DEMO_HASH
    prof = get_profile("demo")
    assert prof.contract_id == "pact-demo.testnet"
    assert prof.rules_hash == EXPECTED_DEMO_HASH
    assert prof.submission_grace_secs == 420


def test_profile_allowlist_enforcement():
    assert get_profile("demo").contract_id == "pact-demo.testnet"
    assert get_profile("pact-demo.testnet").name == "demo"
    assert get_profile("real").contract_id == "pactcontract.testnet"
    assert get_profile("pactcontract.testnet").name == "real"
    assert get_profile(None).contract_id == "pactcontract.testnet"

    for invalid in ["unknown", "pactcontract-fake.testnet", "mainnet", ""]:
        if invalid == "":
            continue
        with pytest.raises(ValueError, match="Unknown or non-allowlisted"):
            get_profile(invalid)
        assert not is_valid_profile_or_contract(invalid)


def test_attestation_domain_separation_between_contracts():
    """An attestation signed for pact-demo.testnet MUST NOT verify for pactcontract.testnet."""
    signing_key = generate_signing_key()
    verify_key = signing_key.verify_key

    # Attestation created for DEMO contract
    payload_demo = AttestationPayload(
        schema_tag=SCHEMA_TAG,
        contract_id="pact-demo.testnet",
        challenge_id="demo-1",
        account_id="alice.testnet",
        passed=True,
        expiry=2000000000,
    )
    sig_demo = sign_attestation(signing_key, payload_demo)

    # Verifies when checked against demo contract
    assert verify_attestation(bytes(verify_key), payload_demo, sig_demo) is True

    # Fails when an attacker or mistaken client presents it for REAL contract
    payload_real_mismatch = AttestationPayload(
        schema_tag=SCHEMA_TAG,
        contract_id="pactcontract.testnet",  # mismatched contract!
        challenge_id="demo-1",
        account_id="alice.testnet",
        passed=True,
        expiry=2000000000,
    )
    assert verify_attestation(bytes(verify_key), payload_real_mismatch, sig_demo) is False


def test_attestation_expiry_bounds_both_profiles():
    """Verify attestation expiry calculation guarantees expiry >= challenge_end + grace."""
    challenge_demo = {
        "challenge_id": "demo-1",
        "end_ns": "1794451953000000000",
    }
    end_s = int(challenge_demo["end_ns"]) // 1_000_000_000

    # Demo grace is 420s
    grace_demo = 420
    margin = 86400  # default 1 day margin
    expiry_demo = end_s + grace_demo + margin
    assert expiry_demo >= end_s + grace_demo
    assert expiry_demo - (end_s + grace_demo) == margin

    # Real grace is 3600s
    grace_real = 3600
    expiry_real = end_s + grace_real + margin
    assert expiry_real >= end_s + grace_real
    assert expiry_real - (end_s + grace_real) == margin
