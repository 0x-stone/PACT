"""Endpoint + unit tests for NEAR sign-in (NEP-413) and settlement
attestations.

Run with pytest (plugin autoload disabled for the same reason as the other
suites — a globally installed plugin pulls in a broken scipy):

    cd backend
    PYTEST_DISABLE_PLUGIN_AUTOLOAD=1 python3 -m pytest -q test_auth.py

Done criteria under test:
- fresh install flow: /api/auth/nonce -> NEP-413 signature -> /api/auth/verify
  -> session token -> health data attributed to the NEAR account
- replay attacks rejected (single-use nonces), expired nonces/sessions
  rejected, nonce-account binding enforced, Full Access Key required
- the attestation endpoint only ever issues the signed-in member's own
  attestation, and the signature verifies against the public key endpoint
"""

from __future__ import annotations

import base64
import hashlib
import os
import secrets
import sys
import tempfile
from datetime import date, datetime, timedelta, timezone
from pathlib import Path

import pytest

# Isolated databases + fixed attestation key BEFORE importing the app.
# (storage.py freezes its DB path at first import; the other stores read the
# env per connection, so every test file sets all of them.)
TEST_DIR = Path(tempfile.mkdtemp(prefix="health_sync_auth_tests_"))
os.environ["HEALTH_SYNC_DB"] = str(TEST_DIR / "health_sync_test.db")
os.environ["HEALTH_SYNC_PACT_DB"] = str(TEST_DIR / "pact_test.db")
os.environ["HEALTH_SYNC_AUTH_DB"] = str(TEST_DIR / "auth_test.db")
os.environ["HEALTH_SYNC_API_KEY"] = ""
os.environ["PACT_CONTRACT_ID"] = "pactcontract.testnet"
TEST_ATTESTATION_SEED = b"\x42" * 32  # fixed, obviously not a real key
os.environ["PACT_ATTESTATION_SECRET_KEY"] = base64.b64encode(
    TEST_ATTESTATION_SEED
).decode("ascii")

BACKEND_DIR = Path(__file__).resolve().parent
REPO_ROOT = BACKEND_DIR.parent  # pact/ lives here
sys.path.insert(0, str(BACKEND_DIR))
sys.path.insert(0, str(REPO_ROOT))

import support  # noqa: E402  (must come after the env setup above)
from pact.attestation import (  # noqa: E402
    AttestationPayload,
    generate_signing_key,
    public_key_b58,
    verify_attestation,
)
from pact.encoding import b58decode, b58encode  # noqa: E402

client = support.client
MONDAY = support.LAST_COMPLETED_WEEK_START
CURRENT_MONDAY = MONDAY + timedelta(days=7)  # this week (in progress)

# A REAL captured rpc.testnet.near.org view_access_key_list response entry
# (account opaque-hwprobe-1f7a3ef8.testnet, captured 2026-11-11 while
# verifying the account-helper flow live). Pins the wire shape the fake
# chain imitates and the parser must accept.
CAPTURED_TESTNET_KEYS = [
    {
        "public_key": "ed25519:8jeR6UUXfcBZh8pfoNERJVaffCNBsp8QKzagoj5aiobW",
        "access_key": {"nonce": 277138325000000, "permission": "FullAccess"},
    }
]


def sync_payload(device_id: str = "test-device-1", count: int = 2) -> dict:
    """A minimal but valid Health Connect sync payload."""
    records = [
        {
            "recordType": "StepsRecord",
            "startTime": "2026-11-09T10:00:00Z",
            "endTime": "2026-11-09T10:30:00Z",
            "recordData": {"count": 1000, "unit": "count"},
            "metadata": {
                "id": f"steps-id-{i:03d}",
                "dataOrigin": "com.google.android.apps.fitness",
            },
        }
        for i in range(count)
    ]
    return {
        "deviceId": device_id,
        "syncTimestamp": "2026-11-09T23:00:00Z",
        "summary": {"StepsRecord": count},
        "records": records,
    }


def attestation_url(challenge_ref) -> str:
    return f"/api/pact/attestation/{challenge_ref}"


