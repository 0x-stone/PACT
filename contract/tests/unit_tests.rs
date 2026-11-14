//! Unit tests for the Pact contract (offline, VMContextBuilder).
//! Run: cargo test --lib

use near_sdk::json_types::{Base64VecU8, U64};
use near_sdk::test_utils::VMContextBuilder;
use near_sdk::{testing_env, AccountId, NearToken, PublicKey};
use std::str::FromStr;

use near_contract_standards::fungible_token::FungibleTokenCore;
use near_contract_standards::fungible_token::metadata::FungibleTokenMetadataProvider;
use near_contract_standards::storage_management::StorageManagement;
use pact_contract::{ChallengeOutcome, PactContract};

// ── constants ────────────────────────────────────────────────────────────────
const STAKE:   u128 = 500_000_000_000_000_000_000_000; // 0.5 NEAR
const MINT:    u128 = 100_000_000_000_000_000_000;      // 100 PACT
const BURN:    u128 =  50_000_000_000_000_000_000;      //  50 PACT
const RESERVE: u128 = 500_000_000_000_000_000_000;      // 500 PACT
const QUORUM:  u8   = 70;
const MIN_MEM: u64  = 5;
const GRACE:   u64  = 3_600;

fn attest_pk() -> PublicKey {
    // from settlement_attestation.json test vector
    PublicKey::from_str("ed25519:39ucT4v8YJpFjnjEhpbz2hFCt1QPxmtGRViAvujEpn8N").unwrap()
}
fn dao() -> AccountId   { "dao.testnet".parse().unwrap() }
fn admin() -> AccountId { "admin.testnet".parse().unwrap() }
fn contract_id() -> AccountId { "pact.testnet".parse().unwrap() }

fn ctx(predecessor: AccountId, ts_ns: u64) -> VMContextBuilder {
    let mut b = VMContextBuilder::new();
    b.predecessor_account_id(predecessor)
     .current_account_id(contract_id())
     .block_timestamp(ts_ns)
     .attached_deposit(NearToken::from_near(0));
    b
}

fn deploy() -> PactContract {
    testing_env!(ctx(admin(), 1_000_000_000).build());
    PactContract::new(
        attest_pk(), dao(), "deadbeef".to_string(),
        STAKE.to_string(), QUORUM,
        MINT.to_string(), BURN.to_string(), RESERVE.to_string(),
        MIN_MEM, GRACE,
    )
}

/// Register account with the FT (storage_deposit equivalent).
fn register(c: &mut PactContract, account: &AccountId) {
    let mut b = ctx(account.clone(), 1_000_000_000);
    b.attached_deposit(NearToken::from_millinear(2));
    testing_env!(b.build());
    c.storage_deposit(Some(account.clone()), Some(true));
}

fn enroll(c: &mut PactContract, account: &AccountId, cid: &str) {
    let mut b = ctx(account.clone(), 1_000_000_000);
    b.attached_deposit(NearToken::from_yoctonear(STAKE));
    testing_env!(b.build());
    c.enroll(cid.to_string());
}

fn secs(s: u64) -> u64 { s * 1_000_000_000 }

// ── invariant checker ────────────────────────────────────────────────────────
fn assert_inv(c: &PactContract) {
    let ts    = c.ft_total_supply().0;
    let rb    = c.get_reserve_balance().0;
    let esc   = c.get_reward_escrow().0;
    let minted = c.get_total_minted().0;
    let burned = c.get_total_burned().0;
    let cbal  = c.ft_balance_of(contract_id()).0;
    assert_eq!(ts, RESERVE + minted - burned, "supply invariant");
    assert_eq!(cbal, rb + esc, "balance invariant");
}

// ─────────────────────────────────────────────────────────────────────────────
// Init
// ─────────────────────────────────────────────────────────────────────────────
#[test] fn test_init_rules() {
    let c = deploy();
    assert_eq!(c.get_stake_yocto().0,  STAKE);
    assert_eq!(c.get_quorum_pct(),     QUORUM);
    assert_eq!(c.get_mint_amount().0,  MINT);
    assert_eq!(c.get_burn_amount().0,  BURN);
    assert_eq!(c.get_reserve_amount().0, RESERVE);
    assert_eq!(c.get_min_members(),    MIN_MEM);
    assert_eq!(c.get_submission_grace_secs(), GRACE);
    assert_eq!(c.get_dao_account_id(), dao());
    assert_eq!(c.get_rules_hash(),     "deadbeef");
}

