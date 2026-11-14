//! NEP-297 events for the Pact contract.
//!
//! NEP-141 token events emit with standard "nep141", version "1.0.0".
//! This allows NearBlocks, NEAR Explorer, and ecosystem indexers to track
//! token mints, burns, transfers, total supply, and holders automatically.
//!
//! Pact application lifecycle events emit with standard "pact-contract".
//!
//! Format verified against near-sdk 5.30.1's `#[near(event_json)]` macro:
//! emitted as `EVENT_JSON:{...}` log lines readable by any NEP-297 indexer.

use near_sdk::json_types::U128;
use near_sdk::near;
use near_sdk::serde::Serialize;

// ---------------------------------------------------------------------------
// NEP-141 Token Event Data (NEP-297 Standard)
// ---------------------------------------------------------------------------

#[derive(Serialize)]
#[serde(crate = "near_sdk::serde")]
pub struct FtMintData {
    pub owner_id: String,
    pub amount: U128,
    pub memo: Option<String>,
}

pub type MintData = FtMintData;

#[derive(Serialize)]
#[serde(crate = "near_sdk::serde")]
pub struct FtBurnData {
    pub owner_id: String,
    pub amount: U128,
    pub memo: Option<String>,
}

pub type BurnData = FtBurnData;

// ---------------------------------------------------------------------------
// Pact Application Event Data
// ---------------------------------------------------------------------------

#[derive(Serialize)]
#[serde(crate = "near_sdk::serde")]
pub struct ChallengeSetData {
    pub challenge_id: String,
    pub steps_per_day: u32,
    pub days_required: u8,
    /// Nanoseconds (u64).
    pub start_ns: u64,
    /// Nanoseconds (u64).
    pub end_ns: u64,
}

#[derive(Serialize)]
#[serde(crate = "near_sdk::serde")]
pub struct EnrollData {
    pub challenge_id: String,
    pub account_id: String,
    pub stake_yocto: U128,
}

#[derive(Serialize)]
#[serde(crate = "near_sdk::serde")]
pub struct CompletionSubmittedData {
    pub challenge_id: String,
    pub account_id: String,
    pub passed: bool,
    /// Unix seconds.
    pub expiry: u64,
}

#[derive(Serialize)]
#[serde(crate = "near_sdk::serde")]
pub struct SettledData {
    pub challenge_id: String,
    pub enrolled: u64,
    pub qualifiers: u64,
    /// "Void", "Success { ... }", or "Failure { ... }" — the Debug repr of ChallengeOutcome.
    pub outcome: String,
}

#[derive(Serialize)]
#[serde(crate = "near_sdk::serde")]
pub struct ClaimRewardData {
    pub challenge_id: String,
    pub account_id: String,
    pub amount: U128,
}

#[derive(Serialize)]
#[serde(crate = "near_sdk::serde")]
pub struct StakeWithdrawnData {
    pub challenge_id: String,
    pub account_id: String,
    pub amount: U128,
}

// ---------------------------------------------------------------------------
// NEP-141 Standard Event Enum
// Emits: EVENT_JSON:{"standard":"nep141","version":"1.0.0","event":"ft_mint"|"ft_burn",...}
// ---------------------------------------------------------------------------

#[near(event_json(standard = "nep141"))]
pub enum Nep141Event {
    #[event_version("1.0.0")]
    FtMint(Vec<FtMintData>),

    #[event_version("1.0.0")]
    FtBurn(Vec<FtBurnData>),
}

// ---------------------------------------------------------------------------
// Pact Protocol Event Enum
// Emits: EVENT_JSON:{"standard":"pact-contract","version":"1.0.0","event":"...",...}
// ---------------------------------------------------------------------------

#[near(event_json(standard = "pact-contract"))]
pub enum PactEvent {
    #[event_version("1.0.0")]
    ChallengeSet(Vec<ChallengeSetData>),

    #[event_version("1.0.0")]
    Enroll(Vec<EnrollData>),

    #[event_version("1.0.0")]
    CompletionSubmitted(Vec<CompletionSubmittedData>),

    #[event_version("1.0.0")]
    Settled(Vec<SettledData>),

    #[event_version("1.0.0")]
    RewardClaimed(Vec<ClaimRewardData>),

    #[event_version("1.0.0")]
    StakeWithdrawn(Vec<StakeWithdrawnData>),
}