@pytest.fixture(autouse=True)
def clean_state():
    """Every test starts from empty stores (uploads, pact, auth)."""
    import auth_store
    import pact_store
    import storage

    auth_store.clear_auth_db()
    pact_store.clear_pact_db()
    storage.clear_uploads()
    yield


# ---------------------------------------------------------------------------
# near_auth unit level (pure functions)
# ---------------------------------------------------------------------------

def test_nep413_payload_bytes_are_pinned():
    """Exact Borsh layout of the NEP-413 payload, tag-prefixed."""
    import near_auth

    nonce = bytes(range(32))
    payload = near_auth.nep413_payload("hi", nonce, "opaque-pact")
    expected = (
        (2**31 + 413).to_bytes(4, "little")  # tag: invalid-Borsh-transaction prefix
        + (2).to_bytes(4, "little") + b"hi"  # message: String
        + nonce  # nonce: [u8; 32]
        + (11).to_bytes(4, "little") + b"opaque-pact"  # recipient: String
        + b"\x00"  # callbackUrl: Option::None
    )
    assert payload == expected


def test_nep413_payload_with_callback_url():
    import near_auth

    nonce = bytes(32)
    payload = near_auth.nep413_payload("m", nonce, "r", callback_url="https://x/y")
    expected = (
        (2**31 + 413).to_bytes(4, "little")
        + (1).to_bytes(4, "little") + b"m"
        + nonce
        + (1).to_bytes(4, "little") + b"r"
        + b"\x01"
        + (11).to_bytes(4, "little") + b"https://x/y"
    )
    assert payload == expected


def test_nep413_signature_is_over_the_sha256_hash():
    """NEAR convention: Ed25519 signs the 32-byte SHA-256 of the payload."""
    import near_auth

    nonce = secrets.token_bytes(32)
    digest = near_auth.nep413_hash("msg", nonce, "opaque-pact")
    assert len(digest) == 32
    assert digest == hashlib.sha256(
        near_auth.nep413_payload("msg", nonce, "opaque-pact")
    ).digest()


@pytest.mark.parametrize(
    "account_id",
    [
        "alice.testnet",
        "a.testnet",
        "bob_t.near",
        "carol-x.near",
        "123456.testnet",
        "app.alice.testnet",
    ],
)
def test_valid_account_ids_accepted(account_id):
    import near_auth

    near_auth.validate_account_id(account_id)  # no raise


@pytest.mark.parametrize(
    "account_id",
    [
        "a",  # too short
        "ALICE.testnet",  # uppercase
        ".alice.testnet",  # leading separator
        "alice..testnet",  # double separator
        "alice-.testnet",  # trailing separator in label
        "ali ce.testnet",  # space
        "al ice.testnet",
        "x" * 65,  # too long
        "",  # empty
        123,  # not a string
    ],
)
def test_invalid_account_ids_rejected(account_id):
    import near_auth

    with pytest.raises(ValueError):
        near_auth.validate_account_id(account_id)


def test_parse_public_key_formats():
    import near_auth

    raw = bytes.fromhex("aa" * 32)
    wire = "ed25519:" + b58encode(raw)
    assert near_auth.parse_public_key(wire) == raw
    with pytest.raises(ValueError):  # missing ed25519: prefix
        near_auth.parse_public_key(b58encode(raw))
    with pytest.raises(ValueError):  # wrong decoded length
        near_auth.parse_public_key("ed25519:" + b58encode(b"\x01" * 31))
    with pytest.raises(ValueError):  # garbage base58
        near_auth.parse_public_key("ed25519:0OIl")


def test_real_captured_testnet_wire_shape():
    """The real RPC entry (FullAccess) is recognized; restricted keys are not."""
    import near_auth

    captured_pk = CAPTURED_TESTNET_KEYS[0]["public_key"]
    assert near_auth.is_full_access_key(CAPTURED_TESTNET_KEYS, captured_pk)

    restricted = support.function_call_key(captured_pk)
    assert not near_auth.is_full_access_key([restricted], captured_pk)

    # A key list can hold several keys; each is judged by its own permission.
    other_pk = "ed25519:" + b58encode(bytes(support.SigningKey.generate().verify_key))
    mixed = [support.function_call_key(other_pk), support.full_access_key(captured_pk)]
    assert near_auth.is_full_access_key(mixed, captured_pk)
    assert not near_auth.is_full_access_key(mixed, other_pk)