#[test] fn test_init_reserve_minted() {
    let c = deploy();
    assert_eq!(c.ft_total_supply().0,     RESERVE);
    assert_eq!(c.get_reserve_balance().0, RESERVE);
    assert_eq!(c.ft_balance_of(contract_id()).0, RESERVE);
    assert_inv(&c);
}

#[test]
#[should_panic(expected = "quorum_pct must be 1..=100")]
fn test_init_bad_quorum() {
    testing_env!(ctx(admin(), 1_000_000_000).build());
    PactContract::new(attest_pk(), dao(), "h".to_string(),
        STAKE.to_string(), 0, MINT.to_string(), BURN.to_string(),
        RESERVE.to_string(), MIN_MEM, GRACE);
}

#[test]
#[should_panic(expected = "reserve_amount must be >= burn_amount")]
fn test_init_reserve_lt_burn() {
    testing_env!(ctx(admin(), 1_000_000_000).build());
    PactContract::new(attest_pk(), dao(), "h".to_string(),
        STAKE.to_string(), QUORUM, MINT.to_string(), BURN.to_string(),
        (BURN - 1).to_string(), MIN_MEM, GRACE);
}

// ─────────────────────────────────────────────────────────────────────────────
// set_next_challenge
// ─────────────────────────────────────────────────────────────────────────────
fn setup_challenge() -> (PactContract, u64, u64) {
    let mut c = deploy();
    let start = secs(5_000);
    let end   = secs(10_000);
    testing_env!(ctx(dao(), secs(100)).build());
    c.set_next_challenge("w1".to_string(), 8000, 5, U64(start), U64(end));
    (c, start, end)
}

#[test] fn test_challenge_stored() {
    let (c, start, end) = setup_challenge();
    let ch = c.get_challenge("w1".to_string()).unwrap();
    assert_eq!(ch.start_ns, start);
    assert_eq!(ch.end_ns,   end);
    assert!(matches!(ch.outcome, ChallengeOutcome::Pending));
}

#[test]
#[should_panic(expected = "only the DAO may set challenges")]
fn test_non_dao_blocked() {
    let mut c = deploy();
    testing_env!(ctx("alice.testnet".parse().unwrap(), secs(100)).build());
    c.set_next_challenge("w1".to_string(), 8000, 5, U64(secs(5000)), U64(secs(10000)));
}

#[test]
#[should_panic(expected = "end must be strictly after start")]
fn test_bad_timestamps() {
    let mut c = deploy();
    testing_env!(ctx(dao(), secs(100)).build());
    c.set_next_challenge("w1".to_string(), 8000, 5, U64(secs(5000)), U64(secs(4999)));
}

#[test]
#[should_panic(expected = "challenge_id already used")]
fn test_duplicate_id() {
    let (mut c, _, _) = setup_challenge();
    testing_env!(ctx(dao(), secs(100)).build());
    c.set_next_challenge("w1".to_string(), 8000, 5, U64(secs(20000)), U64(secs(30000)));
}

#[test]
#[should_panic(expected = "overlaps")]
fn test_overlap_rejected() {
    let (mut c, _, _) = setup_challenge();
    testing_env!(ctx(dao(), secs(100)).build());
    c.set_next_challenge("w2".to_string(), 8000, 5, U64(secs(7000)), U64(secs(15000)));
}

#[test] fn test_adjacent_ok() {
    let (mut c, _, end) = setup_challenge();
    testing_env!(ctx(dao(), secs(100)).build());
    c.set_next_challenge("w2".to_string(), 8000, 5, U64(end), U64(end + secs(5000)));
    assert!(c.get_challenge("w2".to_string()).is_some());
}

// ─────────────────────────────────────────────────────────────────────────────
// enroll
// ─────────────────────────────────────────────────────────────────────────────
#[test] fn test_enroll_ok() {
    let (mut c, start, _) = setup_challenge();
    let alice: AccountId = "alice.testnet".parse().unwrap();
    register(&mut c, &alice);
    let mut b = ctx(alice.clone(), start - 1);
    b.attached_deposit(NearToken::from_yoctonear(STAKE));
    testing_env!(b.build());
    c.enroll("w1".to_string());
    assert!(c.is_enrolled("w1".to_string(), alice));
    assert_eq!(c.get_challenge("w1".to_string()).unwrap().enrolled_count, 1);
}

