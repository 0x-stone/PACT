"""Typed loader/validator for the community fitness pact rules.

Single source of truth: ``pact/rules.json`` (one directory up from this
file). Four consumers import this exact module:

1. backend            -> ``backend/main.py`` (read-only ``GET /api/pact/rules``)
2. contract init      -> ``pact/scripts/init_contract.py``
3. simulation         -> ``pact/scripts/simulate.py``
4. deploy script      -> ``pact/scripts/deploy_contract.py``

Design decisions (be honest about what is trusted):
- Amount fields (stake_yocto, mint_amount, burn_amount, reserve_amount) are
  written as canonical base-10 strings, following the NEP-141 convention for
  U128 arguments. This avoids losing precision in JSON consumers that only
  have 53-bit numbers (JavaScript) and maps 1:1 onto contract init args.
  They are whole base units of the pact token; the decimals live in the
  contract's NEP-148 metadata, not here.
- Types are strict and unknown fields are forbidden: a typo in rules.json
  must fail loudly, never silently fall back to a default.
- day_boundary_timezone_policy is either the literal ``"UTC"`` or an IANA
  zone name (validated via the stdlib ``zoneinfo`` database). "Device local"
  is deliberately NOT accepted: eligibility must be deterministic and
  identical for every member, and device-local day boundaries are not.
- submission_grace_secs: seconds after a challenge ends during which
  attestations may still be submitted. Testnet default: 3600 (1 hour).
  Production recommended: 86400 (24 hours). Attestation expiry set by
  the backend MUST be >= challenge_end + submission_grace_secs.

The Rust contract receives these values once in ``new()`` (immutable
afterwards, exposed via view methods) and must re-implement the same bounds
and settlement math. This module is the Python mirror of that contract
logic; the two must be kept in sync by tests.
"""

from __future__ import annotations

import hashlib
import json
import re
from pathlib import Path
from typing import Optional, Union
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

# Default location of the single source of truth, relative to this file, so
# the loader works no matter which directory a consumer is started from.
DEFAULT_RULES_PATH = Path(__file__).resolve().parent / "rules.json"

# NEAR arguments are U128; anything larger cannot exist on-chain.
U128_MAX = 2**128 - 1

# Canonical decimal string: "0" or a number without leading zeros/sign/point.
_CANONICAL_BASE10 = re.compile(r"^(0|[1-9][0-9]*)$")

# ISO 8601 weekday numbering: 1 = Monday ... 7 = Sunday.
ISO_WEEKDAY_NAMES = {
    1: "Monday",
    2: "Tuesday",
    3: "Wednesday",
    4: "Thursday",
    5: "Friday",
    6: "Saturday",
    7: "Sunday",
}

_AMOUNT_FIELDS = ("stake_yocto", "mint_amount", "burn_amount", "reserve_amount")


