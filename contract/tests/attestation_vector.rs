//! Cross-language verification of the settlement-attestation stage.
//!
//! Tests that depend on the stage-4 contract API (validate_init_args,
//! parse_canonical_u128, SignedSettlementAttestation, verify_settlement_attestation)
//! have been removed because those items are not part of the stage-5 public API.
//! The core invariant — that the Python-signed test vector verifies byte-for-byte
//! in Rust — is fully covered by the remaining tests and by test_stage4_vector_still_verifies
//! in unit_tests.rs.

use std::str::FromStr;

use near_sdk::json_types::U128;
use near_sdk::test_utils::VMContextBuilder;
use near_sdk::testing_env;
use near_sdk::{AccountId, NearToken, PublicKey};

use pact_contract::attestation::{
    ed25519_key_bytes, verify_signature, verify_with_expiry, SettlementAttestation, SCHEMA_TAG,
};
use pact_contract::PactContract;

// ---------------------------------------------------------------------------
// The shared test vector
// ---------------------------------------------------------------------------

fn vector() -> near_sdk::serde_json::Value {
    let text = std::fs::read_to_string("test-vectors/settlement_attestation.json")
        .expect("test vector must exist; run `python pact/scripts/make_attestation_vector.py`");
    near_sdk::serde_json::from_str(&text).expect("test vector must be valid JSON")
}

fn vector_attestation(v: &near_sdk::serde_json::Value) -> SettlementAttestation {
    SettlementAttestation {
        schema_tag:   v["schemaTag"].as_str().unwrap().to_string(),
        contract_id:  v["contractId"].as_str().unwrap().to_string(),
        challenge_id: v["challengeId"].as_str().unwrap().to_string(),
        account_id:   v["accountId"].as_str().unwrap().to_string(),
        passed:       v["passed"].as_bool().unwrap(),
        expiry:       v["expiry"].as_u64().unwrap(),
    }
}

fn vector_signature_bytes(v: &near_sdk::serde_json::Value) -> Vec<u8> {
    use near_sdk::base64::Engine as _;
    near_sdk::base64::engine::general_purpose::STANDARD
        .decode(v["signature"].as_str().unwrap())
        .expect("signature must be base64")
}

fn vector_public_key(v: &near_sdk::serde_json::Value) -> PublicKey {
    PublicKey::from_str(v["publicKey"].as_str().unwrap()).unwrap()
}

// ---------------------------------------------------------------------------
// Canonical bytes: Rust Borsh mirror must match Python byte-for-byte
// ---------------------------------------------------------------------------

#[test]
fn rust_borsh_mirror_matches_python_bytes_exactly() {
    let v = vector();
    let attestation = vector_attestation(&v);
    let expected = hex::decode(v["payloadBorshHex"].as_str().unwrap()).unwrap();
    assert_eq!(
        attestation.canonical_bytes(),
        expected,
        "Rust Borsh mirror must produce exactly pact/attestation.py's bytes"
    );
}

#[test]
fn canonical_layout_is_pinned_field_by_field() {
    // Documents the layout independently of the vector.
    // contractId "opaque-pact.testnet" is from the test vector — it is the
    // contract_id field baked into the signed payload. It is a protocol
    // identifier (domain separation), not a display name.
    let attestation = SettlementAttestation {
        schema_tag:   SCHEMA_TAG.to_string(),
        contract_id:  "opaque-pact.testnet".to_string(),
        challenge_id: "2026-11-09".to_string(),
        account_id:   "alice.testnet".to_string(),
        passed:       true,
        expiry:       1795392000,
    };
    let bytes = attestation.canonical_bytes();
    let mut offset = 0usize;
    for field in [SCHEMA_TAG, "opaque-pact.testnet", "2026-11-09", "alice.testnet"] {
        let length = u32::try_from(field.len()).unwrap().to_le_bytes();
        assert_eq!(&bytes[offset..offset + 4], &length, "String length must be u32 LE");
        offset += 4;
        assert_eq!(&bytes[offset..offset + field.len()], field.as_bytes());
        offset += field.len();
    }
    assert_eq!(bytes[offset], 1); // bool true
    offset += 1;
    assert_eq!(&bytes[offset..offset + 8], &1795392000u64.to_le_bytes());
    assert_eq!(offset + 8, bytes.len());
}

// ---------------------------------------------------------------------------
// Pure Ed25519 verification of the backend-signed vector
// ---------------------------------------------------------------------------

#[test]
fn backend_signed_vector_verifies_with_ed25519() {
    let v = vector();
    let attestation = vector_attestation(&v);
    let key = ed25519_key_bytes(&vector_public_key(&v))
        .expect("the vector key must be ed25519");
    let signature: [u8; 64] = vector_signature_bytes(&v)
        .try_into()
        .expect("an Ed25519 signature is exactly 64 bytes");

    assert!(verify_signature(&key, &attestation, &signature));

    // Freshness: valid strictly before expiry, rejected at and after it.
    assert!(verify_with_expiry(&key, &attestation, &signature, attestation.expiry - 1));
    assert!(!verify_with_expiry(&key, &attestation, &signature, attestation.expiry));
}

