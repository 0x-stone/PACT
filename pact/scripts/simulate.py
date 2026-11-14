#!/usr/bin/env python3
"""Deterministic weekly simulation of the community fitness pact.

Consumer #3 of the single source of truth (pact/rules.json via
pact/rules.py). It replays the exact settlement semantics the backend will
apply and the contract will enforce: pass/fail per member per week, quorum
via integer math, capped split mint on success, reserve burn on failure.
Seeded RNG, no network access, no files written — same seed, same output.

Population model (simple on purpose, honest about being synthetic):
- Each member draws one "fitness base" once, as a fraction of
  steps_per_day (uniform 0.85..1.25, scaled by --fitness): some members sit
  below the daily target, some above.
- Each day's steps = base * noise (uniform 0.7..1.3).
- A member passes the week if they hit >= steps_per_day on at least
  days_required of the 7 days.

Usage:
    python pact/scripts/simulate.py --weeks 12 --members 8 --seed 7
    python pact/scripts/simulate.py --fitness 1.2   # fitter population
"""

from __future__ import annotations

import argparse
import random
import sys
from pathlib import Path
from typing import Optional

_REPO_ROOT = Path(__file__).resolve().parents[2]
if str(_REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(_REPO_ROOT))

from pact.rules import PactRules, load_rules  # noqa: E402

# The rules' amounts are whole base units; the defaults in rules.json assume
# the contract's NEP-148 metadata will declare 18 decimals. This is display
# sugar only — all settlement math uses base units.
_DISPLAY_DECIMALS = 18


def fmt_amount(base_units: int) -> str:
    tokens = base_units / 10**_DISPLAY_DECIMALS
    return f"{base_units:,} base units (~{tokens:.4f} tokens @ 18 dp)"


def fmt_near(yocto: int) -> str:
    """Stake is NEAR (24 decimals), not a pact token (18 dp) — never mix them."""
    return f"{yocto / 10**24:.6g} NEAR ({yocto:,} yoctoNEAR)"


def simulate_member_days(base_steps: int, rules: PactRules, rng: random.Random) -> int:
    """Days this member met steps_per_day during one 7-day challenge week."""
    return sum(
        1
        for _ in range(7)
        if int(base_steps * rng.uniform(0.7, 1.3)) >= rules.steps_per_day
    )


def run_simulation(weeks: int, members: int, seed: int, fitness: float = 1.0) -> dict:
    """Run the weekly settlement loop and print a per-week ledger.

    Returns a summary dict. Fully deterministic for a given
    (weeks, members, seed, fitness) triple.
    """
    rules = load_rules()
    if members < rules.min_members:
        raise SystemExit(
            f"error: members={members} is below min_members="
            f"{rules.min_members}; such a week would never settle"
        )
    if weeks < 1:
        raise SystemExit(f"error: weeks={weeks} must be >= 1")
    if fitness <= 0:
        raise SystemExit(f"error: fitness={fitness} must be > 0")

    rng = random.Random(seed)
    # Draw each member's fitness base once, around the daily target. The
    # fitness knob scales the whole population up/down to explore how the
    # mint/burn mechanics respond; rules themselves never change.
    member_bases = [
        int(rules.steps_per_day * rng.uniform(0.85, 1.25) * fitness)
        for _ in range(members)
    ]

    reserve = rules.reserve_amount_int
    total_minted = 0
    total_burned = 0
    met_weeks = 0
    shortfall_weeks = 0

    print(
        f"rules: {rules.steps_per_day} steps/day, {rules.days_required}/7 days, "
        f"quorum {rules.quorum_pct}%, stake {fmt_near(rules.stake_yocto_int)} "
        "(refundable)"
    )
    print(
        f"mint on success {fmt_amount(rules.mint_amount_int)} split floor-wise "
        f"among passers; burn on miss {fmt_amount(rules.burn_amount_int)}; "
        f"reserve {fmt_amount(rules.reserve_amount_int)}; "
        f"weeks start {rules.week_start_day_name}, {rules.day_boundary_timezone_policy}"
    )
    print(f"simulating {weeks} weeks x {members} members (seed {seed}, fitness {fitness})")

    for week in range(1, weeks + 1):
        passed = sum(
            1
            for base in member_bases
            if simulate_member_days(base, rules, rng) >= rules.days_required
        )

        if rules.quorum_met(members, passed):
            met_weeks += 1
            per_passer, minted = rules.mint_split(passed)
            total_minted += minted
            print(
                f"week {week:3}: enrolled={members} passed={passed} "
                f"quorum={rules.quorum_pct}% -> MET   mint {minted:,} "
                f"({per_passer:,} each of {passed} passers, remainder "
                f"{rules.mint_amount_int - minted:,} never minted)"
            )
        else:
            if reserve >= rules.burn_amount_int:
                burned = rules.burn_amount_int
                note = ""
            else:
                # Honest reporting: the rules do not define partial burns;
                # the contract stage must. The simulation flags it loudly.
                burned = reserve
                note = "  <- RESERVE SHORTFALL (partial burn, contract TBD)"
            reserve -= burned
            total_burned += burned
            if burned < rules.burn_amount_int:
                shortfall_weeks += 1
            print(
                f"week {week:3}: enrolled={members} passed={passed} "
                f"quorum={rules.quorum_pct}% -> MISS  burn {burned:,}{note}"
            )

    summary = {
        "weeks": weeks,
        "members": members,
        "seed": seed,
        "fitness": fitness,
        "quorum_met_weeks": met_weeks,
        "total_minted": total_minted,
        "total_burned": total_burned,
        "reserve_remaining": reserve,
        "shortfall_weeks": shortfall_weeks,
    }
    print("---")
    print(
        f"result: {met_weeks}/{weeks} weeks met quorum "
        f"({100.0 * met_weeks / weeks:.1f}%), "
        f"minted {fmt_amount(total_minted)}, burned {fmt_amount(total_burned)}, "
        f"reserve left {fmt_amount(reserve)}"
    )
    if shortfall_weeks:
        print(
            f"warning: {shortfall_weeks} week(s) could not burn in full — "
            "reserve exhausted; the contract stage must pick a policy."
        )
    return summary


def main(argv: Optional[list] = None) -> int:
    parser = argparse.ArgumentParser(
        description="Deterministic simulation of the fitness pact settlement."
    )
    parser.add_argument("--weeks", type=int, default=12, help="weeks to simulate")
    parser.add_argument("--members", type=int, default=8, help="enrolled members")
    parser.add_argument("--seed", type=int, default=7, help="RNG seed")
    parser.add_argument(
        "--fitness",
        type=float,
        default=1.0,
        help=(
            "scales the synthetic population's fitness around steps_per_day "
            "(1.0 = default spread; 1.2 = fitter community). Rules never change."
        ),
    )
    args = parser.parse_args(argv)
    run_simulation(args.weeks, args.members, args.seed, args.fitness)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