#[test]
#[should_panic(expected = "attached deposit must be exactly")]
fn test_enroll_wrong_amount() {
    let (mut c, start, _) = setup_challenge();
    let alice: AccountId = "alice.testnet".parse().unwrap();
    register(&mut c, &alice);
    let mut b = ctx(alice.clone(), start - 1);
    b.attached_deposit(NearToken::from_yoctonear(STAKE - 1));
    testing_env!(b.build());
    c.enroll("w1".to_string());
}

#[test]
#[should_panic(expected = "storage_deposit")]
fn test_enroll_without_registration() {
    let (mut c, start, _) = setup_challenge();
    let alice: AccountId = "alice.testnet".parse().unwrap();
    let mut b = ctx(alice.clone(), start - 1);
    b.attached_deposit(NearToken::from_yoctonear(STAKE));
    testing_env!(b.build());
    c.enroll("w1".to_string());
}

#[test]
#[should_panic(expected = "already enrolled")]
fn test_double_enroll() {
    let (mut c, _, _) = setup_challenge();
    let alice: AccountId = "alice.testnet".parse().unwrap();
    register(&mut c, &alice);
    enroll(&mut c, &alice, "w1");
    enroll(&mut c, &alice, "w1");
}

// ─────────────────────────────────────────────────────────────────────────────
// settle_week — counters injected directly (bypass submit_completion)
// ─────────────────────────────────────────────────────────────────────────────
fn set_counters(c: &mut PactContract, cid: &str, enrolled: u64, qualifiers: u64) {
    let mut ch = c.challenges.get(&cid.to_string()).unwrap();
    ch.enrolled_count  = enrolled;
    ch.qualifier_count = qualifiers;
    ch.submitted_count = enrolled;
    c.challenges.insert(&cid.to_string(), &ch);
}

fn settle(c: &mut PactContract, cid: &str, grace_end: u64) {
    testing_env!(ctx(admin(), grace_end + secs(10)).build());
    c.settle_week(cid.to_string());
}

fn setup_settle(enrolled: u64, qualifiers: u64) -> (PactContract, String, u64) {
    let (mut c, _, end) = setup_challenge();
    let grace_end = end + secs(GRACE);
    set_counters(&mut c, "w1", enrolled, qualifiers);
    (c, "w1".to_string(), grace_end)
}

#[test] fn test_void() {
    let (mut c, cid, ge) = setup_settle(MIN_MEM - 1, 0);
    settle(&mut c, &cid, ge);
    assert!(matches!(c.get_challenge(cid).unwrap().outcome, ChallengeOutcome::Void));
    assert_eq!(c.get_reserve_balance().0, RESERVE);
    assert_inv(&c);
}

#[test] fn test_failure() {
    // 5 enrolled, 3 pass: 3*100=300 < 5*70=350
    let (mut c, cid, ge) = setup_settle(5, 3);
    settle(&mut c, &cid, ge);
    match c.get_challenge(cid.clone()).unwrap().outcome {
        ChallengeOutcome::Failure { burn_actual } => assert_eq!(burn_actual.0, BURN),
        _ => panic!("expected Failure"),
    }
    assert_eq!(c.get_reserve_balance().0, RESERVE - BURN);
    assert_inv(&c);
}

#[test] fn test_success() {
    // 5 enrolled, 4 pass: 4*100=400 >= 5*70=350
    let (mut c, cid, ge) = setup_settle(5, 4);
    settle(&mut c, &cid, ge);
    match c.get_challenge(cid.clone()).unwrap().outcome {
        ChallengeOutcome::Success { reward_per_qualifier } =>
            assert_eq!(reward_per_qualifier.0, MINT / 4),
        _ => panic!("expected Success"),
    }
    assert_eq!(c.get_reward_escrow().0, (MINT / 4) * 4);
    assert_inv(&c);
}

