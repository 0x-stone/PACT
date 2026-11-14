//! Pact community fitness contract — Stage 5.
//!
//! # What this contract does
//!
//! 1. **NEP-141 fungible token** ("PACT", 18 decimals, NEP-148 metadata,
//!    NEP-145 storage management).  The contract IS the token: its own
//!    account holds `reserve_balance + unclaimed reward_escrow` at all times.
//!
//! 2. **Immutable pact rules** pinned at `new()`: stake, quorum, mint/burn
//!    amounts, reserve, min_members, submission_grace_secs, attestation key,
//!    DAO account id, and a `rules_hash` commitment.
//!
//! 3. **State machine per challenge**: set → enroll → submit_completion →
//!    settle_week → claim_reward / withdraw_stake.
//!
//! # Trust model (stated honestly)
//!
//! - The backend is trusted to sign true attestations and keep its key secret.
//!   The contract verifies the Ed25519 signature and domain separation; it
//!   cannot verify the underlying step data.
//! - `dao_account_id` is trusted to call `set_next_challenge`.  The contract
//!   rejects overlapping or retroactive challenges.
//! - Stakes are refundable in every outcome; the contract never confiscates them.
//! - Invariant after every operation:
//!     total_supply == reserve_amount_init + total_minted - total_burned
//!     ft_balance_of(contract) == reserve_balance + reward_escrow
//!
//! # Timestamps
//!
//! All challenge timestamps (`start_ns`, `end_ns`) are **nanoseconds** (u64),
//! matching `env::block_timestamp()`.  `submission_grace_secs` and attestation
//! `expiry` are **seconds**.  Every conversion is explicit.
//!
//! # Storage (NEP-145)
//!
//! Members must call `storage_deposit` before `enroll`.  The contract
//! registers itself at `new()` so the reserve mint works.  Enrollment-record
//! storage is paid by the contract's own NEAR balance; the deploy account
//! must be funded (see README).
//!
//! # Backend attestation expiry requirement
//!
//! The backend MUST issue attestations with:
//!     expiry_secs >= (challenge.end_ns / 1_000_000_000) + submission_grace_secs

pub mod attestation;
pub mod events;

use attestation::{ed25519_key_bytes, verify_with_expiry, SettlementAttestation, SCHEMA_TAG};
use events::{
    ChallengeSetData, ClaimRewardData, CompletionSubmittedData, EnrollData,
    FtBurnData, FtMintData, Nep141Event, PactEvent, SettledData, StakeWithdrawnData,
};

use near_contract_standards::fungible_token::metadata::{
    FungibleTokenMetadata, FungibleTokenMetadataProvider, FT_METADATA_SPEC,
};
use near_contract_standards::fungible_token::{FungibleToken, FungibleTokenCore};
use near_contract_standards::storage_management::{
    StorageBalance, StorageBalanceBounds, StorageManagement,
};
use near_sdk::borsh::{BorshDeserialize, BorshSerialize};
use near_sdk::collections::{LookupMap, UnorderedMap};
use near_sdk::json_types::{Base64VecU8, CurveType, U128, U64};
use near_sdk::serde::{Deserialize, Serialize};
use near_sdk::{
    env, log, near, require, AccountId, BorshStorageKey, Gas, NearToken, PanicOnDefault,
    Promise, PromiseOrValue, PublicKey,
};
use near_sdk::NearSchema;

// ---------------------------------------------------------------------------
// Storage key prefixes
// ---------------------------------------------------------------------------
#[derive(BorshSerialize, BorshStorageKey)]
#[borsh(crate = "near_sdk::borsh")]
enum Prefix {
    FungibleToken,
    Challenges,
    Enrollments,
    Submissions,
    Qualifiers,
    StakeWithdrawn,
    RewardClaimed,
    StakePending,
    ClaimPending,
}

// Compound map key: "challenge_id\x00account_id"
fn ck(challenge_id: &str, account_id: &AccountId) -> String {
    format!("{}\x00{}", challenge_id, account_id)
}

// ---------------------------------------------------------------------------
// Challenge state
// ---------------------------------------------------------------------------