# ---------------------------------------------------------------------------
# POST /api/auth/nonce
# ---------------------------------------------------------------------------

def test_nonce_issuance_shape():
    import near_auth
    import main as main_module

    response = client.post("/api/auth/nonce", json={"accountId": "alice.testnet"})
    assert response.status_code == 200, response.text
    body = response.json()
    assert set(body.keys()) == {"accountId", "nonce", "message", "recipient", "expiresAt"}
    assert body["accountId"] == "alice.testnet"
    nonce = base64.b64decode(body["nonce"], validate=True)
    assert len(nonce) == 32  # NEP-413 nonces are exactly 32 bytes
    assert body["message"] == near_auth.SIGN_IN_MESSAGE
    assert body["recipient"] == main_module.AUTH_DOMAIN
    expires = datetime.fromisoformat(body["expiresAt"])
    now = datetime.now(timezone.utc)
    assert now < expires <= now + timedelta(minutes=5)  # 5-minute TTL


def test_nonce_for_invalid_account_is_rejected():
    """Malformed account ids never get a challenge. Pydantic's length bounds
    answer 422; the account-id shape checks in the handler answer 400 —
    both mean 'no nonce for you'."""
    for bad in ("A", "alice..testnet", ".bob", "x" * 65):
        response = client.post("/api/auth/nonce", json={"accountId": bad})
        assert response.status_code in (400, 422), (bad, response.status_code)


def test_two_nonces_differ():
    first = client.post("/api/auth/nonce", json={"accountId": "alice.testnet"})
    second = client.post("/api/auth/nonce", json={"accountId": "alice.testnet"})
    assert first.json()["nonce"] != second.json()["nonce"]


def test_spec_auth_path_aliases_match_api_prefix():
    """The spec names /auth/nonce and /auth/verify; the app also serves
    /api/auth/* (the mobile client). Both must mint equivalent challenges."""
    prefixed = client.post("/api/auth/nonce", json={"accountId": "alice.testnet"})
    aliased = client.post("/auth/nonce", json={"accountId": "alice.testnet"})
    assert prefixed.status_code == aliased.status_code == 200
    assert prefixed.json()["message"] == aliased.json()["message"]
    assert prefixed.json()["recipient"] == aliased.json()["recipient"]
    assert prefixed.json()["nonce"] != aliased.json()["nonce"]


# ---------------------------------------------------------------------------
# POST /api/auth/verify — the sign-in itself
# ---------------------------------------------------------------------------

def test_full_signin_via_spec_path_aliases():
    """A nonce from /auth/nonce verifies at /auth/verify (the spec names)."""
    key = support.SigningKey.generate()
    public_key = "ed25519:" + b58encode(bytes(key.verify_key))
    with support.fake_chain({"alice.testnet": [support.full_access_key(public_key)]}):
        challenge = client.post("/auth/nonce", json={"accountId": "alice.testnet"})
        assert challenge.status_code == 200, challenge.text
        body = support.nep413_sign(key, challenge.json())
        response = client.post("/auth/verify", json=body)
        assert response.status_code == 200, response.text
        assert response.json()["accountId"] == "alice.testnet"
        headers = {"Authorization": f"Bearer {response.json()['token']}"}
        sync = client.post("/api/health/sync", json=sync_payload(), headers=headers)
        assert sync.status_code == 200
        assert sync.json()["accountId"] == "alice.testnet"


def test_full_signin_and_session_lifecycle():
    with support.signed_in("alice.testnet") as session:
        assert session["account"] == "alice.testnet"
        assert session["token"]
        assert session["publicKey"].startswith("ed25519:")
        # The session authorizes attributed uploads immediately.
        response = client.post(
            "/api/health/sync", json=sync_payload(), headers=session["headers"]
        )
        assert response.status_code == 200, response.text
        assert response.json()["accountId"] == "alice.testnet"