#[test] fn test_exactly_at_quorum() {
    // 10 enrolled, 7 pass: 7*100=700 == 10*70=700
    let (mut c, cid, ge) = setup_settle(10, 7);
    settle(&mut c, &cid, ge);
    assert!(matches!(c.get_challenge(cid).unwrap().outcome, ChallengeOutcome::Success { .. }));
    assert_inv(&c);
}

#[test] fn test_one_below_quorum() {
    // 10 enrolled, 6 pass: 600 < 700
    let (mut c, cid, ge) = setup_settle(10, 6);
    settle(&mut c, &cid, ge);
    assert!(matches!(c.get_challenge(cid).unwrap().outcome, ChallengeOutcome::Failure { .. }));
    assert_inv(&c);
}

#[test]
#[should_panic(expected = "already settled")]
fn test_double_settle() {
    let (mut c, cid, ge) = setup_settle(5, 4);
    settle(&mut c, &cid, ge);
    settle(&mut c, &cid, ge);
}

#[test]
#[should_panic(expected = "settlement not available")]
fn test_settle_before_grace() {
    let (mut c, _, end) = setup_challenge();
    // exactly at end, before grace expires
    testing_env!(ctx(admin(), end).build());
    c.settle_week("w1".to_string());
}

// ─────────────────────────────────────────────────────────────────────────────
// Reserve exhaustion — no panic, stakes still withdrawable
// ─────────────────────────────────────────────────────────────────────────────
#[test] fn test_reserve_exhausted_no_panic() {
    let (mut c, cid, ge) = setup_settle(5, 0);
    // Drain reserve manually.
    c.reserve_balance = 0;
    c.total_burned = RESERVE;
    c.token.internal_withdraw(&contract_id(), RESERVE);
    settle(&mut c, &cid, ge);
    match c.get_challenge(cid).unwrap().outcome {
        ChallengeOutcome::Failure { burn_actual } => assert_eq!(burn_actual.0, 0),
        _ => panic!("expected Failure with 0 burn"),
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Rounding
// ─────────────────────────────────────────────────────────────────────────────
#[test] fn test_mint_rounding() {
    // 3 qualifiers: floor(100e18/3) = 33333…e18, total < MINT
    // But first check: does 3-of-5 pass quorum?
    // 3*100=300 < 5*70=350 → FAILS quorum. Need more qualifiers.
    // Use 10 enrolled, 8 qualifiers: 8*100=800 >= 10*70=700 → SUCCESS
    // floor(100e18/8) = 12500000000000000000 each, total = 100000000000000000000 (exact)
    // For a remainder test, use 10 enrolled, 9 qualifiers:
    // 9*100=900 >= 10*70=700 → SUCCESS
    // floor(100e18/9) = 11111111111111111111 each, total = 99999999999999999999
    let (mut c, cid, ge) = setup_settle(10, 9);
    settle(&mut c, &cid, ge);
    let per = MINT / 9;
    assert_eq!(c.get_reward_escrow().0, per * 9);
    assert_eq!(c.get_total_minted().0,  per * 9);
    // The remainder (MINT - per*9 = 1) is never minted
    assert_eq!(MINT - per * 9, 1);
    assert_inv(&c);
}

// ─────────────────────────────────────────────────────────────────────────────
// claim_reward
// ─────────────────────────────────────────────────────────────────────────────
#[test]
#[should_panic(expected = "caller is not a qualifier")]
fn test_claim_non_qualifier() {
    let (mut c, cid, ge) = setup_settle(5, 4);
    settle(&mut c, &cid, ge);
    let alice: AccountId = "alice.testnet".parse().unwrap();
    register(&mut c, &alice);
    testing_env!(ctx(alice.clone(), ge + secs(100)).build());
    c.claim_reward(cid);
}

#[test]
#[should_panic(expected = "reward already claimed")]
fn test_double_claim() {
    let (mut c, cid, ge) = setup_settle(5, 4);
    settle(&mut c, &cid, ge);
    let alice: AccountId = "alice.testnet".parse().unwrap();
    register(&mut c, &alice);
    // Inject qualifier status
    c.qualifiers.insert(&format!("w1\x00{}", alice), &true);
    c.enrollments.insert(&format!("w1\x00{}", alice), &STAKE);
    testing_env!(ctx(alice.clone(), ge + secs(100)).build());
    c.claim_reward(cid.clone());
    c.claim_reward(cid);
}