#[derive(BorshSerialize, BorshDeserialize, Serialize, Deserialize, Clone, PartialEq, Debug, NearSchema)]
#[borsh(crate = "near_sdk::borsh")]
#[serde(crate = "near_sdk::serde")]
pub enum ChallengeOutcome {
    Pending,
    /// enrolled < min_members — no token movement.
    Void,
    /// Quorum met — reward_per_qualifier per passer.
    Success { reward_per_qualifier: U128 },
    /// Quorum not met — burn_actual burned from reserve.
    Failure { burn_actual: U128 },
}

#[derive(
    BorshSerialize,
    BorshDeserialize,
    Serialize,
    Deserialize,
    Clone,
    Debug,
    NearSchema
)]
#[borsh(crate = "near_sdk::borsh")]
#[serde(crate = "near_sdk::serde")]
pub struct Challenge {
    pub challenge_id: String,
    pub steps_per_day: u32,
    pub days_required: u8,
    /// Start timestamp in nanoseconds.
    pub start_ns: u64,
    /// End timestamp in nanoseconds.
    pub end_ns: u64,
    pub enrolled_count: u64,
    pub submitted_count: u64,
    pub qualifier_count: u64,
    pub outcome: ChallengeOutcome,
}

// ---------------------------------------------------------------------------
// Contract state
// ---------------------------------------------------------------------------

#[near(contract_state)]
#[derive(PanicOnDefault)]
pub struct PactContract {
    pub token: FungibleToken,

    // Immutable rules (all set once at new(), no setters)
    pub attestation_public_key: PublicKey,
    pub dao_account_id: AccountId,
    pub rules_hash: String,
    pub stake_yocto: u128,
    pub quorum_pct: u8,
    pub mint_amount: u128,
    pub burn_amount: u128,
    pub reserve_amount: u128,
    pub min_members: u64,
    /// Testnet: 3600.  Production recommended: 86400.
    pub submission_grace_secs: u64,

    // Accounting — both held in the contract's own FT balance
    pub reserve_balance: u128,
    pub reward_escrow: u128,
    pub total_minted: u128,
    pub total_burned: u128,

    // Per-challenge state (key = ck(challenge_id, account_id))
    pub challenges: UnorderedMap<String, Challenge>,
    pub enrollments: LookupMap<String, u128>,   // -> stake amount
    pub submissions: LookupMap<String, bool>,
    pub qualifiers: LookupMap<String, bool>,
    pub stake_withdrawn: LookupMap<String, bool>,
    pub reward_claimed: LookupMap<String, bool>,
    pub stake_pending: LookupMap<String, bool>,
    pub claim_pending: LookupMap<String, bool>,

    pub metadata: FungibleTokenMetadata,
}

// ---------------------------------------------------------------------------
// Contract implementation
// ---------------------------------------------------------------------------

#[near]
impl PactContract {
    // -----------------------------------------------------------------------
    // Init
    // -----------------------------------------------------------------------