#[test]
fn tampered_attestations_never_verify() {
    let v = vector();
    let attestation = vector_attestation(&v);
    let key = ed25519_key_bytes(&vector_public_key(&v)).unwrap();
    let signature: [u8; 64] = vector_signature_bytes(&v).try_into().unwrap();
    assert!(verify_signature(&key, &attestation, &signature));

    let fails = |a: &SettlementAttestation| !verify_signature(&key, a, &signature);

    let mut wrong = attestation.clone();
    wrong.schema_tag = "OPAQUE-PACT-ATTESTATION-V0".to_string();
    assert!(fails(&wrong), "wrong schema tag must not verify");

    let mut wrong = attestation.clone();
    wrong.contract_id = "other-pact.testnet".to_string();
    assert!(fails(&wrong), "another contract's attestation must not verify");

    let mut wrong = attestation.clone();
    wrong.challenge_id = "2026-11-02".to_string();
    assert!(fails(&wrong), "another week's attestation must not verify");

    let mut wrong = attestation.clone();
    wrong.account_id = "bob.testnet".to_string();
    assert!(fails(&wrong), "another member's attestation must not verify");

    let mut wrong = attestation.clone();
    wrong.passed = !wrong.passed;
    assert!(fails(&wrong), "a flipped verdict must not verify");

    let mut wrong = attestation.clone();
    wrong.expiry += 1;
    assert!(fails(&wrong), "a shifted expiry must not verify");

    let mut flipped = signature;
    flipped[0] ^= 0xff;
    assert!(!verify_signature(&key, &attestation, &flipped));

    let mut wrong_key = key;
    wrong_key[0] ^= 0xff;
    assert!(!verify_signature(&wrong_key, &attestation, &signature));
}

#[test]
fn non_ed25519_keys_have_no_ed25519_key_bytes() {
    let secp = format!("secp256k1:{}", near_sdk::bs58::encode([0u8; 64]).into_string());
    let key = PublicKey::from_str(&secp).unwrap();
    assert!(ed25519_key_bytes(&key).is_none());
}

// ---------------------------------------------------------------------------
// Contract-level: stage-5 new() pins the key and get_attestation_public_key
// returns it. The contract no longer exports verify_settlement_attestation as
// a public view (it's now called internally by submit_completion).
// ---------------------------------------------------------------------------

fn init_context(timestamp_unix_seconds: u64) -> VMContextBuilder {
    let mut builder = VMContextBuilder::new();
    builder
        .current_account_id(AccountId::from_str("opaque-pact.testnet").unwrap())
        .block_timestamp(timestamp_unix_seconds * 1_000_000_000)
        .attached_deposit(NearToken::from_near(10));
    builder
}

fn deployed_contract(v: &near_sdk::serde_json::Value) -> PactContract {
    // Stage-5 new() — updated args: dao_account_id, rules_hash, submission_grace_secs
    // using current rules.json values (quorum=70, amounts updated).
    PactContract::new(
        vector_public_key(v),                           // attestation_public_key
        "pactdao.testnet".parse().unwrap(),              // dao_account_id
        "aabbccdd".to_string(),                          // rules_hash (placeholder)
        "500000000000000000000000".to_string(),          // stake_yocto (0.5 NEAR)
        70,                                              // quorum_pct
        "100000000000000000000".to_string(),             // mint_amount
        "50000000000000000000".to_string(),              // burn_amount
        "500000000000000000000".to_string(),             // reserve_amount
        5u64,                                            // min_members
        3600u64,                                         // submission_grace_secs
    )
}

#[test]
fn contract_pins_attestation_key() {
    let v = vector();
    let expiry = v["expiry"].as_u64().unwrap();
    testing_env!(init_context(expiry - 1).build());
    let contract = deployed_contract(&v);

    // Key is pinned and exposed.
    assert_eq!(
        contract.get_attestation_public_key().to_string(),
        v["publicKey"].as_str().unwrap()
    );

    // DAO is pinned.
    assert_eq!(contract.get_dao_account_id().as_str(), "pactdao.testnet");

    // Rules are pinned with stage-5 values.
    assert_eq!(contract.get_quorum_pct(), 70);
    assert_eq!(contract.get_min_members(), 5);
    assert_eq!(contract.get_submission_grace_secs(), 3600);
}

#[test]
#[should_panic(expected = "quorum_pct must be 1..=100")]
fn new_panics_on_invalid_quorum() {
    testing_env!(init_context(0).build());
    let v = vector();
    let _ = PactContract::new(
        vector_public_key(&v),
        "pactdao.testnet".parse().unwrap(),
        "h".to_string(),
        "500000000000000000000000".to_string(),
        0, // invalid quorum_pct
        "100000000000000000000".to_string(),
        "50000000000000000000".to_string(),
        "500000000000000000000".to_string(),
        5u64,
        3600u64,
    );
}