// ─────────────────────────────────────────────────────────────────────────────
// Failed stake withdrawal callback retry
// ─────────────────────────────────────────────────────────────────────────────
#[test] fn test_failed_stake_callback_clears_pending() {
    let (mut c, cid, ge) = setup_settle(5, 4);
    settle(&mut c, &cid, ge);
    let alice: AccountId = "alice.testnet".parse().unwrap();
    // Inject enrolled + pending state
    let key = format!("w1\x00{}", alice);
    c.enrollments.insert(&key, &STAKE);
    c.stake_pending.insert(&key, &true);
    // Fire callback with failure
    testing_env!(ctx(contract_id(), ge + secs(200)).build());
    c.on_stake_withdrawn(cid.clone(), alice.clone(), Err(near_sdk::PromiseError::Failed));
    // pending must be cleared, withdrawn must NOT be set
    assert!(!c.stake_pending.get(&key).unwrap_or(false));
    assert!(!c.has_withdrawn_stake(cid, alice));
}

// ─────────────────────────────────────────────────────────────────────────────
// withdraw_stake requires settlement
// ─────────────────────────────────────────────────────────────────────────────
#[test]
#[should_panic(expected = "not yet settled")]
fn test_withdraw_before_settle() {
    let (mut c, _, _) = setup_settle(5, 4);
    // challenge is still Pending
    let alice: AccountId = "alice.testnet".parse().unwrap();
    let key = format!("w1\x00{}", alice);
    c.enrollments.insert(&key, &STAKE);
    testing_env!(ctx(alice.clone(), secs(500)).build());
    let _ = c.withdraw_stake("w1".to_string());
}

// ─────────────────────────────────────────────────────────────────────────────
// submit_completion — structural rejection tests (no live signer)
// ─────────────────────────────────────────────────────────────────────────────
#[test]
#[should_panic(expected = "not enrolled")]
fn test_submit_non_enrolled() {
    let (mut c, _, end) = setup_challenge();
    let stranger: AccountId = "stranger.testnet".parse().unwrap();
    let expiry = end / 1_000_000_000 + 7_200;
    testing_env!(ctx(stranger.clone(), end + secs(10)).build());
    c.submit_completion(
        "w1".to_string(), stranger, false, U64(expiry),
        Base64VecU8(vec![0u8; 64]),
    );
}

#[test]
#[should_panic(expected = "grace period has expired")]
fn test_submit_after_grace() {
    let (mut c, _, end) = setup_challenge();
    let alice: AccountId = "alice.testnet".parse().unwrap();
    let key = format!("w1\x00{}", alice);
    c.enrollments.insert(&key, &STAKE);
    let expiry = end / 1_000_000_000 + 7_200;
    // After end + grace
    testing_env!(ctx(alice.clone(), end + secs(GRACE + 1)).build());
    c.submit_completion(
        "w1".to_string(), alice, false, U64(expiry),
        Base64VecU8(vec![0u8; 64]),
    );
}

// ─────────────────────────────────────────────────────────────────────────────
// Stage-4 test vector still verifies (Borsh layout unchanged)
// ─────────────────────────────────────────────────────────────────────────────
#[test] fn test_stage4_vector_still_verifies() {
    use pact_contract::attestation::{ed25519_key_bytes, verify_signature, SettlementAttestation};
    let text = std::fs::read_to_string("test-vectors/settlement_attestation.json")
        .expect("test vector must exist");
    let v: serde_json::Value = serde_json::from_str(&text).unwrap();

    let attestation = SettlementAttestation {
        schema_tag:   v["schemaTag"].as_str().unwrap().to_string(),
        contract_id:  v["contractId"].as_str().unwrap().to_string(),
        challenge_id: v["challengeId"].as_str().unwrap().to_string(),
        account_id:   v["accountId"].as_str().unwrap().to_string(),
        passed:       v["passed"].as_bool().unwrap(),
        expiry:       v["expiry"].as_u64().unwrap(),
    };
    let key = ed25519_key_bytes(&attest_pk()).unwrap();
    use near_sdk::base64::Engine as _;
    let sig_bytes = near_sdk::base64::engine::general_purpose::STANDARD
        .decode(v["signature"].as_str().unwrap()).unwrap();
    let sig: [u8; 64] = sig_bytes.try_into().unwrap();
    let expected_hex = v["payloadBorshHex"].as_str().unwrap();
    assert_eq!(hex::encode(attestation.canonical_bytes()), expected_hex);
    assert!(verify_signature(&key, &attestation, &sig));
}