    #[init]
    #[allow(clippy::too_many_arguments)]
    pub fn new(
        attestation_public_key: PublicKey,
        dao_account_id: AccountId,
        rules_hash: String,
        stake_yocto: String,
        quorum_pct: u8,
        mint_amount: String,
        burn_amount: String,
        reserve_amount: String,
        min_members: u64,
        submission_grace_secs: u64,
    ) -> Self {
        let stake   = parse_u128(&stake_yocto,   "stake_yocto");
        let mint    = parse_u128(&mint_amount,    "mint_amount");
        let burn    = parse_u128(&burn_amount,    "burn_amount");
        let reserve = parse_u128(&reserve_amount, "reserve_amount");

        require!(stake > 0,          "stake_yocto must be > 0");
        require!(mint > 0,           "mint_amount must be > 0");
        require!(burn > 0,           "burn_amount must be > 0");
        require!(reserve >= burn,    "reserve_amount must be >= burn_amount");
        require!(quorum_pct >= 1 && quorum_pct <= 100, "quorum_pct must be 1..=100");
        require!(min_members >= 1,   "min_members must be >= 1");
        require!(submission_grace_secs >= 1, "submission_grace_secs must be >= 1");
        require!(!rules_hash.is_empty(), "rules_hash must not be empty");
        require!(
            matches!(attestation_public_key.curve_type(), CurveType::ED25519),
            "attestation_public_key must be ed25519"
        );

        let mut this = Self {
            token: FungibleToken::new(Prefix::FungibleToken),
            attestation_public_key,
            dao_account_id,
            rules_hash,
            stake_yocto: stake,
            quorum_pct,
            mint_amount: mint,
            burn_amount: burn,
            reserve_amount: reserve,
            min_members,
            submission_grace_secs,
            reserve_balance: reserve,
            reward_escrow: 0,
            total_minted: 0,
            total_burned: 0,
            challenges:      UnorderedMap::new(Prefix::Challenges),
            enrollments:     LookupMap::new(Prefix::Enrollments),
            submissions:     LookupMap::new(Prefix::Submissions),
            qualifiers:      LookupMap::new(Prefix::Qualifiers),
            stake_withdrawn: LookupMap::new(Prefix::StakeWithdrawn),
            reward_claimed:  LookupMap::new(Prefix::RewardClaimed),
            stake_pending:   LookupMap::new(Prefix::StakePending),
            claim_pending:   LookupMap::new(Prefix::ClaimPending),
            metadata: FungibleTokenMetadata {
                spec: FT_METADATA_SPEC.to_string(),
                name: "Pact".to_string(),
                symbol: "PACT".to_string(),
                icon: None,
                reference: None,
                reference_hash: None,
                decimals: 18,
            },
        };

        // Register the contract itself and mint the reserve.
        this.token.internal_register_account(&env::current_account_id());
        this.token.internal_deposit(&env::current_account_id(), reserve);

        Nep141Event::FtMint(vec![FtMintData {
            owner_id: env::current_account_id().to_string(),
            amount: U128(reserve),
            memo: Some("initial reserve".to_string()),
        }])
        .emit();

        this
    }

    // -----------------------------------------------------------------------
    // set_next_challenge — DAO only
    // -----------------------------------------------------------------------

    /// Set a new challenge. Only `dao_account_id` may call this.
    ///
    /// `start_ns` and `end_ns` are **nanoseconds** (u64).
    /// `challenge_id` is a free-form string (non-empty, ≤32 chars, unique).
    pub fn set_next_challenge(
        &mut self,
        challenge_id: String,
        steps_per_day: u32,
        days_required: u8,
        start_ns: U64,
        end_ns: U64,
    ) {
        require!(
            env::predecessor_account_id() == self.dao_account_id,
            "only the DAO may set challenges"
        );
        let start: u64 = start_ns.into();
        let end: u64   = end_ns.into();
        let now = env::block_timestamp();

        require!(!challenge_id.is_empty(), "challenge_id must not be empty");
        require!(challenge_id.len() <= 32, "challenge_id must be <= 32 chars");
        require!(end > start, "end must be strictly after start");
        require!(start > now, "challenge cannot start in the past");
        require!(steps_per_day >= 1 && steps_per_day <= 100_000, "steps_per_day 1..=100_000");
        require!(days_required >= 1 && days_required <= 7, "days_required 1..=7");
        require!(
            self.challenges.get(&challenge_id).is_none(),
            "challenge_id already used"
        );

        // Check for time-range overlap with any existing challenge.
        for (_id, existing) in self.challenges.iter() {
            if start < existing.end_ns && existing.start_ns < end {
                env::panic_str(&format!(
                    "overlaps existing challenge '{}' ({}-{})",
                    existing.challenge_id, existing.start_ns, existing.end_ns
                ));
            }
        }

        self.challenges.insert(
            &challenge_id,
            &Challenge {
                challenge_id: challenge_id.clone(),
                steps_per_day,
                days_required,
                start_ns: start,
                end_ns: end,
                enrolled_count: 0,
                submitted_count: 0,
                qualifier_count: 0,
                outcome: ChallengeOutcome::Pending,
            },
        );

        PactEvent::ChallengeSet(vec![ChallengeSetData {
            challenge_id,
            steps_per_day,
            days_required,
            start_ns: start,
            end_ns: end,
        }])
        .emit();
    }

    // -----------------------------------------------------------------------
    // enroll
    // -----------------------------------------------------------------------