def test_replayed_verify_is_rejected():
    """The same nonce+signature can sign in exactly once (replay protection)."""
    key = support.SigningKey.generate()
    public_key = "ed25519:" + b58encode(bytes(key.verify_key))
    with support.fake_chain({"alice.testnet": [support.full_access_key(public_key)]}):
        challenge = support.request_nonce("alice.testnet")
        body = support.nep413_sign(key, challenge)
        first = client.post("/api/auth/verify", json=body)
        assert first.status_code == 200, first.text
        replay = client.post("/api/auth/verify", json=body)
        assert replay.status_code == 401
        assert "nonce" in replay.json()["detail"]


def test_expired_nonce_is_rejected():
    with support.fake_chain({"alice.testnet": []}):
        challenge = support.request_nonce("alice.testnet")
        support.force_expire_nonce(challenge["nonce"])
        key = support.SigningKey.generate()
        body = support.nep413_sign(key, challenge)
        response = client.post("/api/auth/verify", json=body)
        assert response.status_code == 401


def test_unknown_nonce_is_rejected():
    key = support.SigningKey.generate()
    public_key = "ed25519:" + b58encode(bytes(key.verify_key))
    forged_nonce = base64.b64encode(secrets.token_bytes(32)).decode()
    challenge = {
        "accountId": "alice.testnet",
        "nonce": forged_nonce,
        "message": "Sign in to the Opaque health pact",
        "recipient": "opaque-pact",
    }
    with support.fake_chain({"alice.testnet": [support.full_access_key(public_key)]}):
        body = support.nep413_sign(key, challenge)
        response = client.post("/api/auth/verify", json=body)
        assert response.status_code == 401


def test_wrong_signature_is_rejected():
    """A signature made by a different key than the claimed publicKey."""
    signer = support.SigningKey.generate()  # actually signs
    claimed = support.SigningKey.generate()  # whose public key is claimed
    claimed_pk = "ed25519:" + b58encode(bytes(claimed.verify_key))
    with support.fake_chain({"alice.testnet": [support.full_access_key(claimed_pk)]}):
        challenge = support.request_nonce("alice.testnet")
        signed = support.nep413_sign(signer, challenge)  # signed by `signer`
        signed["publicKey"] = claimed_pk  # but attributed to `claimed`
        response = client.post("/api/auth/verify", json=signed)
        assert response.status_code == 401
        assert "signature" in response.json()["detail"]


def test_nonce_is_bound_to_its_account():
    """A nonce issued for alice cannot sign bob in, even with a valid
    signature over the challenge by bob's own Full Access Key."""
    bob = support.SigningKey.generate()
    bob_pk = "ed25519:" + b58encode(bytes(bob.verify_key))
    chain = {"alice.testnet": [], "bob.testnet": [support.full_access_key(bob_pk)]}
    with support.fake_chain(chain):
        challenge = support.request_nonce("alice.testnet")  # issued for alice
        body = support.nep413_sign(bob, challenge)  # bob signs alice's challenge
        body["accountId"] = "bob.testnet"  # ...and claims to be bob
        response = client.post("/api/auth/verify", json=body)
        assert response.status_code == 401
        assert "different account" in response.json()["detail"]


def test_function_call_key_is_rejected():
    """NEP-413 requires a Full Access Key; restricted keys authenticate no
    one, even though they are real keys of the account."""
    key = support.SigningKey.generate()
    public_key = "ed25519:" + b58encode(bytes(key.verify_key))
    chain = {"alice.testnet": [support.function_call_key(public_key)]}
    with support.fake_chain(chain):
        challenge = support.request_nonce("alice.testnet")
        body = support.nep413_sign(key, challenge)
        response = client.post("/api/auth/verify", json=body)
        assert response.status_code == 401
        assert "Full Access Key" in response.json()["detail"]


def test_key_not_on_account_is_rejected():
    """The signing key is not among the account's keys at all."""
    signer = support.SigningKey.generate()
    other_pk = "ed25519:" + b58encode(bytes(support.SigningKey.generate().verify_key))
    with support.fake_chain({"alice.testnet": [support.full_access_key(other_pk)]}):
        challenge = support.request_nonce("alice.testnet")
        body = support.nep413_sign(signer, challenge)
        response = client.post("/api/auth/verify", json=body)
        assert response.status_code == 401
        assert "Full Access Key" in response.json()["detail"]


