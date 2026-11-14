#!/usr/bin/env python3
"""
propose_challenge.py — Propose a new weekly challenge via the DAO.

Usage:
  export PACT_CONTRACT_ID=pactcontract.testnet
  export PACT_DAO_ID=pactdao.testnet
  export COUNCIL_PROPOSER=0xgoku.testnet
  export COUNCIL_1=0xpiccolo.testnet
  export COUNCIL_2=0xbulma.testnet

  python -m dao.propose_challenge \
    --challenge-id "week-2026-11-16" \
    --steps 8000 \
    --days 5 \
    --start-offset-minutes 60 \
    --duration-days 7

Prints the proposal ID and transaction hash.
The start is computed as now + start-offset-minutes to ensure it is in the
future when the DAO vote executes (the contract rejects past start times).
"""
import argparse, json, sys, time
from pathlib import Path

_REPO = Path(__file__).resolve().parents[1]
if str(_REPO) not in sys.path:
    sys.path.insert(0, str(_REPO))

from dao.config import CONTRACT_ID, require_env, COUNCIL_PROPOSER
from dao.sputnik import add_proposal, make_function_call_proposal


def main(argv=None):
    require_env()
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--challenge-id",          required=True)
    parser.add_argument("--steps",                 type=int, default=8000)
    parser.add_argument("--days",                  type=int, default=5)
    parser.add_argument("--start-offset-minutes",  type=int, default=60,
                        help="Minutes from now when the challenge starts (must exceed vote time)")
    parser.add_argument("--duration-days",         type=int, default=7)
    parser.add_argument("--start-ns",              type=int, default=None, help="Explicit start timestamp in nanoseconds")
    parser.add_argument("--end-ns",                type=int, default=None, help="Explicit end timestamp in nanoseconds")
    parser.add_argument("--sign-as",               default=COUNCIL_PROPOSER)
    args = parser.parse_args(argv)

    if args.start_ns is not None and args.end_ns is not None:
        start_ns = args.start_ns
        end_ns   = args.end_ns
    else:
        now_ns   = int(time.time() * 1_000_000_000)
        start_ns = now_ns + args.start_offset_minutes * 60 * 1_000_000_000
        end_ns   = start_ns + args.duration_days * 24 * 3600 * 1_000_000_000

    challenge_args = {
        "challenge_id":  args.challenge_id,
        "steps_per_day": args.steps,
        "days_required": args.days,
        "start_ns":      str(start_ns),
        "end_ns":        str(end_ns),
    }
    print(f"Challenge args: {json.dumps(challenge_args, indent=2)}")

    prop = make_function_call_proposal(
        f"Set challenge {args.challenge_id}: "
        f"{args.steps} steps/day, {args.days} days/week",
        CONTRACT_ID,
        "set_next_challenge",
        challenge_args,
    )
    proposal_id, tx_hash = add_proposal(prop, sign_as=args.sign_as)
    print(f"Proposal ID : {proposal_id}")
    print(f"TX hash     : {tx_hash}")
    print(f"Explorer    : https://explorer.testnet.near.org/transactions/{tx_hash}")
    return proposal_id


if __name__ == "__main__":
    main()