    /// Enroll the caller in a challenge.
    ///
    /// Requirements:
    /// - Caller must have called `storage_deposit` (NEP-145) first.
    /// - Attached deposit MUST equal exactly `stake_yocto`.
    /// - Challenge must exist and not have started.
    /// - Caller must not already be enrolled.
    #[payable]
    pub fn enroll(&mut self, challenge_id: String) {
        let caller   = env::predecessor_account_id();
        let attached = env::attached_deposit().as_yoctonear();

        require!(
            attached == self.stake_yocto,
            &format!("attached deposit must be exactly {} yoctoNEAR", self.stake_yocto)
        );
        require!(
            self.token.accounts.contains_key(&caller),
            "caller must call storage_deposit before enrolling"
        );

        let mut challenge = self.challenges.get(&challenge_id)
            .unwrap_or_else(|| env::panic_str(&format!("challenge '{}' not found", challenge_id)));

        require!(
            env::block_timestamp() < challenge.start_ns,
            "challenge has already started; enrollment closed"
        );
        require!(
            matches!(challenge.outcome, ChallengeOutcome::Pending),
            "challenge already settled"
        );

        let key = ck(&challenge_id, &caller);
        require!(self.enrollments.get(&key).is_none(), "already enrolled");

        self.enrollments.insert(&key, &self.stake_yocto);
        challenge.enrolled_count += 1;
        self.challenges.insert(&challenge_id, &challenge);

        PactEvent::Enroll(vec![EnrollData {
            challenge_id,
            account_id: caller.to_string(),
            stake_yocto: U128(self.stake_yocto),
        }])
        .emit();
    }

    // -----------------------------------------------------------------------
    // submit_completion
    // -----------------------------------------------------------------------

    /// Submit a backend-signed attestation for `account_id`.
    ///
    /// The caller may be `account_id` itself or any relayer; the attested
    /// `account_id` decides who is credited.
    ///
    /// The contract constructs the canonical Borsh payload from its own state
    /// and verifies the Ed25519 signature. Allowed until
    /// `end_ns + submission_grace_secs * 1_000_000_000`.
    pub fn submit_completion(
        &mut self,
        challenge_id: String,
        account_id: AccountId,
        passed: bool,
        expiry: U64,        // unix seconds
        signature: Base64VecU8,
    ) {
        let now_ns = env::block_timestamp();
        let now_s  = now_ns / 1_000_000_000;
        let expiry_s: u64 = expiry.into();

        let mut challenge = self.challenges.get(&challenge_id)
            .unwrap_or_else(|| env::panic_str(&format!("challenge '{}' not found", challenge_id)));

        let grace_ns   = self.submission_grace_secs * 1_000_000_000;
        let deadline_ns = challenge.end_ns.saturating_add(grace_ns);

        require!(now_ns >= challenge.end_ns, "challenge has not ended yet");
        require!(now_ns <= deadline_ns,      "submission grace period has expired");

        let key = ck(&challenge_id, &account_id);
        require!(self.enrollments.get(&key).is_some(), "account_id is not enrolled");
        require!(self.submissions.get(&key).is_none(), "completion already submitted");

        let sig_bytes = &signature.0;
        require!(sig_bytes.len() == 64, "signature must be 64 bytes");
        let mut sig = [0u8; 64];
        sig.copy_from_slice(sig_bytes);

        let attestation = SettlementAttestation {
            schema_tag:   SCHEMA_TAG.to_string(),
            contract_id:  env::current_account_id().to_string(),
            challenge_id: challenge_id.clone(),
            account_id:   account_id.to_string(),
            passed,
            expiry: expiry_s,
        };

        let key_bytes = match ed25519_key_bytes(&self.attestation_public_key) {
            Some(k) => k,
            None    => env::panic_str("attestation_public_key is not ed25519"),
        };
        require!(
            verify_with_expiry(&key_bytes, &attestation, &sig, now_s),
            "attestation signature invalid or expired"
        );

        self.submissions.insert(&key, &passed);
        if passed {
            self.qualifiers.insert(&key, &true);
        }
        challenge.submitted_count += 1;
        if passed { challenge.qualifier_count += 1; }
        self.challenges.insert(&challenge_id, &challenge);

        PactEvent::CompletionSubmitted(vec![CompletionSubmittedData {
            challenge_id,
            account_id: account_id.to_string(),
            passed,
            expiry: expiry_s,
        }])
        .emit();
    }

    // -----------------------------------------------------------------------
    // settle_week
    // -----------------------------------------------------------------------