def test_account_not_found_is_401():
    with support.fake_chain({}):  # the fake chain knows no accounts
        challenge = support.request_nonce("ghost.testnet")
        key = support.SigningKey.generate()
        body = support.nep413_sign(key, challenge)
        response = client.post("/api/auth/verify", json=body)
        assert response.status_code == 401


def test_rpc_failure_is_502():
    import near_auth

    original = near_auth.fetch_access_keys

    def broken(account_id, rpc_url=None):
        raise near_auth.NearRpcError("node unreachable")

    near_auth.fetch_access_keys = broken
    try:
        challenge = support.request_nonce("alice.testnet")
        key = support.SigningKey.generate()
        body = support.nep413_sign(key, challenge)
        response = client.post("/api/auth/verify", json=body)
        assert response.status_code == 502
        assert "RPC" in response.json()["detail"]
    finally:
        near_auth.fetch_access_keys = original


def test_malformed_verify_inputs_are_400():
    with support.fake_chain({"alice.testnet": []}):
        challenge = support.request_nonce("alice.testnet")
        key = support.SigningKey.generate()
        good = support.nep413_sign(key, challenge)

        # publicKey without the ed25519: prefix
        bad = dict(good, publicKey=b58encode(bytes(key.verify_key)))
        assert client.post("/api/auth/verify", json=bad).status_code == 400
        # non-base64 signature
        bad = dict(good, signature="!!!not-base64!!!")
        assert client.post("/api/auth/verify", json=bad).status_code == 400
        # nonce that is not 32 bytes
        bad = dict(good, nonce=base64.b64encode(b"\x01" * 16).decode())
        assert client.post("/api/auth/verify", json=bad).status_code == 400
        # signature that is not 64 bytes
        bad = dict(good, signature=base64.b64encode(b"\x01" * 63).decode())
        assert client.post("/api/auth/verify", json=bad).status_code == 400
        # invalid accountId shape
        bad = dict(good, accountId="BAD.ACCOUNT")
        assert client.post("/api/auth/verify", json=bad).status_code == 400


# ---------------------------------------------------------------------------
# Sessions: attribution + scoping of health data
# ---------------------------------------------------------------------------

def test_sync_requires_a_session():
    response = client.post("/api/health/sync", json=sync_payload())
    assert response.status_code == 401
    assert "session" in response.json()["detail"]
    response = client.post(
        "/api/health/sync",
        json=sync_payload(),
        headers={"Authorization": "Bearer not-a-real-token"},
    )
    assert response.status_code == 401
    response = client.post(
        "/api/health/sync",
        json=sync_payload(),
        headers={"Authorization": "Basic dXNlcjpwYXNz"},
    )
    assert response.status_code == 401


def test_expired_session_is_rejected():
    with support.signed_in("alice.testnet") as session:
        support.force_expire_session(session["token"])
        response = client.post(
            "/api/health/sync", json=sync_payload(), headers=session["headers"]
        )
        assert response.status_code == 401


def test_uploads_are_attributed_and_scoped_per_account():
    with support.accounts_signed_in("alice.testnet", "bob.testnet") as sessions:
        alice, bob = sessions["alice.testnet"], sessions["bob.testnet"]
        first = client.post(
            "/api/health/sync", json=sync_payload("alice-dev"), headers=alice["headers"]
        ).json()
        second = client.post(
            "/api/health/sync", json=sync_payload("bob-dev"), headers=bob["headers"]
        ).json()
        assert first["accountId"] == "alice.testnet"
        assert second["accountId"] == "bob.testnet"

        # Each member sees only their own uploads.
        listing = client.get("/api/health/uploads", headers=bob["headers"]).json()
        assert [u["accountId"] for u in listing["uploads"]] == ["bob.testnet"]
        assert [u["uploadId"] for u in listing["uploads"]] == [second["uploadId"]]

        # Another member's upload id is indistinguishable from a missing one.
        assert (
            client.get(
                f"/api/health/uploads/{first['uploadId']}", headers=bob["headers"]
            ).status_code
            == 404
        )
        assert (
            client.delete(
                f"/api/health/uploads/{first['uploadId']}", headers=bob["headers"]
            ).status_code
            == 404
        )

        # Flattened records and stats are scoped too.
        records = client.get("/api/health/records", headers=bob["headers"]).json()
        assert records["count"] == 2  # bob's payload had 2 records
        stats = client.get("/api/health/stats", headers=bob["headers"]).json()
        assert stats["uploads"] == 1

        # bob's confirm=true clear removes only bob's uploads.
        cleared = client.delete(
            "/api/health/uploads?confirm=true", headers=bob["headers"]
        ).json()
        assert cleared["deletedCount"] == 1
        assert (
            client.get(
                f"/api/health/uploads/{first['uploadId']}", headers=alice["headers"]
            ).status_code
            == 200
        )  # alice's data survived


