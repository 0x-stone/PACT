#!/usr/bin/env python3
"""
vote.py — Cast a vote on a DAO proposal.

Usage:
  python -m dao.vote --proposal-id 2 --action VoteApprove --sign-as 0xgoku.testnet
  python -m dao.vote --proposal-id 2 --action VoteReject  --sign-as 0xpiccolo.testnet

Fetches the live proposal kind first (required by Sputnik's ERR_WRONG_KIND check).
Prints the transaction hash and the resulting proposal status.
"""
import argparse, json, sys
from pathlib import Path

_REPO = Path(__file__).resolve().parents[1]
if str(_REPO) not in sys.path:
    sys.path.insert(0, str(_REPO))

from dao.config import DAO_ID, require_env
from dao.sputnik import act_proposal, get_proposal


def main(argv=None):
    require_env()
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--proposal-id", type=int, required=True)
    parser.add_argument("--action",      required=True,
                        choices=["VoteApprove", "VoteReject", "VoteRemove"])
    parser.add_argument("--sign-as",     required=True)
    args = parser.parse_args(argv)

    # Show current state before voting
    p_before = get_proposal(args.proposal_id)
    print(f"Proposal #{args.proposal_id} before vote:")
    print(f"  status : {p_before['status']}")
    print(f"  votes  : {json.dumps(p_before.get('votes', {}))}")

    tx_hash = act_proposal(args.proposal_id, args.action, sign_as=args.sign_as)
    print(f"\nVote TX : {tx_hash}")

    p_after = get_proposal(args.proposal_id)
    print(f"Proposal #{args.proposal_id} after vote:")
    print(f"  status : {p_after['status']}")
    print(f"  votes  : {json.dumps(p_after.get('votes', {}))}")
    print(f"Explorer: https://explorer.testnet.near.org/transactions/{tx_hash}")


if __name__ == "__main__":
    main()
