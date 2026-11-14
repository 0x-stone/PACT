#!/usr/bin/env python3
"""
PACT Governance Terminal — Recording-friendly DAO proposal and voting CLI.

Provides a calm, sleek terminal UI to inspect Sputnik DAO governance, propose new
pact challenges via real on-chain proposals, coordinate council votes, and verify
execution on-chain.

Usage:
  python scripts/pact_governance.py view [--profile demo|sandbox|real]
  python scripts/pact_governance.py create [--profile demo] [--dry-run] [--pace] [--yes]
"""
from __future__ import annotations

import argparse
import base64
import json
import logging
import math
import os
import re
import signal
import sys
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable, Dict, List, Optional, Tuple

_REPO_ROOT = Path(__file__).resolve().parents[1]
if str(_REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(_REPO_ROOT))

# Rich UI imports
from rich.console import Console
from rich.panel import Panel
from rich.table import Table
from rich.text import Text
from rich import box

# DAO & Profile integrations
from pact.profiles import PROFILES, get_profile, PactProfile
from dao.near_rpc import view, view_account, near_cli, extract_tx_hash

# ---------------------------------------------------------------------------
# Setup Logging (Sanitized, No Secrets)
# ---------------------------------------------------------------------------
LOGS_DIR = _REPO_ROOT / "logs"
LOGS_DIR.mkdir(exist_ok=True)
_LOG_TIMESTAMP = datetime.now().strftime("%Y%m%d_%H%M%S")
_LOG_FILE = LOGS_DIR / f"governance_{_LOG_TIMESTAMP}.log"

logging.basicConfig(
    filename=str(_LOG_FILE),
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
)
logger = logging.getLogger("pact_governance")

# ---------------------------------------------------------------------------
# Console Setup & Design System
# ---------------------------------------------------------------------------
console = Console(width=100)

COLOR_PRIMARY = "#1E88E5"   # Blue
COLOR_ACCENT  = "#FF9800"   # Orange
COLOR_ERROR   = "#E53935"   # Red
COLOR_SUCCESS = "#2E7D32"   # Green
COLOR_MUTED   = "#78909C"   # Muted gray/slate

EXPLORER_BASE = "https://testnet.nearblocks.io/txns"

# Track recorded actions for clean Ctrl+C report
_RECORDED_ACTIONS: List[str] = []

def _signal_handler(sig, frame):
    console.print()
    err_panel = Panel(
        Text("Execution interrupted by user (Ctrl+C). Exiting safely.", style="bold red"),
        title="[bold red]Interrupted[/bold red]",
        border_style="red",
        box=box.ROUNDED,
    )
    console.print(err_panel)
    if _RECORDED_ACTIONS:
        console.print("[dim]Actions completed prior to exit:[/dim]")
        for act in _RECORDED_ACTIONS:
            console.print(f"  • {act}")
    sys.exit(130)

signal.signal(signal.SIGINT, _signal_handler)


# ---------------------------------------------------------------------------
# Pure Helper Logic & Verification Functions (Unit-Testable)
# ---------------------------------------------------------------------------

def encode_function_call_args(args_dict: Dict[str, Any]) -> str:
    """Encode dictionary arguments to base64 JSON string for FunctionCall proposals."""
    json_bytes = json.dumps(args_dict, separators=(",", ":")).encode("utf-8")
    return base64.b64encode(json_bytes).decode("utf-8")


def decode_function_call_args(b64_str: str) -> Dict[str, Any]:
    """Decode base64 encoded JSON argument string."""
    try:
        raw = base64.b64decode(b64_str.encode("utf-8")).decode("utf-8")
        return json.loads(raw)
    except Exception:
        return {}


def calculate_required_votes(vote_policy: Dict[str, Any], council_size: int) -> int:
    """
    Compute number of approving votes required under Sputnik DAO policy.
    Threshold can be:
      - Ratio: [numerator, denominator] -> votes/council_size > num/denom
      - Weight: direct integer count
    """
    if council_size <= 0:
        return 0
    thresh = vote_policy.get("threshold", [1, 2])
    if isinstance(thresh, list) and len(thresh) == 2:
        num, denom = thresh[0], thresh[1]
        if denom == 0:
            return council_size
        # Sputnik logic: (votes * denom) > (council_size * num)
        # Minimum integer votes such that votes * denom > council_size * num:
        required = math.floor((council_size * num) / denom) + 1
        return min(council_size, max(1, required))
    elif isinstance(thresh, (int, str)):
        try:
            return min(council_size, max(1, int(thresh)))
        except ValueError:
            return 1
    return max(1, math.ceil(council_size * 0.5))