def test_uploads_requires_session():
    """Unauthenticated GET /api/health/uploads is rejected with 401."""
    response = client.get("/api/health/uploads")
    assert response.status_code == 401
    assert "requires a session" in response.json()["detail"]


def test_admin_path_sees_all_uploads():
    """Admin path /api/admin/health/uploads: unscoped listing across accounts."""
    with support.accounts_signed_in("alice.testnet", "bob.testnet") as sessions:
        for member in ("alice.testnet", "bob.testnet"):
            client.post(
                "/api/health/sync",
                json=sync_payload(),
                headers=sessions[member]["headers"],
            )
        listing = client.get("/api/admin/health/uploads").json()
        assert listing["count"] == 2
        assert {u["accountId"] for u in listing["uploads"]} == {
            "alice.testnet",
            "bob.testnet",
        }


def test_admin_uploads_rejects_normal_user_session():
    """A regular member's Bearer token must be rejected by /api/admin/* endpoints."""
    with support.signed_in("alice.testnet") as session:
        response = client.get("/api/admin/health/uploads", headers=session["headers"])
        assert response.status_code == 403
        assert "cannot access admin endpoints" in response.json()["detail"]

        response_item = client.get("/api/admin/health/uploads/some-upload-id", headers=session["headers"])
        assert response_item.status_code == 403


# ---------------------------------------------------------------------------
# Pact endpoints under sessions
# ---------------------------------------------------------------------------

def test_pact_eligibility_requires_session():
    response = client.post("/api/pact/eligibility", json={"records": []})
    assert response.status_code == 401


def test_pact_member_is_the_signed_in_account():
    with support.signed_in("alice.testnet") as session:
        response = support.ingest_pact(session, "normal_week")
        assert response.status_code == 200, response.text
        body = response.json()
        assert body["memberId"] == "alice.testnet"
        assert set(body.keys()) == {
            "memberId", "challengeId", "weekStart", "daysCompleted", "passed", "flags"
        }


def test_pact_cannot_impersonate_another_member():
    """A stale memberId field in the body is ignored — identity comes from
    the session, never from client claims."""
    with support.signed_in("alice.testnet") as session:
        body = {
            "memberId": "mallory.testnet",  # ignored
            "records": support.shifted_records("normal_week"),
            "weekStart": MONDAY.isoformat(),
        }
        response = client.post(
            "/api/pact/eligibility", json=body, headers=session["headers"]
        )
        assert response.status_code == 200
        assert response.json()["memberId"] == "alice.testnet"
        community = client.get("/api/pact/community").json()
        assert community["enrolled"] == 1  # only alice, no mallory row


def test_pact_verdicts_stay_per_member():
    with support.accounts_signed_in("alice.testnet", "bob.testnet") as sessions:
        alice = support.ingest_pact(sessions["alice.testnet"], "normal_week")
        bob = support.ingest_pact(sessions["bob.testnet"], "partial_week")
        assert alice.json()["daysCompleted"] == 7
        assert bob.json()["daysCompleted"] == 3  # bob sees only bob's verdict


# ---------------------------------------------------------------------------
# Settlement attestations
# ---------------------------------------------------------------------------