    /// Settle the challenge. Callable by anyone, only once, only after
    /// `end_ns + submission_grace_secs * 1_000_000_000`.
    ///
    /// Uses stored counters only — no member iteration.
    pub fn settle_week(&mut self, challenge_id: String) {
        let now_ns    = env::block_timestamp();
        let grace_ns  = self.submission_grace_secs * 1_000_000_000;

        let mut challenge = self.challenges.get(&challenge_id)
            .unwrap_or_else(|| env::panic_str(&format!("challenge '{}' not found", challenge_id)));

        require!(
            matches!(challenge.outcome, ChallengeOutcome::Pending),
            "challenge already settled"
        );
        let deadline_ns = challenge.end_ns.saturating_add(grace_ns);
        require!(
            now_ns > deadline_ns,
            "settlement not available until after end + submission_grace_secs"
        );

        let enrolled   = challenge.enrolled_count;
        let qualifiers = challenge.qualifier_count;

        let outcome = if enrolled < self.min_members {
            ChallengeOutcome::Void
        } else if qualifiers == 0
            || qualifiers * 100 < enrolled * (self.quorum_pct as u64)
        {
            // Failure — burn min(burn_amount, reserve_balance).
            let burn_actual = self.reserve_balance.min(self.burn_amount);
            self.reserve_balance -= burn_actual;
            self.total_burned    += burn_actual;
            if burn_actual > 0 {
                self.token.internal_withdraw(&env::current_account_id(), burn_actual);
                Nep141Event::FtBurn(vec![FtBurnData {
                    owner_id: env::current_account_id().to_string(),
                    amount: U128(burn_actual),
                    memo: Some(format!("failed week: {}", challenge_id)),
                }])
                .emit();
            }
            ChallengeOutcome::Failure { burn_actual: U128(burn_actual) }
        } else {
            // Success — mint floor(mint_amount / qualifiers) * qualifiers.
            let reward_per = self.mint_amount / (qualifiers as u128);
            let total_mint = reward_per * (qualifiers as u128);
            self.reward_escrow += total_mint;
            self.total_minted  += total_mint;
            if total_mint > 0 {
                self.token.internal_deposit(&env::current_account_id(), total_mint);
                Nep141Event::FtMint(vec![FtMintData {
                    owner_id: env::current_account_id().to_string(),
                    amount: U128(total_mint),
                    memo: Some(format!("reward escrow: {}", challenge_id)),
                }])
                .emit();
            }
            ChallengeOutcome::Success { reward_per_qualifier: U128(reward_per) }
        };

        PactEvent::Settled(vec![SettledData {
            challenge_id: challenge_id.clone(),
            enrolled,
            qualifiers,
            outcome: format!("{:?}", outcome),
        }])
        .emit();

        challenge.outcome = outcome;
        self.challenges.insert(&challenge_id, &challenge);
    }

    // -----------------------------------------------------------------------
    // claim_reward
    // -----------------------------------------------------------------------

    /// Claim token reward for a qualifying member (pull-based, once per qualifier).
    pub fn claim_reward(&mut self, challenge_id: String) {
        let caller = env::predecessor_account_id();
        let key    = ck(&challenge_id, &caller);

        let challenge = self.challenges.get(&challenge_id)
            .unwrap_or_else(|| env::panic_str(&format!("challenge '{}' not found", challenge_id)));

        require!(
            self.qualifiers.get(&key).unwrap_or(false),
            "caller is not a qualifier"
        );
        require!(
            !self.reward_claimed.get(&key).unwrap_or(false),
            "reward already claimed"
        );
        require!(
            !self.claim_pending.get(&key).unwrap_or(false),
            "claim already in progress"
        );

        let reward = match &challenge.outcome {
            ChallengeOutcome::Success { reward_per_qualifier } => reward_per_qualifier.0,
            _ => env::panic_str("challenge did not succeed"),
        };

        // Optimistic: set pending before transfer to block re-entrancy.
        self.claim_pending.insert(&key, &true);
        self.token.internal_transfer(
            &env::current_account_id(),
            &caller,
            reward,
            Some("pact reward".to_string()),
        );
        self.reward_escrow -= reward;
        self.reward_claimed.insert(&key, &true);
        self.claim_pending.remove(&key);

        PactEvent::RewardClaimed(vec![ClaimRewardData {
            challenge_id,
            account_id: caller.to_string(),
            amount: U128(reward),
        }])
        .emit();
    }

