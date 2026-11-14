#!/usr/bin/env python3
"""
Unit test suite for scripts/pact_governance.py.

Verifies:
  1. Argument encoding and contract payload consistency.
  2. Time and overlap validation logic.
  3. Sputnik DAO vote-threshold calculations.
  4. Composite proposal-state classification.
  5. Security guards preventing accidental execution on the production 'real' profile.
"""
import unittest
from datetime import datetime, timezone
from pathlib import Path
import sys

_REPO_ROOT = Path(__file__).resolve().parents[1]
if str(_REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(_REPO_ROOT))

from scripts.pact_governance import (
    encode_function_call_args,
    decode_function_call_args,
    calculate_required_votes,
    validate_challenge_timing,
    classify_proposal_state,
    check_real_profile_guards,
)
from pact.profiles import PROFILES


class TestPactGovernance(unittest.TestCase):

    # -------------------------------------------------------------------
    # 1. Argument Encoding & Contract Payload Consistency
    # -------------------------------------------------------------------

    def test_encode_and_decode_function_call_args(self):
        sample_args = {
            "challenge_id": "demo-17",
            "steps_per_day": 20,
            "days_required": 1,
            "start_ns": "1794624000000000000",
            "end_ns": "1794624300000000000",
        }
        b64 = encode_function_call_args(sample_args)
        decoded = decode_function_call_args(b64)

        self.assertEqual(decoded["challenge_id"], "demo-17")
        self.assertEqual(decoded["steps_per_day"], 20)
        self.assertEqual(decoded["days_required"], 1)
        self.assertEqual(decoded["start_ns"], "1794624000000000000")
        self.assertEqual(decoded["end_ns"], "1794624300000000000")

    # -------------------------------------------------------------------
    # 2. Time and Overlap Validation
    # -------------------------------------------------------------------

    def test_timing_rejection_when_end_before_start(self):
        now_ns = 1_000_000_000_000_000_000
        start_ns = now_ns + (600 * 1_000_000_000)
        end_ns = start_ns - 1000  # Invalid: end before start

        ok, err = validate_challenge_timing(
            start_ns=start_ns,
            end_ns=end_ns,
            existing_challenges=[],
            lead_time_seconds=180,
            now_ns=now_ns,
        )
        self.assertFalse(ok)
        self.assertIn("strictly greater", err)

    def test_timing_rejection_when_lead_time_insufficient(self):
        now_ns = 1_000_000_000_000_000_000
        # Start in 60 seconds (less than 180s lead time)
        start_ns = now_ns + (60 * 1_000_000_000)
        end_ns = start_ns + (300 * 1_000_000_000)

        ok, err = validate_challenge_timing(
            start_ns=start_ns,
            end_ns=end_ns,
            existing_challenges=[],
            lead_time_seconds=180,
            now_ns=now_ns,
        )
        self.assertFalse(ok)
        self.assertIn("lead time", err)

    def test_timing_rejection_on_overlap_with_existing_challenge(self):
        now_ns = 1_000_000_000_000_000_000
        existing = [
            {
                "challenge_id": "demo-16",
                "start_ns": str(now_ns + 100 * 1_000_000_000),
                "end_ns": str(now_ns + 1000 * 1_000_000_000),
            }
        ]
        # Start at now + 500s (overlaps demo-16)
        start_ns = now_ns + (500 * 1_000_000_000)
        end_ns = start_ns + (300 * 1_000_000_000)

        ok, err = validate_challenge_timing(
            start_ns=start_ns,
            end_ns=end_ns,
            existing_challenges=existing,
            lead_time_seconds=180,
            now_ns=now_ns,
        )
        self.assertFalse(ok)
        self.assertIn("overlaps with challenge 'demo-16'", err)

    def test_timing_acceptance_valid_future_schedule(self):
        now_ns = 1_000_000_000_000_000_000
        existing = [
            {
                "challenge_id": "demo-16",
                "start_ns": str(now_ns + 100 * 1_000_000_000),
                "end_ns": str(now_ns + 500 * 1_000_000_000),
            }
        ]
        # Starts safely after demo-16 end + 5s buffer, and > 180s in the future
        start_ns = now_ns + (600 * 1_000_000_000)
        end_ns = start_ns + (300 * 1_000_000_000)

        ok, err = validate_challenge_timing(
            start_ns=start_ns,
            end_ns=end_ns,
            existing_challenges=existing,
            lead_time_seconds=180,
            now_ns=now_ns,
        )
        self.assertTrue(ok)
        self.assertIsNone(err)

    # -------------------------------------------------------------------
    # 3. Sputnik DAO Vote-Threshold Calculations
    # -------------------------------------------------------------------

    def test_vote_threshold_ratio_calculation(self):
        policy_half = {"threshold": [1, 2]}  # > 50%
        # Council of 3: > 1.5 -> requires 2
        self.assertEqual(calculate_required_votes(policy_half, 3), 2)
        # Council of 5: > 2.5 -> requires 3
        self.assertEqual(calculate_required_votes(policy_half, 5), 3)
        # Council of 1: > 0.5 -> requires 1
        self.assertEqual(calculate_required_votes(policy_half, 1), 1)

        policy_two_thirds = {"threshold": [2, 3]}  # > 66.6%
        # Council of 3: > 2.0 -> requires 3
        self.assertEqual(calculate_required_votes(policy_two_thirds, 3), 3)

    def test_vote_threshold_weight_calculation(self):
        policy_weight = {"threshold": 2}
        self.assertEqual(calculate_required_votes(policy_weight, 3), 2)

    # -------------------------------------------------------------------
    # 4. Proposal-State Classification
    # -------------------------------------------------------------------

    def test_classify_proposal_states(self):
        # 1. InProgress
        lbl, _ = classify_proposal_state({"status": "InProgress"})
        self.assertEqual(lbl, "Voting")

        # 2. Rejected
        lbl, _ = classify_proposal_state({"status": "Rejected"})
        self.assertEqual(lbl, "Rejected")

        # 3. Approved & Executed (challenge present on contract)
        lbl, _ = classify_proposal_state(
            {"status": "Approved"},
            contract_challenge={"challenge_id": "demo-17", "steps_per_day": 20}
        )
        self.assertEqual(lbl, "Executed")

        # 4. Approved but Not Executed (challenge missing from contract)
        lbl, _ = classify_proposal_state(
            {"status": "Approved"},
            contract_challenge=None
        )
        self.assertEqual(lbl, "Approved but not executed")

        # 5. Failed
        lbl, _ = classify_proposal_state({"status": "Failed"})
        self.assertEqual(lbl, "Execution failed")

    # -------------------------------------------------------------------
    # 5. Production 'Real' Profile Security Guard
    # -------------------------------------------------------------------

    def test_real_profile_rejected_without_allow_flag(self):
        ok, err = check_real_profile_guards("real", allow_real=False)
        self.assertFalse(ok)
        self.assertIn("--allow-real", err)

    def test_real_profile_rejected_on_incorrect_typed_confirmation(self):
        ok, err = check_real_profile_guards(
            "real",
            allow_real=True,
            confirm_input_func=lambda: "wrong-contract-name"
        )
        self.assertFalse(ok)
        self.assertIn("Confirmation failed", err)

    def test_real_profile_accepted_on_exact_typed_confirmation(self):
        ok, err = check_real_profile_guards(
            "real",
            allow_real=True,
            confirm_input_func=lambda: PROFILES["real"].contract_id
        )
        self.assertTrue(ok)
        self.assertIsNone(err)

    def test_demo_and_sandbox_bypass_real_guard(self):
        ok_demo, _ = check_real_profile_guards("demo", allow_real=False)
        self.assertTrue(ok_demo)

        ok_sb, _ = check_real_profile_guards("sandbox", allow_real=False)
        self.assertTrue(ok_sb)


if __name__ == "__main__":
    unittest.main()