def validate_challenge_timing(
    start_ns: int,
    end_ns: int,
    existing_challenges: List[Dict[str, Any]],
    lead_time_seconds: int = 180,
    now_ns: Optional[int] = None,
) -> Tuple[bool, Optional[str]]:
    """
    Validates challenge start/end timestamps against:
      1. End must strictly succeed start.
      2. Start must be in the future by at least lead_time_seconds (for voting execution).
      3. Start must not overlap any existing challenge end time (+ 5s buffer).
    """
    if now_ns is None:
        now_ns = int(time.time() * 1_000_000_000)

    if end_ns <= start_ns:
        return False, f"End time ({end_ns}) must be strictly greater than start time ({start_ns})."

    min_allowed_start_ns = now_ns + (lead_time_seconds * 1_000_000_000)
    if start_ns < min_allowed_start_ns:
        seconds_diff = (min_allowed_start_ns - start_ns) / 1_000_000_000
        return False, (
            f"Start time is too soon or in the past ({seconds_diff:.1f}s short of {lead_time_seconds}s lead time). "
            "DAO council votes will execute after the challenge has already begun."
        )

    # Check overlaps against existing challenges
    for ch in existing_challenges:
        c_end = int(ch.get("end_ns", 0))
        if c_end > 0 and start_ns < (c_end + 5_000_000_000): # 5s buffer
            c_id = ch.get("challenge_id", "existing")
            c_end_dt = datetime.fromtimestamp(c_end / 1e9, tz=timezone.utc).strftime("%H:%M:%S UTC")
            return False, (
                f"Proposed start overlaps with challenge '{c_id}' ending at {c_end_dt}. "
                "The contract strictly forbids overlapping challenges."
            )

    return True, None


def classify_proposal_state(
    proposal: Dict[str, Any],
    contract_challenge: Optional[Dict[str, Any]] = None
) -> Tuple[str, str]:
    """
    Determine composite proposal and on-chain execution state:
    Returns (status_label, detailed_explanation).
    """
    raw_status = proposal.get("status", "Unknown")

    if raw_status == "InProgress":
        return "Voting", "Proposal is active and awaiting council member votes."
    elif raw_status == "Rejected":
        return "Rejected", "Proposal was rejected by council vote. On-chain state was not modified."
    elif raw_status == "Expired":
        return "Expired", "Proposal expired before receiving sufficient approvals."
    elif raw_status == "Approved":
        if contract_challenge is not None:
            return "Executed", "Proposal passed council vote and successfully initialized on-chain challenge."
        else:
            return "Approved but not executed", (
                "Proposal received required votes, but the contract callback failed or execution is still pending."
            )
    elif raw_status == "Failed":
        return "Execution failed", "Proposal received votes but execution transaction failed on-chain."
    return raw_status, f"Proposal is in state {raw_status}."


def check_real_profile_guards(
    profile_name: str,
    allow_real: bool,
    confirm_input_func: Callable[[], str] = input
) -> Tuple[bool, Optional[str]]:
    """
    Enforces security guards for the 'real' production profile.
    Requires --allow-real flag AND explicit typed entry of the contract ID.
    """
    if profile_name.lower() != "real":
        return True, None

    if not allow_real:
        return False, (
            "Refusing to execute on production profile 'real'. "
            "You must supply the '--allow-real' flag to proceed."
        )

    expected_contract = PROFILES["real"].contract_id
    console.print(
        f"\n[bold red]CAUTION: PRODUCTION PROFILE TARGETED ({expected_contract})[/bold red]"
    )
    console.print(
        f"To confirm live on-chain governance, type the exact contract ID '[bold]{expected_contract}[/bold]':"
    )
    user_entry = confirm_input_func().strip()
    if user_entry != expected_contract:
        return False, f"Confirmation failed. Entered '{user_entry}', expected '{expected_contract}'."

    return True, None


# ---------------------------------------------------------------------------
# Formatting Helpers
# ---------------------------------------------------------------------------

def format_near(yocto: str | int) -> str:
    """Format yoctoNEAR string to readable NEAR string."""
    try:
        val = int(yocto)
        near = val / 10**24
        if near == int(near):
            return f"{int(near)} NEAR"
        return f"{near:.4f} NEAR"
    except Exception:
        return "0 NEAR"


def format_timestamp_dual(ns: int) -> str:
    """Format nanoseconds timestamp in both Local Time and UTC."""
    sec = ns / 1_000_000_000
    dt_utc = datetime.fromtimestamp(sec, tz=timezone.utc)
    dt_local = datetime.fromtimestamp(sec).astimezone()
    return f"{dt_local.strftime('%Y-%m-%d %H:%M:%S %Z')} ({dt_utc.strftime('%H:%M:%S UTC')})"


def render_header(profile: PactProfile):
    """Render calm, sleek header with PACT wordmark and profile chip."""
    grid = Table.grid(expand=True)
    grid.add_column(justify="left", ratio=3)
    grid.add_column(justify="right", ratio=2)

    title_text = Text()
    title_text.append("◯  PACT GOVERNANCE TERMINAL", style="bold white")
    title_text.append("  |  Decentralized Challenge Lifecycle", style=COLOR_MUTED)

    badge_style = "bold red" if profile.id == "real" else f"bold {COLOR_ACCENT}"
    badge_text = Text(f" [ {profile.label.upper()} ] ", style=badge_style)

    grid.add_row(title_text, badge_text)
    header_panel = Panel(grid, box=box.ROUNDED, border_style=COLOR_PRIMARY, padding=(0, 1))
    console.print(header_panel)