class PactRules(BaseModel):
    """Validated pact rules. Mirrors exactly what the contract stores at init."""

    model_config = ConfigDict(extra="forbid", strict=True)

    steps_per_day: int = Field(
        ge=1,
        le=100_000,
        description=(
            "Minimum steps in a single day for that day to count towards the "
            "weekly challenge. Upper bound exists only to catch typo'd values."
        ),
    )
    days_required: int = Field(
        ge=1,
        le=7,
        description=(
            "How many of the 7 challenge-week days must reach steps_per_day "
            "for a member to pass the week."
        ),
    )
    stake_yocto: str = Field(
        description=(
            "Refundable stake per member, in yoctoNEAR (1 NEAR = 10^24), as a "
            "canonical base-10 string."
        )
    )
    quorum_pct: int = Field(
        ge=1,
        le=100,
        description=(
            "Percentage of enrolled members that must pass for the week to "
            "succeed. Integer-only; compared with integer math."
        ),
    )
    mint_amount: str = Field(
        description=(
            "Total pact tokens minted for one successful week, in whole base "
            "units, as a canonical base-10 string. Split equally among "
            "passers: each gets floor(mint_amount / passers) and only "
            "reward * passers is minted, so any remainder is never minted."
        )
    )
    burn_amount: str = Field(
        description=(
            "Pact tokens burned from the reserve when a week misses quorum, "
            "in whole base units, as a canonical base-10 string."
        )
    )
    min_members: int = Field(
        ge=1,
        description=(
            "Minimum enrollment for a week to settle at all. Guards against "
            "one-member 'communities' trivially passing quorum."
        ),
    )
    reserve_amount: str = Field(
        description=(
            "Initial pact-token reserve held by the contract for burns, in "
            "whole base units, as a canonical base-10 string. Must cover at "
            "least one burn_amount."
        )
    )
    week_start_day: int = Field(
        ge=1,
        le=7,
        description="ISO weekday the challenge week starts on (1=Monday..7=Sunday).",
    )
    day_boundary_timezone_policy: str = Field(
        description=(
            "How day boundaries are computed: 'UTC' or a fixed IANA zone "
            "name (e.g. 'Africa/Lagos'). Never device-local."
        )
    )
    submission_grace_secs: int = Field(
        ge=1,
        description=(
            "Seconds after a challenge end timestamp during which attestations "
            "may still be submitted and settle_week may be called. "
            "Testnet default: 3600 (1 hour). Production recommended: 86400 (24 h). "
            "Backend attestation expiry MUST be >= challenge_end + this value."
        ),
    )

    # ------------------------------------------------------------------
    # Validation
    # ------------------------------------------------------------------
    @field_validator(*_AMOUNT_FIELDS)
    @classmethod
    def _check_canonical_u128(cls, value: str) -> str:
        """Amounts must be canonical base-10 strings that fit in U128."""
        if not isinstance(value, str) or not _CANONICAL_BASE10.match(value):
            raise ValueError(
                "amount must be a canonical base-10 string without sign, "
                "decimal point or leading zeros (e.g. \"25000000000000000000\"), "
                f"got {value!r}"
            )
        if int(value) > U128_MAX:
            raise ValueError(
                f"amount {value} exceeds U128 max ({U128_MAX}) and cannot "
                "exist as a NEAR balance"
            )
        return value

    @field_validator("stake_yocto", "mint_amount", "burn_amount")
    @classmethod
    def _check_positive(cls, value: str) -> str:
        """A zero stake/mint/burn would make the pact mechanics meaningless."""
        if int(value) < 1:
            raise ValueError("amount must be at least 1 base unit (zero is not a pact)")
        return value

    @field_validator("day_boundary_timezone_policy")
    @classmethod
    def _check_timezone(cls, value: str) -> str:
        """Accept exactly 'UTC' or a valid IANA zone name; nothing else.

        Case-sensitive: 'utc' and 'Utc' are rejected — IANA zone names
        always start with an uppercase letter (e.g. 'America/New_York') and
        the special UTC zone is spelled exactly 'UTC'. This prevents the
        Windows/tzdata "utc" alias from slipping through.
        """
        if value == "UTC":
            return value
        # Reject any case-variant of "UTC" before the ZoneInfo lookup.
        # On Windows, ZoneInfo("utc") succeeds because tzdata ships "utc" as
        # an alias — but we require the canonical spelling.
        if value.upper() == "UTC":
            raise ValueError(
                "day_boundary_timezone_policy must be exactly 'UTC' "
                f"(case-sensitive); got {value!r}"
            )
        try:
            ZoneInfo(value)
        except (ZoneInfoNotFoundError, ValueError, KeyError) as exc:
            raise ValueError(
                "day_boundary_timezone_policy must be 'UTC' or a valid IANA "
                f"zone name (e.g. 'Africa/Lagos'); device-local boundaries "
                f"are not allowed because they are not deterministic. Got {value!r}"
            ) from exc
        return value

    @model_validator(mode="after")
    def _reserve_covers_burn(self) -> "PactRules":
        """The reserve must survive at least one full failed-week burn."""
        if self.reserve_amount_int < self.burn_amount_int:
            raise ValueError(
                "reserve_amount ("
                f"{self.reserve_amount_int}) must be >= burn_amount "
                f"({self.burn_amount_int}) so the first failed week can burn "
                "in full"
            )
        return self

    # ------------------------------------------------------------------
    # Convenience views (do not change the JSON representation)
    # ------------------------------------------------------------------
    @property
    def stake_yocto_int(self) -> int:
        return int(self.stake_yocto)

    @property
    def mint_amount_int(self) -> int:
        return int(self.mint_amount)

    @property
    def burn_amount_int(self) -> int:
        return int(self.burn_amount)

    @property
    def reserve_amount_int(self) -> int:
        return int(self.reserve_amount)

    @property
    def week_start_day_name(self) -> str:
        return ISO_WEEKDAY_NAMES[self.week_start_day]

    # ------------------------------------------------------------------
    # Deterministic settlement semantics (shared by backend + simulation;
    # the Rust contract must implement the identical logic)
    # ------------------------------------------------------------------
    def quorum_met(self, enrolled: int, passed: int) -> bool:
        """True when `passed` members reach quorum_pct of `enrolled`.

        Pure integer math: passed * 100 >= quorum_pct * enrolled. No
        floats, no rounding ambiguity, no AI. With quorum_pct=70 and 5
        enrolled members, 4 passes meet quorum (400 >= 350) and 3 do not
        (300 < 350).
        """
        if enrolled < 0 or passed < 0 or passed > enrolled:
            raise ValueError(
                f"nonsensical counts: enrolled={enrolled}, passed={passed}"
            )
        if enrolled == 0:
            # An empty pact cannot meet quorum, even at quorum_pct=1.
            return False
        return passed * 100 >= self.quorum_pct * enrolled

    def mint_split(self, passers: int) -> tuple[int, int]:
        """Per-passer reward and total minted for one successful week.

        reward = floor(mint_amount / passers); the contract mints only
        reward * passers, so any remainder is never minted and the weekly
        mint is capped at mint_amount regardless of headcount. If passers
        exceeds mint_amount (in base units) the floor is 0 and nothing is
        minted for that week.
        """
        if passers < 0:
            raise ValueError(f"passers must be >= 0, got {passers}")
        if passers == 0:
            return (0, 0)
        reward = self.mint_amount_int // passers
        return (reward, reward * passers)


