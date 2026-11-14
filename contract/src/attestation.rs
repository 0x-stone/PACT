//! Settlement attestation: the Rust mirror of `pact/attestation.py`.
//!
//! The backend signs an Ed25519 attestation proving that one member passed
//! (or failed) one pact week. This module defines the canonical payload —
//! the exact bytes that are signed — plus pure verification functions, so
//! the contract can check backend attestations without trusting the
//! backend's transport at settlement time.
//!
//! Canonical byte format (Borsh; byte-for-byte identical to
//! `pact/attestation.py` and pinned on both sides by the shared test
//! vector `contract/test-vectors/settlement_attestation.json`, whose
//! `payloadBorshHex` is produced by the Python side):
//!
//! ```text
//! struct SettlementAttestation {
//!     schema_tag:   String  // "OPAQUE-PACT-ATTESTATION-V1" (domain separation)
//!     contract_id:  String  // the pact contract that settles it
//!     challenge_id: String  // pact week's Monday, ISO date (e.g. "2026-11-09")
//!     account_id:   String  // NEAR account id of the member
//!     passed:       bool    // one byte: 0x00 / 0x01
//!     expiry:       u64     // unix seconds, little-endian (window close)
//! }
//! ```
//!
//! Borsh encoding rules used (https://borsh.io):
//! - `String` -> u32 little-endian byte length + UTF-8 bytes
//! - `bool`   -> single byte 0x00 / 0x01
//! - `u64`    -> 8 bytes little-endian
//!
//! `signature = ed25519(secret_key, borsh_bytes)` — over the raw canonical
//! bytes, no pre-hash.
//!
//! Domain separation, so one signature can never be replayed somewhere it
//! was not meant for:
//! - the fixed `schema_tag` means this is never a NEAR transaction, a
//!   NEP-413 login challenge, or any other protocol's message;
//! - `contract_id` pins the attestation to one deployed contract;
//! - `challenge_id` (the week's Monday) pins it to one week;
//! - `account_id` pins it to one member;
//! - `expiry` (the settlement-window close) makes stale attestations
//!   useless.

use near_sdk::borsh::{BorshDeserialize, BorshSerialize};
use near_sdk::json_types::CurveType;
use near_sdk::serde::{Deserialize, Serialize};
use near_sdk::PublicKey;

use ed25519_dalek::{Signature, Verifier, VerifyingKey};

/// Domain-separation tag: the first field of every canonical payload.
/// Must stay identical to `pact/attestation.py:SCHEMA_TAG`.
pub const SCHEMA_TAG: &str = "OPAQUE-PACT-ATTESTATION-V1";

/// The six signed fields, in canonical (serialized) order.
///
/// - Borsh (what is signed): `String` = u32 little-endian length + UTF-8,
///   `bool` = one byte, `u64` = 8 bytes little-endian — byte-for-byte the
///   same as `pact/attestation.py`'s manual encoding; the shared test
///   vector pins this on both sides.
/// - JSON (the contract ABI): camelCase keys matching the backend's
///   `GET /api/pact/attestation/{week}` response, so that response object
///   can be passed to the contract as-is (unknown fields such as
///   `publicKey` and `expiresAt` are ignored, as serde does by default).
#[derive(
    Debug, Clone, PartialEq, BorshSerialize, BorshDeserialize, Serialize, Deserialize,
)]
// `borsh`/`serde` are re-exports of near-sdk, not direct dependencies of
// this crate, so both derives need the crate path spelled out.
#[borsh(crate = "near_sdk::borsh")]
#[serde(crate = "near_sdk::serde", rename_all = "camelCase")]
pub struct SettlementAttestation {
    pub schema_tag: String,
    pub contract_id: String,
    pub challenge_id: String,
    pub account_id: String,
    pub passed: bool,
    pub expiry: u64,
}

impl SettlementAttestation {
    /// The canonical bytes the backend signs (and the contract verifies).
    ///
    /// borsh 1.8 removed the `try_to_vec` trait method in favor of the
    /// `to_vec` free function (re-exported through near-sdk).
    pub fn canonical_bytes(&self) -> Vec<u8> {
        near_sdk::borsh::to_vec(self)
            .expect("borsh serialization of String/bool/u64 fields cannot fail")
    }
}

/// Extract the raw 32-byte Ed25519 key from a NEAR `PublicKey`.
///
/// `near_sdk::PublicKey` stores one curve-tag byte followed by the raw key
/// bytes (32 for ed25519). Returns `None` for non-ed25519 keys (secp256k1
/// and ml-dsa-65 keys can never verify these attestations).
pub fn ed25519_key_bytes(public_key: &PublicKey) -> Option<[u8; 32]> {
    if !matches!(public_key.curve_type(), CurveType::ED25519) {
        return None;
    }
    let bytes = public_key.as_bytes();
    if bytes.len() != 1 + 32 {
        return None;
    }
    let mut key = [0u8; 32];
    key.copy_from_slice(&bytes[1..]);
    Some(key)
}

/// Verify the Ed25519 signature over the attestation's canonical bytes.
///
/// Pure check — no schema-tag, contract-id or freshness policy here; those
/// are the caller's (the contract view's) job, exactly like the optional
/// `now` parameter of `pact/attestation.py:verify_attestation`. The wrong
/// lengths Python guards against are enforced here by the argument types.
pub fn verify_signature(
    public_key: &[u8; 32],
    attestation: &SettlementAttestation,
    signature: &[u8; 64],
) -> bool {
    // A malformed key (not a valid curve point) simply fails verification.
    let Ok(verifying_key) = VerifyingKey::from_bytes(public_key) else {
        return false;
    };
    verifying_key
        .verify(&attestation.canonical_bytes(), &Signature::from_bytes(signature))
        .is_ok()
}

/// Signature + freshness: valid only while `now` (unix seconds) is strictly
/// before `expiry` — mirrors `verify_attestation(..., now=...)` in
/// `pact/attestation.py` (`expiry <= now` rejects).
pub fn verify_with_expiry(
    public_key: &[u8; 32],
    attestation: &SettlementAttestation,
    signature: &[u8; 64],
    now: u64,
) -> bool {
    if attestation.expiry <= now {
        return false;
    }
    verify_signature(public_key, attestation, signature)
}