// ─────────────────────────────────────────────────────────────────────────────
// NEP-148 metadata
// ─────────────────────────────────────────────────────────────────────────────
#[test] fn test_ft_metadata() {
    let c = deploy();
    let m = c.ft_metadata();
    assert_eq!(m.name, "Pact");
    assert_eq!(m.symbol, "PACT");
    assert_eq!(m.decimals, 18);
}

// =============================================================================
// Item 5: submit_completion tests with REAL signatures from the test vector key
//
// All tests in this section use the fixed seed from settlement_attestation.json
// so we can produce real valid signatures offline (no network required).
// Seed: sha256(b"opaque-pact-attestation-test-vector") — same as make_attestation_vector.py
// =============================================================================

use pact_contract::attestation::{verify_signature, SettlementAttestation, SCHEMA_TAG};
use ed25519_dalek::{SigningKey as DalekSigningKey, Signer};

/// Sign the canonical Borsh bytes of a SettlementAttestation with the test seed.
/// Seed = sha256(b"opaque-pact-attestation-test-vector")
/// = 583f32d82e3bc1b137f12d2c01dd5f815911054b67937bf6fb8838e2e9c7a9c3
/// This matches the public key ed25519:39ucT4v8YJpFjnjEhpbz2hFCt1QPxmtGRViAvujEpn8N
fn sign_attestation(attestation: &SettlementAttestation) -> [u8; 64] {
    let seed: [u8; 32] = [
        0x58, 0x3f, 0x32, 0xd8, 0x2e, 0x3b, 0xc1, 0xb1,
        0x37, 0xf1, 0x2d, 0x2c, 0x01, 0xdd, 0x5f, 0x81,
        0x59, 0x11, 0x05, 0x4b, 0x67, 0x93, 0x7b, 0xf6,
        0xfb, 0x88, 0x38, 0xe2, 0xe9, 0xc7, 0xa9, 0xc3,
    ];
    let sk = DalekSigningKey::from_bytes(&seed);
    sk.sign(&attestation.canonical_bytes()).to_bytes()
}

/// Deploy a contract that uses the test-vector public key.
fn deploy_with_test_key() -> PactContract {
    testing_env!(ctx(admin(), 1_000_000_000).build());
    PactContract::new(
        attest_pk(),  // ed25519:39ucT4v8... — matches the test seed above
        dao(), "deadbeef".to_string(),
        STAKE.to_string(), QUORUM,
        MINT.to_string(), BURN.to_string(), RESERVE.to_string(),
        MIN_MEM, GRACE,
    )
}

/// Set up a challenge and enroll `account_id` for submission tests.
/// Returns (contract, end_ns, expiry_secs)
fn setup_for_submission(
    account_id: &AccountId,
) -> (PactContract, u64, u64) {
    let mut c = deploy_with_test_key();
    let start_ns = secs(5_000);
    let end_ns   = secs(10_000);
    testing_env!(ctx(dao(), secs(100)).build());
    c.set_next_challenge("ch1".to_string(), 8000, 5,
        near_sdk::json_types::U64(start_ns), near_sdk::json_types::U64(end_ns));

    // Register + enroll
    register(&mut c, account_id);
    enroll(&mut c, account_id, "ch1");

    // Expiry = end_secs + grace + 100 (safely in the future relative to submission)
    let expiry_secs = end_ns / 1_000_000_000 + GRACE + 100;
    (c, end_ns, expiry_secs)
}

// ── Bad signature rejected ────────────────────────────────────────────────────

