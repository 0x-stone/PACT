"""
Canonical policy definitions used by the DAO scripts.

Vote threshold computation (from policy.rs to_weight):
  Ratio(num, denom).to_weight(group_size) = floor(group_size * num / denom) + 1

With 3-member council:
  FunctionCall [1,2]: floor(3*1/2)+1 = 1+1 = 2  (any 2 of 3)
  Transfer     [2,3]: floor(3*2/3)+1 = 2+1 = 3  (unanimous — all 3)

With 4-member council (transitional, during policy proposal #1):
  Any kind [1,2]: floor(4*1/2)+1 = 2+1 = 3  (any 3 of 4)

Tiering note: Sputnik v2 enforces per-kind thresholds, NOT per-amount.
A Transfer of 1 yocto uses the same threshold as a Transfer of 10 NEAR.
Amount-based tiers are not possible on-chain. Documented here and in README.

One absent council member blocks Transfer (unanimous). This is intentional
for the prize payment flow — it prevents any 2 members from diverting funds
without the third's consent.
"""

# Proposal bond: 1 NEAR (1_000_000_000_000_000_000_000_000 yocto)
# Returned on Approved or Rejected; NOT returned on Removed (spam).
PROPOSAL_BOND = "1000000000000000000000000"

# Proposal period: 7 days in nanoseconds
PROPOSAL_PERIOD = "604800000000000"

# Council permissions: can do everything except RemoveProposal
COUNCIL_PERMISSIONS = [
    "*:AddProposal",
    "*:VoteApprove",
    "*:VoteReject",
    "*:VoteRemove",
    "*:Finalize",
]

# Everyone can add proposals
ALL_PERMISSIONS = ["*:AddProposal"]


def vote_policy_ratio(num: int, denom: int) -> dict:
    """RoleWeight ratio threshold, quorum=0."""
    return {
        "weight_kind": "RoleWeight",
        "quorum":      "0",
        "threshold":   [num, denom],
    }


def build_policy(council_members: list[str]) -> dict:
    """
    Build the full Current policy for a given council.

    Per-kind vote_policy on the council role:
      "call"     (FunctionCall) → [1,2]  → 2-of-3
      "transfer" (Transfer)     → [2,3]  → 3-of-3 (unanimous)

    All other kinds use the default [1,2].
    """
    return {
        "roles": [
            {
                "name":        "all",
                "kind":        "Everyone",
                "permissions": ALL_PERMISSIONS,
                "vote_policy": {},
            },
            {
                "name":        "council",
                "kind":        {"Group": council_members},
                "permissions": COUNCIL_PERMISSIONS,
                "vote_policy": {
                    # FunctionCall proposals: any 2 of 3 approve
                    "call":     vote_policy_ratio(1, 2),
                    # Transfer proposals: unanimous (all 3 must approve)
                    "transfer": vote_policy_ratio(2, 3),
                },
            },
        ],
        "default_vote_policy": vote_policy_ratio(1, 2),
        "proposal_bond":               PROPOSAL_BOND,
        "proposal_period":             PROPOSAL_PERIOD,
        "bounty_bond":                 PROPOSAL_BOND,
        "bounty_forgiveness_period":   "86400000000000",
    }