# ---------------------------------------------------------------------------
# Mode 1: View Governance (Read-Only)
# ---------------------------------------------------------------------------

def run_view_mode(profile: PactProfile):
    """Polished read-only screen showing DAO status, treasury, policy, and proposals."""
    logger.info("Executing view mode for profile %s", profile.id)
    render_header(profile)

    # 1. Fetch DAO & Contract Facts
    with console.status("[bold blue]Querying NEAR RPC state...", spinner="dots"):
        contract_id = profile.contract_id
        dao_id = profile.dao_id

        # Treasury balance
        treasury_near = "Unavailable"
        try:
            acc = view_account(dao_id)
            treasury_near = format_near(acc.get("amount", "0"))
        except Exception as e:
            logger.warning("Could not view account %s: %s", dao_id, e)

        # Policy & Council
        council: List[str] = []
        proposal_bond_near = "1 NEAR"
        votes_required_str = "2 of 3 (67%)"
        last_proposals: List[Dict[str, Any]] = []

        is_sputnik_dao = True
        try:
            policy = view(dao_id, "get_policy") or {}
            proposal_bond_near = format_near(policy.get("proposal_bond", "1000000000000000000000000"))

            for role in policy.get("roles", []):
                if role.get("name") == "council":
                    kind = role.get("kind", {})
                    if isinstance(kind, dict) and "Group" in kind:
                        council = kind["Group"]

            call_policy = policy.get("roles", [{}])[1].get("vote_policy", {}).get("call", {})
            req_votes = calculate_required_votes(call_policy, len(council))
            votes_required_str = f"{req_votes} of {len(council)} votes"

            last_id = view(dao_id, "get_last_proposal_id") or 0
            if last_id > 0:
                from_idx = max(0, last_id - 5)
                last_proposals = view(dao_id, "get_proposals", {"from_index": from_idx, "limit": 5}) or []
        except Exception as e:
            logger.info("DAO account %s is direct relayer or query failed: %s", dao_id, e)
            is_sputnik_dao = False

        # Contract facts
        min_members = profile.min_members
        quorum_pct = profile.quorum_pct
        grace_secs = profile.submission_grace_secs
        stake_near = format_near(profile.stake_yocto)
        try:
            min_members = view(contract_id, "get_min_members") or min_members
            quorum_pct = view(contract_id, "get_quorum_pct") or quorum_pct
            grace_secs = view(contract_id, "get_submission_grace_secs") or grace_secs
            stake_yocto = view(contract_id, "get_stake_yocto")
            if stake_yocto:
                stake_near = format_near(stake_yocto)
        except Exception as e:
            logger.warning("Could not query contract %s constants: %s", contract_id, e)

    # 2. Render Governance Parameters Table
    gov_table = Table(box=box.ROUNDED, expand=True, border_style=COLOR_MUTED)
    gov_table.add_column("Parameter", style="bold white", ratio=2)
    gov_table.add_column("On-Chain Value", style="white", ratio=3)
    gov_table.add_column("Context / Economics", style=COLOR_MUTED, ratio=3)

    gov_table.add_row("Contract Target", contract_id, "Verified PACT Challenge Core")
    gov_table.add_row("Governance Entity", dao_id, "Sputnik v2 DAO" if is_sputnik_dao else "Relayer Controller")
    gov_table.add_row("DAO Treasury", treasury_near, "Available for operations & staking")
    gov_table.add_row("Proposal Bond", proposal_bond_near, "Returned on approval; unreturned on spam")
    gov_table.add_row("FunctionCall Vote Rule", votes_required_str, "Threshold to execute set_next_challenge")
    gov_table.add_row("Target Contract Stake", stake_near, f"Enforce commit: {min_members} members min")
    gov_table.add_row("Submission Grace Window", f"{grace_secs} seconds", f"{quorum_pct}% community quorum target")

    console.print(Panel(gov_table, title="[bold]Governance & Economic Rules[/bold]", box=box.ROUNDED, border_style=COLOR_PRIMARY))

    # 3. Council Members
    if council:
        c_table = Table(box=box.SIMPLE, expand=True)
        c_table.add_column("#", style="dim", width=4)
        c_table.add_column("Council Account", style="bold white")
        c_table.add_column("Role", style=COLOR_ACCENT)
        for idx, member in enumerate(council, 1):
            c_table.add_row(str(idx), member, "Council Member (Voter)")
        console.print(Panel(c_table, title="[bold]Council Members[/bold]", box=box.ROUNDED, border_style=COLOR_PRIMARY))
    elif not is_sputnik_dao:
        console.print(Panel(
            Text(f"Account '{dao_id}' controls challenges directly via authorized relayer transactions.", style="white"),
            title="[bold]Relayer Governance Architecture[/bold]",
            box=box.ROUNDED,
            border_style=COLOR_PRIMARY
        ))

    # 4. Recent Proposals
    if last_proposals:
        prop_table = Table(box=box.ROUNDED, expand=True, border_style=COLOR_MUTED)
        prop_table.add_column("ID", style="bold", width=6)
        prop_table.add_column("Description", style="white", ratio=3)
        prop_table.add_column("Proposer", style=COLOR_MUTED, ratio=2)
        prop_table.add_column("Status", justify="center", width=14)
        prop_table.add_column("Votes Summary", ratio=2)

        for p in reversed(last_proposals):
            p_id = str(p.get("id"))
            p_desc = p.get("description", "No description")
            p_proposer = p.get("proposer", "Unknown")
            p_status = p.get("status", "Unknown")
            p_votes = p.get("votes", {})

            # Format votes summary
            approvals = [k for k, v in p_votes.items() if v == "Approve"]
            rejections = [k for k, v in p_votes.items() if v == "Reject"]
            vote_summary = f"{len(approvals)} Approve"
            if rejections:
                vote_summary += f", {len(rejections)} Reject"

            status_style = "bold green" if p_status == "Approved" else ("bold orange3" if p_status == "InProgress" else "bold red")
            prop_table.add_row(
                p_id,
                p_desc,
                p_proposer,
                Text(p_status, style=status_style),
                vote_summary
            )
        console.print(Panel(prop_table, title="[bold]Last 5 DAO Proposals[/bold]", box=box.ROUNDED, border_style=COLOR_PRIMARY))
    console.print()