def test_attestation_key_endpoint():
    response = client.get("/api/pact/attestation-key")
    assert response.status_code == 200
    body = response.json()
    assert set(body.keys()) == {
        "publicKey", "algorithm", "schemaTag", "payloadFormat", "contractId"
    }
    # Deterministic from the fixed test seed, so the vector/key never drift.
    expected = public_key_b58(generate_signing_key(TEST_ATTESTATION_SEED))
    assert body["publicKey"] == expected
    assert body["algorithm"] == "ed25519"
    assert body["payloadFormat"] == "borsh"
    assert body["contractId"] == "pactcontract.testnet"


def test_attestation_happy_path_verifies_against_published_key():
    with support.signed_in("alice.testnet") as session:
        ingest = support.ingest_pact(session, "normal_week")
        assert ingest.status_code == 200
        assert ingest.json()["passed"] is True

        response = client.get(
            attestation_url(MONDAY.isoformat()), headers=session["headers"]
        )
        assert response.status_code == 200, response.text
        att = response.json()
        assert set(att.keys()) == {
            "schemaTag", "contractId", "challengeId", "accountId",
            "passed", "expiry", "expiresAt", "signature", "publicKey",
        }

        # Payload fields are exactly the member/week verdict...
        assert att["schemaTag"] == "OPAQUE-PACT-ATTESTATION-V1"
        assert att["contractId"] == "pactcontract.testnet"
        assert att["challengeId"] == MONDAY.isoformat()
        assert att["accountId"] == "alice.testnet"
        assert att["passed"] is True
        expected_expiry = int(
            datetime.combine(
                MONDAY + timedelta(days=14), datetime.min.time(), tzinfo=timezone.utc
            ).timestamp()
        )
        assert att["expiry"] == expected_expiry

        # ...and the signature verifies with the PUBLISHED key (offline).
        published = client.get("/api/pact/attestation-key").json()["publicKey"]
        payload = AttestationPayload(
            schema_tag=att["schemaTag"],
            contract_id=att["contractId"],
            challenge_id=att["challengeId"],
            account_id=att["accountId"],
            passed=att["passed"],
            expiry=att["expiry"],
        )
        signature = base64.b64decode(att["signature"], validate=True)
        assert len(signature) == 64
        public_key = b58decode(published.removeprefix("ed25519:"))
        assert verify_attestation(public_key, payload, signature, now=att["expiry"] - 1)
        # And an expired clock rejects it (freshness semantics).
        assert not verify_attestation(public_key, payload, signature, now=att["expiry"])


def test_attestation_tampered_fields_do_not_verify():
    with support.signed_in("alice.testnet") as session:
        assert support.ingest_pact(session, "normal_week").status_code == 200
        att = client.get(
            attestation_url(MONDAY.isoformat()), headers=session["headers"]
        ).json()
        published = client.get("/api/pact/attestation-key").json()["publicKey"]
        public_key = b58decode(published.removeprefix("ed25519:"))
        signature = base64.b64decode(att["signature"])
        base = dict(att)

        def verify_with(**overrides):
            fields = {
                "schemaTag": base["schemaTag"],
                "contractId": base["contractId"],
                "challengeId": base["challengeId"],
                "accountId": base["accountId"],
                "passed": base["passed"],
                "expiry": base["expiry"],
            }
            fields.update(overrides)
            payload = AttestationPayload(
                schema_tag=fields["schemaTag"],
                contract_id=fields["contractId"],
                challenge_id=fields["challengeId"],
                account_id=fields["accountId"],
                passed=fields["passed"],
                expiry=fields["expiry"],
            )
            return verify_attestation(public_key, payload, signature)

        assert verify_with()  # sanity: the untampered one verifies
        assert not verify_with(passed=not base["passed"])  # flipped verdict
        assert not verify_with(accountId="mallory.testnet")  # other member
        assert not verify_with(challengeId="2026-11-16")  # other week
        assert not verify_with(contractId="other-pact.testnet")  # other contract
        assert not verify_with(expiry=base["expiry"] + 1)  # stretched window


def test_attestation_for_member_without_verdict_is_404():
    with support.accounts_signed_in("alice.testnet", "bob.testnet") as sessions:
        assert support.ingest_pact(sessions["alice.testnet"], "normal_week").status_code == 200
        # bob never ingested that week: no verdict, no attestation.
        response = client.get(
            attestation_url(MONDAY.isoformat()),
            headers=sessions["bob.testnet"]["headers"],
        )
        assert response.status_code == 404
        assert "no stored verdict" in response.json()["detail"]