#[test]
#[should_panic(expected = "signature invalid or expired")]
fn test_submit_bad_signature() {
    let alice: AccountId = "alice.testnet".parse().unwrap();
    let (mut c, end_ns, expiry_secs) = setup_for_submission(&alice);

    // Build the correct attestation but corrupt the signature.
    let attestation = SettlementAttestation {
        schema_tag:   SCHEMA_TAG.to_string(),
        contract_id:  contract_id().to_string(),
        challenge_id: "ch1".to_string(),
        account_id:   alice.to_string(),
        passed:       true,
        expiry:       expiry_secs,
    };
    let mut sig = sign_attestation(&attestation);
    sig[0] ^= 0xFF; // corrupt first byte

    testing_env!(ctx(alice.clone(), end_ns + secs(10)).build());
    c.submit_completion(
        "ch1".to_string(), alice.clone(), true,
        near_sdk::json_types::U64(expiry_secs),
        Base64VecU8(sig.to_vec()),
    );
}

// ── Expired attestation rejected ─────────────────────────────────────────────

#[test]
#[should_panic(expected = "signature invalid or expired")]
fn test_submit_expired_attestation() {
    let alice: AccountId = "alice.testnet".parse().unwrap();
    let (mut c, end_ns, _) = setup_for_submission(&alice);

    // expiry in the PAST relative to block timestamp
    let submit_time_ns = end_ns + secs(10);
    let expired_secs   = submit_time_ns / 1_000_000_000 - 1; // 1 second in the past

    let attestation = SettlementAttestation {
        schema_tag:   SCHEMA_TAG.to_string(),
        contract_id:  contract_id().to_string(),
        challenge_id: "ch1".to_string(),
        account_id:   alice.to_string(),
        passed:       true,
        expiry:       expired_secs,
    };
    let sig = sign_attestation(&attestation);

    testing_env!(ctx(alice.clone(), submit_time_ns).build());
    c.submit_completion(
        "ch1".to_string(), alice.clone(), true,
        near_sdk::json_types::U64(expired_secs),
        Base64VecU8(sig.to_vec()),
    );
}

// ── Wrong account rejected ───────────────────────────────────────────────────

#[test]
#[should_panic(expected = "signature invalid or expired")]
fn test_submit_wrong_account() {
    let alice: AccountId = "alice.testnet".parse().unwrap();
    let bob:   AccountId = "bob.testnet".parse().unwrap();
    let (mut c, end_ns, expiry_secs) = setup_for_submission(&alice);

    // Signature is over alice's attestation but we claim it's for bob.
    // Even if bob is enrolled, the sig won't verify because account_id differs.
    register(&mut c, &bob);
    enroll(&mut c, &bob, "ch1");

    let attestation_for_alice = SettlementAttestation {
        schema_tag:   SCHEMA_TAG.to_string(),
        contract_id:  contract_id().to_string(),
        challenge_id: "ch1".to_string(),
        account_id:   alice.to_string(), // signed for alice
        passed:       true,
        expiry:       expiry_secs,
    };
    let sig = sign_attestation(&attestation_for_alice);

    testing_env!(ctx(bob.clone(), end_ns + secs(10)).build());
    // Submit for bob but with alice's signature — must fail
    c.submit_completion(
        "ch1".to_string(), bob.clone(), true,
        near_sdk::json_types::U64(expiry_secs),
        Base64VecU8(sig.to_vec()),
    );
}

// ── Wrong challenge_id rejected ──────────────────────────────────────────────
// The contract verifies the Ed25519 signature over the canonical Borsh payload
// which includes challenge_id. Submitting a sig signed for ch1 to ch2 must fail
// because the canonical bytes differ.

#[test]
#[should_panic(expected = "signature invalid or expired")]
fn test_submit_wrong_challenge_id() {
    let alice: AccountId = "alice.testnet".parse().unwrap();
    let (mut c, _end_ns1, _expiry_secs1) = setup_for_submission(&alice);
    // ch1: start=secs(5000), end=secs(10000)

    // Set ch2 with explicit times that don't overlap ch1 and are in the future
    // relative to block time secs(100).
    // Submission window for ch2: (end2, end2 + grace_ns]
    // We submit at secs(10200): after end2=secs(10100), well within grace=3600s
    testing_env!(ctx(dao(), secs(100)).build());
    let start2 = secs(10001);   // after ch1 ends, future relative to secs(100)
    let end2   = secs(10100);   // 99s duration
    c.set_next_challenge("ch2".to_string(), 8000, 5,
        near_sdk::json_types::U64(start2), near_sdk::json_types::U64(end2));
    enroll(&mut c, &alice, "ch2");

    // expiry must be > now_s at submit time
    // submit_time_s = 10200, expiry must be > 10200
    let expiry_secs = 10200u64 + GRACE + 100; // 13900
    let attestation_for_ch1 = SettlementAttestation {
        schema_tag:   SCHEMA_TAG.to_string(),
        contract_id:  contract_id().to_string(),
        challenge_id: "ch1".to_string(), // ← signed for ch1, not ch2
        account_id:   alice.to_string(),
        passed:       true,
        expiry:       expiry_secs,
    };
    let sig = sign_attestation(&attestation_for_ch1);

    // Submit time: after end2 (10100s), within grace (10100 + 3600 = 13700s)
    let submit_time = secs(10200);
    testing_env!(ctx(alice.clone(), submit_time).build());
    // ch2 attestation would need challenge_id="ch2" in the sig — this one has "ch1"
    c.submit_completion(
        "ch2".to_string(), alice.clone(), true,
        near_sdk::json_types::U64(expiry_secs),
        Base64VecU8(sig.to_vec()),
    );
}