    // -----------------------------------------------------------------------
    // withdraw_stake
    // -----------------------------------------------------------------------

    /// Withdraw the NEAR stake. Available after settlement in every outcome.
    /// Stakes are NEVER confiscated.
    ///
    /// Uses optimistic flag + Promise callback: sets `stake_pending` before
    /// the Promise, callback clears it and sets `stake_withdrawn` on success,
    /// or rolls back `stake_pending` on failure so the member can retry.
    pub fn withdraw_stake(&mut self, challenge_id: String) -> Promise {
        let caller = env::predecessor_account_id();
        let key    = ck(&challenge_id, &caller);

        let challenge = self.challenges.get(&challenge_id)
            .unwrap_or_else(|| env::panic_str(&format!("challenge '{}' not found", challenge_id)));

        require!(
            !matches!(challenge.outcome, ChallengeOutcome::Pending),
            "challenge not yet settled; call settle_week first"
        );
        let stake = self.enrollments.get(&key)
            .unwrap_or_else(|| env::panic_str("not enrolled or stake already withdrawn"));
        require!(
            !self.stake_withdrawn.get(&key).unwrap_or(false),
            "stake already withdrawn"
        );
        require!(
            !self.stake_pending.get(&key).unwrap_or(false),
            "withdrawal already in progress"
        );

        self.stake_pending.insert(&key, &true);

        Promise::new(caller.clone())
            .transfer(NearToken::from_yoctonear(stake))
            .then(
                Self::ext(env::current_account_id())
                    .with_static_gas(Gas::from_tgas(5))
                    .on_stake_withdrawn(challenge_id, caller),
            )
    }

    /// Callback for `withdraw_stake`.
    #[private]
    pub fn on_stake_withdrawn(
        &mut self,
        challenge_id: String,
        account_id: AccountId,
        #[callback_result] result: Result<(), near_sdk::PromiseError>,
    ) {
        let key = ck(&challenge_id, &account_id);
        if result.is_ok() {
            self.stake_withdrawn.insert(&key, &true);
            self.stake_pending.remove(&key);
            PactEvent::StakeWithdrawn(vec![StakeWithdrawnData {
                challenge_id,
                account_id: account_id.to_string(),
                amount: U128(self.stake_yocto),
            }])
            .emit();
        } else {
            // Transfer failed — roll back so member can retry.
            self.stake_pending.remove(&key);
            log!(
                "stake withdrawal failed for {} in challenge {}; member can retry",
                account_id, challenge_id
            );
        }
    }

    // -----------------------------------------------------------------------
    // View methods
    // -----------------------------------------------------------------------

    pub fn get_rules_hash(&self) -> String {
        self.rules_hash.clone()
    }
    pub fn get_attestation_public_key(&self) -> PublicKey {
        self.attestation_public_key.clone()
    }
    pub fn get_dao_account_id(&self) -> AccountId {
        self.dao_account_id.clone()
    }
    pub fn get_stake_yocto(&self) -> U128 { U128(self.stake_yocto) }
    pub fn get_quorum_pct(&self) -> u8    { self.quorum_pct }
    pub fn get_mint_amount(&self) -> U128 { U128(self.mint_amount) }
    pub fn get_burn_amount(&self) -> U128 { U128(self.burn_amount) }
    pub fn get_reserve_amount(&self) -> U128 { U128(self.reserve_amount) }
    pub fn get_min_members(&self) -> u64  { self.min_members }
    pub fn get_submission_grace_secs(&self) -> u64 { self.submission_grace_secs }
    pub fn get_reserve_balance(&self) -> U128 { U128(self.reserve_balance) }
    pub fn get_reward_escrow(&self) -> U128   { U128(self.reward_escrow) }
    pub fn get_total_minted(&self) -> U128    { U128(self.total_minted) }
    pub fn get_total_burned(&self) -> U128    { U128(self.total_burned) }

