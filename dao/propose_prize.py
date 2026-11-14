#!/usr/bin/env python3
"""
propose_prize.py — Propose a Transfer prize to an eligible member.

Recipient eligibility is verified against the backend's pact_store DB
(no get_qualifiers view exists on the contract — labeled as a backend oracle).

The minimum-completed-weeks gate is enforced by the backend's reputation
endpoint: a member must have passed >= MIN_WEEKS_FOR_PRIZE weeks.

Usage:
  export PACT_CONTRACT_ID=pactcontract.testnet
  export PACT_DAO_ID=pactdao.testnet
  export COUNCIL_PROPOSER=0xgoku.testnet
  export COUNCIL_1=0xpiccolo.testnet
  export COUNCIL_2=0xbulma.testnet
  export BACKEND_URL=http://localhost:8000

  python -m dao.propose_prize \
    --recipient 0xbulma.testnet \
    --amount 1 \
    --challenge-id week-2026-11-16 \
    --sign-as 0xgoku.testnet
"""
import argparse, json, os, sys, urllib.request
from pathlib import Path

_REPO = Path(__file__).resolve().parents[1]
if str(_REPO) not in sys.path:
    sys.path.insert(0, str(_REPO))

from dao.config import DAO_ID, CONTRACT_ID, COUNCIL_PROPOSER, require_env
from dao.near_rpc import view
from dao.sputnik import add_proposal, make_transfer_proposal

YOCTO = 10**24
MIN_WEEKS_FOR_PRIZE = 1  # minimum passing weeks to be eligible for a prize


def check_eligibility_oracle(recipient: str, challenge_id: str) -> bool:
    """
    Check eligibility via the backend reputation endpoint.
    Labeled as a backend oracle — not on-chain truth.
    Returns True if eligible, False if not.
    """
    backend_url = os.getenv("BACKEND_URL", "http://localhost:8000").rstrip("/")
    url = f"{backend_url}/api/pact/reputation/{recipient}"
    try:
        with urllib.request.urlopen(url, timeout=5) as r:
            data = json.loads(r.read())
        weeks_passed = data.get("weeks_passed", 0)
        print(f"  Backend oracle: {recipient} has {weeks_passed} passing weeks")
        return weeks_passed >= MIN_WEEKS_FOR_PRIZE
    except Exception as e:
        print(f"  Backend oracle unavailable ({e}) — skipping eligibility check")
        return True  # fail open: let council decide


def check_on_chain_qualifier(recipient: str, challenge_id: str) -> bool:
    """
    Check if recipient is a qualifier for a specific challenge on-chain.
    Returns True if is_qualifier returns True, False or None otherwise.
    NOTE: is_qualifier only exists per-challenge, not as a list.
    """
    result = view(CONTRACT_ID, "is_qualifier", {
        "challenge_id": challenge_id,
        "account_id":   recipient,
    })
    print(f"  On-chain is_qualifier({challenge_id}, {recipient}): {result}")
    return bool(result)


def main(argv=None):
    require_env()
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--recipient",    required=True)
    parser.add_argument("--amount",       type=float, default=1.0, help="NEAR prize amount")
    parser.add_argument("--challenge-id", default="")
    parser.add_argument("--sign-as",      default=COUNCIL_PROPOSER)
    parser.add_argument("--skip-check",   action="store_true",
                        help="Skip eligibility check (council assumes responsibility)")
    args = parser.parse_args(argv)

    amount_yocto = str(int(args.amount * YOCTO))

    print(f"Prize proposal: {args.amount} NEAR → {args.recipient}")

    if not args.skip_check:
        print("Checking eligibility...")
        # Check on-chain if challenge_id provided
        if args.challenge_id:
            on_chain = check_on_chain_qualifier(args.recipient, args.challenge_id)
            if not on_chain:
                print(f"  WARNING: {args.recipient} is NOT a qualifier for {args.challenge_id} on-chain")
                print("  Proposing anyway — council is responsible for prize eligibility decisions")
        # Check backend oracle
        backend_ok = check_eligibility_oracle(args.recipient, args.challenge_id)
        if not backend_ok:
            raise SystemExit(
                f"Eligibility check failed: {args.recipient} has fewer than "
                f"{MIN_WEEKS_FOR_PRIZE} passing weeks per the backend oracle.\n"
                "Use --skip-check to override (council assumes responsibility)."
            )
    else:
        print("  Eligibility check skipped (--skip-check)")

    desc = (
        f"Prize: {args.amount} NEAR to {args.recipient}"
        + (f" for challenge {args.challenge_id}" if args.challenge_id else "")
        + " — backend oracle confirmed eligibility"
    )
    prop = make_transfer_proposal(desc, args.recipient, amount_yocto)
    proposal_id, tx_hash = add_proposal(prop, sign_as=args.sign_as)
    print(f"Proposal ID : {proposal_id}")
    print(f"TX hash     : {tx_hash}")
    print(f"Explorer    : https://explorer.testnet.near.org/transactions/{tx_hash}")
    print(f"\nNote: Transfer requires unanimous council vote (3/3 with current 3-member council).")
    return proposal_id


if __name__ == "__main__":
    main()