// ── Relayer submit succeeds (caller != account_id) ───────────────────────────

#[test]
fn test_relayer_submit_succeeds() {
    let alice:   AccountId = "alice.testnet".parse().unwrap();
    let relayer: AccountId = "relayer.testnet".parse().unwrap();
    let (mut c, end_ns, expiry_secs) = setup_for_submission(&alice);

    let attestation = SettlementAttestation {
        schema_tag:   SCHEMA_TAG.to_string(),
        contract_id:  contract_id().to_string(),
        challenge_id: "ch1".to_string(),
        account_id:   alice.to_string(), // attested for alice
        passed:       true,
        expiry:       expiry_secs,
    };
    let sig = sign_attestation(&attestation);

    // Relayer (not alice) submits on alice's behalf
    testing_env!(ctx(relayer.clone(), end_ns + secs(10)).build());
    c.submit_completion(
        "ch1".to_string(),
        alice.clone(),   // account_id = alice (attested)
        true,
        near_sdk::json_types::U64(expiry_secs),
        Base64VecU8(sig.to_vec()),
    );

    // Alice is now a qualifier; qualifier_count incremented
    assert!(c.is_qualifier("ch1".to_string(), alice.clone()));
    assert_eq!(c.get_challenge("ch1".to_string()).unwrap().qualifier_count, 1);
}

// ── Double submit rejected ───────────────────────────────────────────────────

#[test]
#[should_panic(expected = "already submitted")]
fn test_double_submit_rejected() {
    let alice: AccountId = "alice.testnet".parse().unwrap();
    let (mut c, end_ns, expiry_secs) = setup_for_submission(&alice);

    let attestation = SettlementAttestation {
        schema_tag:   SCHEMA_TAG.to_string(),
        contract_id:  contract_id().to_string(),
        challenge_id: "ch1".to_string(),
        account_id:   alice.to_string(),
        passed:       true,
        expiry:       expiry_secs,
    };
    let sig = sign_attestation(&attestation);
    let sig2 = sig; // same sig both times

    testing_env!(ctx(alice.clone(), end_ns + secs(10)).build());
    c.submit_completion(
        "ch1".to_string(), alice.clone(), true,
        near_sdk::json_types::U64(expiry_secs),
        Base64VecU8(sig.to_vec()),
    );
    // Second submit — must panic
    c.submit_completion(
        "ch1".to_string(), alice.clone(), true,
        near_sdk::json_types::U64(expiry_secs),
        Base64VecU8(sig2.to_vec()),
    );
}

// ── Verify signatures are actually correct (sanity check for the helper) ─────

#[test]
fn test_sign_attestation_helper_produces_valid_sig() {
    use pact_contract::attestation::ed25519_key_bytes;
    let alice: AccountId = "alice.testnet".parse().unwrap();
    let attestation = SettlementAttestation {
        schema_tag:   SCHEMA_TAG.to_string(),
        contract_id:  contract_id().to_string(),
        challenge_id: "ch1".to_string(),
        account_id:   alice.to_string(),
        passed:       true,
        expiry:       99_999_999,
    };
    let sig = sign_attestation(&attestation);
    let key = ed25519_key_bytes(&attest_pk()).unwrap();
    assert!(verify_signature(&key, &attestation, &sig),
        "test signing helper must produce a valid signature");
}