# ---------------------------------------------------------------------------
# Mode 2: Interactive Create Flow (6 Guided Steps)
# ---------------------------------------------------------------------------

def run_create_flow(
    profile: PactProfile,
    dry_run: bool = False,
    pace: bool = False,
    compact: bool = False,
    auto_yes: bool = False,
    json_out_path: Optional[str] = None,
    allow_real: bool = False,
):
    """Guided 6-step challenge creation and council voting flow."""
    logger.info("Starting create flow: profile=%s, dry_run=%s, pace=%s", profile.id, dry_run, pace)
    render_header(profile)

    # Security check for production profile
    ok, err_msg = check_real_profile_guards(profile.id, allow_real)
    if not ok:
        console.print(Panel(Text(err_msg, style="bold red"), title="Security Check Failed", border_style="red"))
        sys.exit(1)

    def do_pace():
        if pace:
            time.sleep(0.7)

    # Gather contract facts
    with console.status("[bold blue]Querying contract and DAO rules...", spinner="dots"):
        contract_id = profile.contract_id
        dao_id = profile.dao_id

        # Existing challenges
        existing_challenges: List[Dict[str, Any]] = []
        try:
            existing_challenges = view(contract_id, "get_challenges", {"from_index": 0, "limit": 100}) or []
        except Exception as e:
            logger.warning("Could not fetch existing challenges: %s", e)

        # Economic parameters
        stake_yocto = profile.stake_yocto
        grace_secs = profile.submission_grace_secs
        min_members = profile.min_members
        quorum_pct = profile.quorum_pct
        try:
            stake_yocto = view(contract_id, "get_stake_yocto") or stake_yocto
            grace_secs = view(contract_id, "get_submission_grace_secs") or grace_secs
            min_members = view(contract_id, "get_min_members") or min_members
            quorum_pct = view(contract_id, "get_quorum_pct") or quorum_pct
        except Exception:
            pass

        # Council & Policy
        council: List[str] = []
        proposal_bond = "1000000000000000000000000"
        vote_policy = {"threshold": [1, 2]}
        try:
            pol = view(dao_id, "get_policy") or {}
            proposal_bond = pol.get("proposal_bond", proposal_bond)
            for role in pol.get("roles", []):
                if role.get("name") == "council":
                    council = role.get("kind", {}).get("Group", [])
                    vote_policy = role.get("vote_policy", {}).get("call", vote_policy)
        except Exception as e:
            logger.warning("Could not fetch DAO policy: %s", e)

    req_votes = calculate_required_votes(vote_policy, len(council))
    proposer_account = os.getenv("COUNCIL_PROPOSER", council[0] if council else "0xgoku.testnet")

    # Determine auto-suggested challenge ID (highest existing index + 1)
    existing_ids = {c.get("challenge_id", "") for c in existing_challenges}
    max_idx = 0
    idx_regex = re.compile(rf"^{re.escape(profile.id)}-(\d+)$")
    for cid in existing_ids:
        m = idx_regex.match(cid)
        if m:
            max_idx = max(max_idx, int(m.group(1)))
    suggested_id = f"{profile.id}-{max_idx + 1}"

    # -----------------------------------------------------------------------
    # Step 1: Challenge Parameters
    # -----------------------------------------------------------------------
    step1_table = Table.grid(padding=(0, 2))
    step1_table.add_column(style="bold white", width=22)
    step1_table.add_column(style="white")

    step1_table.add_row("Contract Stake:", f"{format_near(stake_yocto)} (Immutable on-chain)")
    step1_table.add_row("Submission Grace:", f"{grace_secs} seconds (Immutable on-chain)")
    step1_table.add_row("Min Members & Quorum:", f"{min_members} members min · {quorum_pct}% quorum")
    step1_table.add_row("Suggested Challenge ID:", f"{suggested_id}")

    console.print(Panel(
        step1_table,
        title="[bold]Step 1: Challenge Parameters & Contract Rules[/bold]",
        subtitle="[dim]Contract facts read on-chain[/dim]",
        border_style=COLOR_PRIMARY,
        box=box.ROUNDED
    ))

    # In interactive or scripted mode:
    if auto_yes:
        challenge_id = suggested_id
        steps_per_day = 10 if profile.id == "sandbox" else 20
        days_required = 1
        start_offset_mins = 15
        duration_mins = 5
    else:
        c_id_in = console.input(f"[bold]Challenge ID[/bold] [dim]({suggested_id})[/dim]: ").strip()
        challenge_id = c_id_in if c_id_in else suggested_id
        if len(challenge_id) > 32:
            challenge_id = challenge_id[:32]

        steps_in = console.input("[bold]Daily Steps Target[/bold] [dim](20)[/dim]: ").strip()
        steps_per_day = int(steps_in) if steps_in.isdigit() else 20

        days_in = console.input("[bold]Days Required[/bold] [dim](1)[/dim]: ").strip()
        days_required = int(days_in) if days_in.isdigit() else 1

        offset_in = console.input("[bold]Start Offset (Minutes from now)[/bold] [dim](15)[/dim]: ").strip()
        start_offset_mins = int(offset_in) if offset_in.isdigit() else 15

        dur_in = console.input("[bold]Duration (Minutes)[/bold] [dim](5)[/dim]: ").strip()
        duration_mins = int(dur_in) if dur_in.isdigit() else 5

    now_ns = int(time.time() * 1_000_000_000)
    start_ns = now_ns + (start_offset_mins * 60 * 1_000_000_000)
    end_ns = start_ns + (duration_mins * 60 * 1_000_000_000)
    settle_ns = end_ns + (grace_secs * 1_000_000_000)

    do_pace()

    # -----------------------------------------------------------------------
    # Step 2: Review & Schedule
    # -----------------------------------------------------------------------
    # Validate timing & overlaps
    valid_timing, timing_err = validate_challenge_timing(
        start_ns=start_ns,
        end_ns=end_ns,
        existing_challenges=existing_challenges,
        lead_time_seconds=180,
        now_ns=now_ns
    )
    if not valid_timing:
        console.print(Panel(
            Text(f"Scheduling Validation Failed:\n{timing_err}", style="bold red"),
            title="[bold red]Validation Error[/bold red]",
            border_style="red",
            box=box.ROUNDED
        ))
        sys.exit(1)

    # Check proposer balance
    proposer_bal_near = "10 NEAR (dry-run)" if dry_run else "Unknown"
    if not dry_run:
        try:
            p_acc = view_account(proposer_account)
            proposer_bal_near = format_near(p_acc.get("amount", "0"))
        except Exception as e:
            logger.warning("Could not view proposer account: %s", e)

    step2_table = Table.grid(padding=(0, 2))
    step2_table.add_column(style="bold white", width=22)
    step2_table.add_column(style="white")

    step2_table.add_row("Challenge ID:", challenge_id)
    step2_table.add_row("Target Parameters:", f"{steps_per_day} steps/day · {days_required} day required")
    step2_table.add_row("Start Time:", format_timestamp_dual(start_ns))
    step2_table.add_row("End Time:", format_timestamp_dual(end_ns))
    step2_table.add_row("Settlement Opens:", format_timestamp_dual(settle_ns))
    step2_table.add_row("Target Contract:", contract_id)
    step2_table.add_row("Target DAO:", dao_id)
    step2_table.add_row("Proposal Bond:", format_near(proposal_bond))
    step2_table.add_row("Proposer Account:", f"{proposer_account} (Balance: {proposer_bal_near})")

    review_content = Table.grid(padding=(1, 0))
    review_content.add_row(step2_table)
    review_content.add_row(
        Text(
            "Notice: This submits a DAO proposal for council approval. It does not create a challenge yet.",
            style="italic cyan"
        )
    )

    console.print(Panel(
        review_content,
        title="[bold]Step 2: Review & Schedule[/bold]",
        border_style=COLOR_PRIMARY,
        box=box.ROUNDED
    ))

    if not auto_yes:
        confirm = console.input("[bold]Continue to submit DAO proposal? [y/N][/bold]: ").strip().lower()
        if confirm != "y":
            console.print("[dim]Aborted by user.[/dim]")
            sys.exit(0)

    do_pace()

    # -----------------------------------------------------------------------
    # Step 3: Propose
    # -----------------------------------------------------------------------
    challenge_call_args = {
        "challenge_id": challenge_id,
        "steps_per_day": steps_per_day,
        "days_required": days_required,
        "start_ns": str(start_ns),
        "end_ns": str(end_ns),
    }
    proposal_desc = f"Set demo challenge {challenge_id} ({steps_per_day} steps, {days_required} day, {duration_mins} mins)"

    # Check for existing identical pending proposal to guarantee idempotency
    existing_proposal_id: Optional[int] = None
    if not dry_run:
        try:
            last_id = view(dao_id, "get_last_proposal_id") or 0
            if last_id > 0:
                recent_proposals = view(dao_id, "get_proposals", {"from_index": max(0, last_id - 10), "limit": 10}) or []
                for p in recent_proposals:
                    if p.get("status") == "InProgress" and challenge_id in p.get("description", ""):
                        existing_proposal_id = p.get("id")
                        break
        except Exception as e:
            logger.warning("Could not query recent proposals for idempotency: %s", e)

    proposal_id: int = 0
    propose_tx_hash: str = ""

    if existing_proposal_id is not None:
        proposal_id = existing_proposal_id
        console.print(Panel(
            Text(f"Found existing pending proposal #{proposal_id} for '{challenge_id}'. Resuming vote.", style="bold cyan"),
            title="[bold cyan]Idempotency Match[/bold cyan]",
            box=box.ROUNDED
        ))
    elif dry_run:
        proposal_id = 99
        propose_tx_hash = "9xMockTxAddProposalHashDryRunForDemoVideo77"
        console.print(Panel(
            Text(f"[DRY-RUN] Simulated proposal creation:\nProposal ID: #{proposal_id}\nTX Hash: {propose_tx_hash}\nExplorer: {EXPLORER_BASE}/{propose_tx_hash}", style="yellow"),
            title="[bold yellow]Step 3: Propose (Dry-Run)[/bold yellow]",
            box=box.ROUNDED
        ))
        _RECORDED_ACTIONS.append(f"Simulated DAO Proposal #{proposal_id} for {challenge_id}")
    else:
        with console.status(f"[bold blue]Submitting DAO proposal via {proposer_account}...", spinner="dots"):
            args_b64 = encode_function_call_args(challenge_call_args)
            proposal_payload = {
                "proposal": {
                    "description": proposal_desc,
                    "kind": {
                        "FunctionCall": {
                            "receiver_id": contract_id,
                            "actions": [{
                                "method_name": "set_next_challenge",
                                "args": args_b64,
                                "deposit": "0",
                                "gas": "100000000000000",
                            }],
                        }
                    },
                }
            }
            output = near_cli(
                contract_id=dao_id,
                method="add_proposal",
                json_args=json.dumps(proposal_payload),
                sign_as=proposer_account,
                deposit=proposal_bond,
                gas="300.0 Tgas",
            )
            propose_tx_hash = extract_tx_hash(output)
            last_id = view(dao_id, "get_last_proposal_id")
            proposal_id = last_id - 1

        tx_url = f"{EXPLORER_BASE}/{propose_tx_hash}"
        console.print(Panel(
            Text(f"Proposal #{proposal_id} Submitted Successfully!\nTX Hash: {propose_tx_hash}\nExplorer: {tx_url}", style="bold green"),
            title="[bold]Step 3: Propose[/bold]",
            border_style=COLOR_SUCCESS,
            box=box.ROUNDED
        ))
        _RECORDED_ACTIONS.append(f"Submitted DAO Proposal #{proposal_id} ({propose_tx_hash})")

    do_pace()

    # -----------------------------------------------------------------------
    # Step 4: Council Voting
    # -----------------------------------------------------------------------
    vote_tx_hashes: Dict[str, str] = {}
    proposal_record: Dict[str, Any] = {}

    if dry_run:
        # Simulate dry-run voting by council members
        simulated_approvers = [proposer_account, council[1] if len(council) > 1 else "0xpiccolo.testnet"]
        for voter in simulated_approvers:
            mock_hash = f"8xMockVoteTx{voter[:6]}ApproveDryRun"
            vote_tx_hashes[voter] = mock_hash
            _RECORDED_ACTIONS.append(f"Simulated vote Approve by {voter}")

        sim_card = Table(box=box.ROUNDED, expand=True)
        sim_card.add_column("Council Member", style="bold white")
        sim_card.add_column("Vote Action", style="green")
        sim_card.add_column("TX Link", style=COLOR_MUTED)

        for voter in council:
            if voter in simulated_approvers:
                sim_card.add_row(voter, "Approve ✓", f"{EXPLORER_BASE}/{vote_tx_hashes[voter]}")
            else:
                sim_card.add_row(voter, "[Not Voted]", "-")

        console.print(Panel(
            sim_card,
            title="[bold yellow]Step 4: Council Voting (Dry-Run: 2 of 3 Approved)[/bold yellow]",
            box=box.ROUNDED
        ))
    else:
        # Live interactive council voting loop
        while True:
            proposal_record = view(dao_id, "get_proposal", {"id": proposal_id}) or {}
            p_status = proposal_record.get("status", "InProgress")
            p_votes = proposal_record.get("votes", {})

            # Render Live Proposal Card
            vote_card = Table(box=box.SIMPLE, expand=True)
            vote_card.add_column("Member", style="bold white", ratio=2)
            vote_card.add_column("Status", ratio=1)
            vote_card.add_column("TX Hash", style=COLOR_MUTED, ratio=3)

            approvals = 0
            for m in council:
                v_state = p_votes.get(m, "Not Voted")
                if v_state == "Approve":
                    approvals += 1
                    vote_card.add_row(m, "[bold green]Approved ✓[/bold green]", vote_tx_hashes.get(m, "Prior TX"))
                elif v_state == "Reject":
                    vote_card.add_row(m, "[bold red]Rejected ✗[/bold red]", vote_tx_hashes.get(m, "Prior TX"))
                else:
                    vote_card.add_row(m, "[dim]Not Voted[/dim]", "-")

            console.print(Panel(
                vote_card,
                title=f"[bold]Step 4: Live Proposal Card #{proposal_id} ({approvals}/{req_votes} Approved)[/bold]",
                subtitle=f"Status: {p_status}",
                border_style=COLOR_PRIMARY,
                box=box.ROUNDED
            ))

            if p_status != "InProgress" or approvals >= req_votes:
                console.print(f"[bold green]Proposal reached resolution: {p_status}![/bold green]")
                break

            # Prompt next voter
            unvoted = [m for m in council if m not in p_votes]
            if not unvoted:
                console.print("[dim]All council members have voted.[/dim]")
                break

            if auto_yes:
                voter_to_act = unvoted[0]
                action_to_take = "VoteApprove"
            else:
                console.print(f"Eligible council members to vote: {', '.join(unvoted)}")
                voter_input = console.input(f"Account to sign vote [dim]({unvoted[0]})[/dim]: ").strip()
                voter_to_act = voter_input if voter_input else unvoted[0]

                action_input = console.input("Vote action [A]pprove / [R]eject / [S]kip [dim](A)[/dim]: ").strip().lower()
                if action_input == "r":
                    action_to_take = "VoteReject"
                elif action_input == "s":
                    console.print("[dim]Skipping further votes.[/dim]")
                    break
                else:
                    action_to_take = "VoteApprove"

            with console.status(f"[bold blue]Broadcasting {action_to_take} as {voter_to_act}...", spinner="dots"):
                v_args = {"id": proposal_id, "action": action_to_take, "proposal": proposal_record["kind"]}
                out = near_cli(
                    contract_id=dao_id,
                    method="act_proposal",
                    json_args=json.dumps(v_args),
                    sign_as=voter_to_act,
                    deposit="0",
                    gas="300.0 Tgas",
                )
                v_tx = extract_tx_hash(out)
                vote_tx_hashes[voter_to_act] = v_tx
                _RECORDED_ACTIONS.append(f"Council vote {action_to_take} by {voter_to_act} ({v_tx})")

            console.print(f"[green]Vote recorded: {EXPLORER_BASE}/{v_tx}[/green]")
            do_pace()

    do_pace()

    # -----------------------------------------------------------------------
    # Step 5: On-Chain Verification
    # -----------------------------------------------------------------------
    on_chain_challenge: Optional[Dict[str, Any]] = None
    if dry_run:
        on_chain_challenge = {
            "challenge_id": challenge_id,
            "steps_per_day": steps_per_day,
            "days_required": days_required,
            "start_ns": str(start_ns),
            "end_ns": str(end_ns),
            "enrolled_count": 0,
            "outcome": "Pending"
        }
    else:
        with console.status("[bold blue]Verifying on-chain state on target contract...", spinner="dots"):
            time.sleep(2)  # Give RPC a moment to index callback receipt
            try:
                on_chain_challenge = view(contract_id, "get_challenge", {"challenge_id": challenge_id})
            except Exception as e:
                logger.warning("Could not read challenge %s: %s", challenge_id, e)

    state_label, state_desc = classify_proposal_state(
        {"status": "Approved" if dry_run else proposal_record.get("status", "Unknown")},
        on_chain_challenge
    )

    verif_color = COLOR_SUCCESS if state_label == "Executed" else ("yellow" if state_label == "Voting" else COLOR_ERROR)
    verif_content = Table.grid(padding=(0, 2))
    verif_content.add_column(style="bold white", width=22)
    verif_content.add_column(style="white")

    verif_content.add_row("Resolution Status:", f"[{verif_color}]{state_label}[/{verif_color}]")
    verif_content.add_row("Execution Verdict:", state_desc)
    if on_chain_challenge:
        secs_to_start = max(0, int((start_ns - int(time.time() * 1e9)) / 1e9))
        verif_content.add_row("On-Chain Verification:", f"Found '{challenge_id}' on {contract_id} ✓")
        verif_content.add_row("Countdown to Start:", f"{secs_to_start // 60}m {secs_to_start % 60}s")

    console.print(Panel(
        verif_content,
        title="[bold]Step 5: On-Chain Verification[/bold]",
        border_style=verif_color,
        box=box.ROUNDED
    ))

    do_pace()

    # -----------------------------------------------------------------------
    # Step 6: Summary & Runbook
    # -----------------------------------------------------------------------
    summary_grid = Table.grid(padding=(0, 2))
    summary_grid.add_column(style="bold white", width=22)
    summary_grid.add_column(style="white")

    summary_grid.add_row("Challenge ID:", challenge_id)
    summary_grid.add_row("Enrollment Window:", f"Now until {format_timestamp_dual(start_ns)}")
    summary_grid.add_row("Challenge Active:", f"{format_timestamp_dual(start_ns)} → {format_timestamp_dual(end_ns)}")
    summary_grid.add_row("Submission Grace Ends:", format_timestamp_dual(settle_ns))
    summary_grid.add_row("Settlement Opens:", f"Immediately after grace window closes")

    all_txs_text = Text()
    if propose_tx_hash:
        all_txs_text.append(f"Proposal TX: {EXPLORER_BASE}/{propose_tx_hash}\n", style="cyan")
    for voter, h in vote_tx_hashes.items():
        all_txs_text.append(f"Vote ({voter}): {EXPLORER_BASE}/{h}\n", style="cyan")

    final_content = Table.grid(padding=(1, 0))
    final_content.add_row(summary_grid)
    if str(all_txs_text):
        final_content.add_row(Panel(all_txs_text, title="[bold]Transaction Links[/bold]", box=box.ROUNDED, border_style=COLOR_MUTED))
    final_content.add_row(
        Text(
            f"Next Step: Open the PACT app, switch to '{profile.label}', and join before {format_timestamp_dual(start_ns)}.",
            style="bold green"
        )
    )

    console.print(Panel(
        final_content,
        title="[bold]Step 6: Summary & Runbook[/bold]",
        border_style=COLOR_PRIMARY,
        box=box.ROUNDED
    ))

    # Optional machine-readable JSON record export (demo_cycle.json compatible)
    if json_out_path:
        out_data = {
            "challenge_id": challenge_id,
            "status": "Verified Lifecycle Completed" if state_label == "Executed" else state_label,
            "contract_id": contract_id,
            "dao_id": dao_id,
            "dao_proposal_id": proposal_id,
            "dao_description": proposal_desc,
            "dao_proposer": proposer_account,
            "dao_approvers": list(vote_tx_hashes.keys()),
            "target_steps": steps_per_day,
            "duration_seconds": duration_mins * 60,
            "start_ns": str(start_ns),
            "end_ns": str(end_ns),
            "proposal_tx_hash": propose_tx_hash,
            "vote_tx_hashes": vote_tx_hashes,
            "timestamp": datetime.now(timezone.utc).isoformat(),
        }
        try:
            p_out = Path(json_out_path)
            p_out.parent.mkdir(parents=True, exist_ok=True)
            with open(p_out, "w", encoding="utf-8") as f:
                json.dump(out_data, f, indent=2)
            console.print(f"[dim]Machine-readable summary saved to {json_out_path}[/dim]")
        except Exception as e:
            logger.error("Could not write json-out file: %s", e)