def test_attestation_for_unknown_week_is_404():
    with support.signed_in("alice.testnet") as session:
        response = client.get(
            attestation_url("2020-01-06"), headers=session["headers"]
        )
        assert response.status_code == 404
        assert "no pact challenge" in response.json()["detail"]


def test_attestation_for_in_progress_week_is_409():
    with support.signed_in("alice.testnet") as session:
        # Enroll in the CURRENT week (still in progress).
        response = client.post(
            "/api/pact/eligibility",
            json={"records": [], "weekStart": CURRENT_MONDAY.isoformat()},
            headers=session["headers"],
        )
        assert response.status_code == 200, response.text
        response = client.get(
            attestation_url(CURRENT_MONDAY.isoformat()), headers=session["headers"]
        )
        assert response.status_code == 409
        assert "in progress" in response.json()["detail"]


def test_attestation_after_settlement_window_is_410():
    with support.signed_in("alice.testnet") as session:
        # The week BEFORE the last completed one: its 7-day settlement
        # window closed at (that Monday + 14d) = this week's Monday.
        stale_monday = MONDAY - timedelta(days=7)
        response = support.ingest_pact(session, "normal_week", week_start=stale_monday)
        assert response.status_code == 200, response.text
        response = client.get(
            attestation_url(stale_monday.isoformat()), headers=session["headers"]
        )
        assert response.status_code == 410
        assert "settlement window" in response.json()["detail"]


def test_attestation_spec_path_alias():
    """GET /attestation/{challenge_id} is the spec name; same bytes as /api/pact."""
    with support.signed_in("alice.testnet") as session:
        assert support.ingest_pact(session, "normal_week").status_code == 200
        week = MONDAY.isoformat()
        prefixed = client.get(attestation_url(week), headers=session["headers"])
        aliased = client.get(f"/attestation/{week}", headers=session["headers"])
        assert prefixed.status_code == aliased.status_code == 200
        assert prefixed.json() == aliased.json()
        key_prefixed = client.get("/api/pact/attestation-key").json()
        key_aliased = client.get("/attestation-key").json()
        assert key_prefixed == key_aliased


def test_attestation_requires_session():
    response = client.get(attestation_url(MONDAY.isoformat()))
    assert response.status_code == 401


def test_attestation_accepts_numeric_challenge_id():
    import pact_store

    with support.signed_in("alice.testnet") as session:
        assert support.ingest_pact(session, "normal_week").status_code == 200
        challenge = pact_store.get_challenge_by_week_start(MONDAY.isoformat())
        assert challenge is not None
        by_date = client.get(
            attestation_url(MONDAY.isoformat()), headers=session["headers"]
        )
        by_id = client.get(
            attestation_url(challenge["challengeId"]), headers=session["headers"]
        )
        assert by_date.status_code == by_id.status_code == 200
        assert by_date.json() == by_id.json()


def test_failed_member_still_gets_a_verifiable_attestation():
    """Honest record: a failed week is attested as passed=false, not hidden."""
    with support.signed_in("mallory.testnet") as session:
        ingest = support.ingest_pact(session, "cheater")
        assert ingest.status_code == 200
        assert ingest.json()["passed"] is False

        att = client.get(
            attestation_url(MONDAY.isoformat()), headers=session["headers"]
        ).json()
        assert att["passed"] is False
        published = client.get("/api/pact/attestation-key").json()["publicKey"]
        payload = AttestationPayload(
            schema_tag=att["schemaTag"],
            contract_id=att["contractId"],
            challenge_id=att["challengeId"],
            account_id=att["accountId"],
            passed=att["passed"],
            expiry=att["expiry"],
        )
        assert verify_attestation(
            b58decode(published.removeprefix("ed25519:")),
            payload,
            base64.b64decode(att["signature"]),
            now=att["expiry"] - 1,
        )


if __name__ == "__main__":
    raise SystemExit(pytest.main([__file__, "-q"]))