    pub fn get_challenge(&self, challenge_id: String) -> Option<Challenge> {
        self.challenges.get(&challenge_id)
    }
    pub fn get_challenges(&self, from_index: Option<u64>, limit: Option<u64>) -> Vec<Challenge> {
        self.challenges
            .values()
            .skip(from_index.unwrap_or(0) as usize)
            .take(limit.unwrap_or(50) as usize)
            .collect()
    }
    pub fn is_enrolled(&self, challenge_id: String, account_id: AccountId) -> bool {
        self.enrollments.get(&ck(&challenge_id, &account_id)).is_some()
    }
    pub fn has_submitted(&self, challenge_id: String, account_id: AccountId) -> bool {
        self.submissions.get(&ck(&challenge_id, &account_id)).is_some()
    }
    pub fn is_qualifier(&self, challenge_id: String, account_id: AccountId) -> bool {
        self.qualifiers.get(&ck(&challenge_id, &account_id)).unwrap_or(false)
    }
    pub fn has_withdrawn_stake(&self, challenge_id: String, account_id: AccountId) -> bool {
        self.stake_withdrawn.get(&ck(&challenge_id, &account_id)).unwrap_or(false)
    }
    pub fn has_claimed_reward(&self, challenge_id: String, account_id: AccountId) -> bool {
        self.reward_claimed.get(&ck(&challenge_id, &account_id)).unwrap_or(false)
    }
}

// ---------------------------------------------------------------------------
// NEP-141 / NEP-145 / NEP-148 trait delegations
// ---------------------------------------------------------------------------

#[near]
impl FungibleTokenCore for PactContract {
    #[payable]
    fn ft_transfer(&mut self, receiver_id: AccountId, amount: U128, memo: Option<String>) {
        self.token.ft_transfer(receiver_id, amount, memo)
    }
    #[payable]
    fn ft_transfer_call(
        &mut self,
        receiver_id: AccountId,
        amount: U128,
        memo: Option<String>,
        msg: String,
    ) -> PromiseOrValue<U128> {
        self.token.ft_transfer_call(receiver_id, amount, memo, msg)
    }
    fn ft_total_supply(&self) -> U128 { self.token.ft_total_supply() }
    fn ft_balance_of(&self, account_id: AccountId) -> U128 {
        self.token.ft_balance_of(account_id)
    }
}

#[near]
impl near_contract_standards::fungible_token::resolver::FungibleTokenResolver for PactContract {
    #[private]
    fn ft_resolve_transfer(
        &mut self,
        sender_id: AccountId,
        receiver_id: AccountId,
        amount: U128,
    ) -> U128 {
        self.token.ft_resolve_transfer(sender_id, receiver_id, amount)
    }
}

#[near]
impl StorageManagement for PactContract {
    #[payable]
    fn storage_deposit(
        &mut self,
        account_id: Option<AccountId>,
        registration_only: Option<bool>,
    ) -> StorageBalance {
        self.token.storage_deposit(account_id, registration_only)
    }
    #[payable]
    fn storage_withdraw(&mut self, amount: Option<NearToken>) -> StorageBalance {
        self.token.storage_withdraw(amount)
    }
    fn storage_unregister(&mut self, force: Option<bool>) -> bool {
        self.token.storage_unregister(force)
    }
    fn storage_balance_bounds(&self) -> StorageBalanceBounds {
        self.token.storage_balance_bounds()
    }
    fn storage_balance_of(&self, account_id: AccountId) -> Option<StorageBalance> {
        self.token.storage_balance_of(account_id)
    }
}

#[near]
impl FungibleTokenMetadataProvider for PactContract {
    fn ft_metadata(&self) -> FungibleTokenMetadata {
        self.metadata.clone()
    }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/// Parse a canonical base-10 U128 string. Panics loudly on bad input.
pub fn parse_u128(value: &str, field: &str) -> u128 {
    let bytes = value.as_bytes();
    let ok = match bytes.first() {
        None => false,
        Some(b'0') if bytes.len() > 1 => false, // leading zero
        Some(first) if !first.is_ascii_digit() => false,
        _ => bytes.iter().all(|b| b.is_ascii_digit()),
    };
    if !ok {
        env::panic_str(&format!(
            "{field} must be a canonical base-10 string, got {value:?}"
        ));
    }
    value
        .parse::<u128>()
        .unwrap_or_else(|_| env::panic_str(&format!("{field} overflows u128: {value:?}")))
}