# ---------------------------------------------------------------------------
# CLI Argument Parser & Entry Point
# ---------------------------------------------------------------------------

def main(argv=None):
    parser = argparse.ArgumentParser(
        description="PACT Governance Terminal — DAO proposal and voting CLI."
    )
    subparsers = parser.add_subparsers(dest="mode", help="Command mode: 'view' or 'create'")

    # view mode parser
    view_parser = subparsers.add_parser("view", help="Inspect DAO governance, policy, treasury and proposals")
    view_parser.add_argument("--profile", default="demo", choices=["demo", "sandbox", "real"],
                             help="Target profile (default: demo)")

    # create mode parser
    create_parser = subparsers.add_parser("create", help="Interactive guided flow to propose and vote on challenges")
    create_parser.add_argument("--profile", default="demo", choices=["demo", "sandbox", "real"],
                               help="Target profile (default: demo)")
    create_parser.add_argument("--dry-run", action="store_true",
                               help="Simulate execution without sending any transactions on-chain")
    create_parser.add_argument("--pace", action="store_true",
                               help="Add deliberate pacing and pauses for clear video recordings")
    create_parser.add_argument("--compact", action="store_true",
                               help="Render compact panels")
    create_parser.add_argument("--yes", action="store_true",
                               help="Automatically accept confirmations in scripted runs")
    create_parser.add_argument("--json-out", type=str, default=None,
                               help="Write machine-readable run record to PATH")
    create_parser.add_argument("--allow-real", action="store_true",
                               help="Allow targeting production 'real' profile (requires contract ID confirmation)")

    args = parser.parse_args(argv)

    if not args.mode:
        parser.print_help()
        sys.exit(1)

    profile = get_profile(args.profile)

    if args.mode == "view":
        run_view_mode(profile)
    elif args.mode == "create":
        run_create_flow(
            profile=profile,
            dry_run=args.dry_run,
            pace=args.pace,
            compact=args.compact,
            auto_yes=args.yes,
            json_out_path=args.json_out,
            allow_real=args.allow_real,
        )


if __name__ == "__main__":
    main()