def canonical_rules(rules: "PactRules") -> str:
    """The canonical JSON string used to compute the on-chain rules_hash.

    Includes ONLY the immutable on-chain economic parameters:
        stake_yocto, quorum_pct, mint_amount, burn_amount,
        reserve_amount, min_members, submission_grace_secs

    Excludes per-challenge parameters (steps_per_day, days_required) and
    display-only fields (week_start_day, day_boundary_timezone_policy).

    Amounts are decimal strings (as stored in rules.json — do NOT use
    model_dump() which returns Python ints for int fields). Keys are sorted
    alphabetically, compact separators (no spaces).

    The hash is a commitment label, not the source of truth. The contract's
    view methods (get_stake_yocto, get_quorum_pct, etc.) are the authority.
    Anyone can rebuild this string from those views and verify the hash.

    Fixed test vector (SHA-256 hex):
        Input  : canonical_rules(load_rules()) with the values in rules.json
        Hash   : computed by pact/test_rules.py::test_canonical_rules_hash_vector
    """
    subset = {
        "burn_amount": rules.burn_amount,         # str (decimal)
        "min_members": rules.min_members,          # int
        "mint_amount": rules.mint_amount,          # str (decimal)
        "quorum_pct": rules.quorum_pct,            # int
        "reserve_amount": rules.reserve_amount,    # str (decimal)
        "stake_yocto": rules.stake_yocto,          # str (decimal)
        "submission_grace_secs": rules.submission_grace_secs,  # int
    }
    # Keys are already sorted alphabetically above; json.dumps sorts anyway.
    return json.dumps(subset, sort_keys=True, separators=(",", ":"))


def rules_hash(rules: "PactRules") -> str:
    """SHA-256 hex digest of canonical_rules(rules).

    This is the value stored in the contract at init and returned by
    get_rules_hash(). The deploy script passes it as `rules_hash` init arg.
    """
    return hashlib.sha256(canonical_rules(rules).encode("utf-8")).hexdigest()


def load_rules(path: Optional[Union[str, Path]] = None) -> PactRules:
    """Load and validate rules.json (default: the repo's single source of truth).

    Raises pydantic.ValidationError (invalid values) or ValueError (not
    parseable JSON) so callers fail loudly instead of using bad config.
    """
    rules_path = Path(path) if path is not None else DEFAULT_RULES_PATH
    try:
        raw = json.loads(rules_path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        raise ValueError(f"{rules_path} is not valid JSON: {exc}") from exc
    return PactRules.model_validate(raw)
