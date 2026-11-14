"""
Sputnik v2 DAO helpers.

Proposal format verified against:
  github.com/near-daos/sputnik-dao-contract/blob/master/sputnikdao2/src/proposals.rs

act_proposal requires the full proposal kind to be passed back as a
verification argument (ERR_WRONG_KIND check in source). The scripts
fetch the live proposal kind before voting.

Policy label used for per-kind vote_policy keys:
  FunctionCall → "call"
  Transfer     → "transfer"
  ChangePolicy → "policy"
  AddMemberToRole → "add_member_to_role"
  RemoveMemberFromRole → "remove_member_from_role"

Bond mechanics (from source):
  Rejected → bond IS returned to proposer
  Removed  → bond is NOT returned (spam)
  Approved → bond IS returned on successful execution callback
"""
import base64, json, time

from dao.config import DAO_ID, PROPOSAL_BOND_YOCTO
from dao.near_rpc import view, near_cli, extract_tx_hash


# ── Proposal constructors ──────────────────────────────────────────────────

def make_change_policy_proposal(description: str, new_policy: dict) -> dict:
    """Full ChangePolicy proposal input.

    VersionedPolicy is serde(untagged) — Current(Policy) serializes as the
    Policy object directly, with no "Current" wrapper key.
    Default(Vec<AccountId>) serializes as a JSON array.
    So we pass the policy dict directly, not {"Current": policy}.
    """
    return {
        "proposal": {
            "description": description,
            "kind": {"ChangePolicy": {"policy": new_policy}},
        }
    }


def make_function_call_proposal(
    description: str,
    receiver_id: str,
    method_name: str,
    args: dict,
    deposit_yocto: str = "0",
    gas: str = "100000000000000",
) -> dict:
    args_b64 = base64.b64encode(json.dumps(args).encode()).decode()
    return {
        "proposal": {
            "description": description,
            "kind": {
                "FunctionCall": {
                    "receiver_id": receiver_id,
                    "actions": [{
                        "method_name": method_name,
                        "args":        args_b64,
                        "deposit":     deposit_yocto,
                        "gas":         gas,
                    }],
                }
            },
        }
    }


def make_transfer_proposal(
    description: str,
    receiver_id: str,
    amount_yocto: str,
) -> dict:
    """Transfer NEAR (token_id="" means native NEAR)."""
    return {
        "proposal": {
            "description": description,
            "kind": {
                "Transfer": {
                    "token_id":    "",
                    "receiver_id": receiver_id,
                    "amount":      amount_yocto,
                    "msg":         None,
                }
            },
        }
    }


# ── Add proposal ──────────────────────────────────────────────────────────

def add_proposal(proposal_input: dict, sign_as: str) -> tuple[int, str]:
    """Submit a proposal. Returns (proposal_id, tx_hash). Attaches 1 NEAR bond."""
    output = near_cli(
        DAO_ID, "add_proposal",
        json.dumps(proposal_input),
        sign_as=sign_as,
        deposit=PROPOSAL_BOND_YOCTO,
        gas="300.0 Tgas",
    )
    tx_hash = extract_tx_hash(output)
    proposal_id = view(DAO_ID, "get_last_proposal_id") - 1
    return proposal_id, tx_hash


# ── Vote ──────────────────────────────────────────────────────────────────

def act_proposal(proposal_id: int, action: str, sign_as: str) -> str:
    """Vote on a proposal. Fetches live kind first (ERR_WRONG_KIND check)."""
    proposal = view(DAO_ID, "get_proposal", {"id": proposal_id})
    if proposal is None:
        raise RuntimeError(f"Proposal {proposal_id} not found")
    args = {"id": proposal_id, "action": action, "proposal": proposal["kind"]}
    output = near_cli(
        DAO_ID, "act_proposal",
        json.dumps(args),
        sign_as=sign_as,
        deposit="0",
        gas="300.0 Tgas",
    )
    return extract_tx_hash(output)


# ── Status helpers ────────────────────────────────────────────────────────

def get_proposal(proposal_id: int) -> dict:
    p = view(DAO_ID, "get_proposal", {"id": proposal_id})
    if p is None:
        raise RuntimeError(f"Proposal {proposal_id} not found")
    return p


def assert_proposal_status(proposal_id: int, expected: str) -> dict:
    """Raise if the proposal doesn't have the expected status."""
    p = get_proposal(proposal_id)
    status = p.get("status")
    if status != expected:
        raise RuntimeError(
            f"Proposal #{proposal_id}: expected status={expected!r}, got {status!r}\n"
            f"Full proposal: {json.dumps(p, indent=2)}"
        )
    return p


def get_policy() -> dict:
    return view(DAO_ID, "get_policy")


def council_members() -> list[str]:
    policy = get_policy()
    for role in policy.get("roles", []):
        if role.get("name") == "council":
            kind = role.get("kind", {})
            if isinstance(kind, dict) and "Group" in kind:
                return kind["Group"]
    return []
