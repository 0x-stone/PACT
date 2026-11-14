"""
DAO configuration — all IDs and RPC endpoint from environment.
Never hardcode account IDs or keys. Import this module everywhere.

Required env vars:
  PACT_CONTRACT_ID     e.g. pactcontract.testnet
  PACT_DAO_ID          e.g. pactdao.testnet
  NEAR_NETWORK         testnet (default)
  NEAR_RPC_URL         https://rpc.testnet.near.org (default)

Council accounts (used to sign proposals/votes; keys must be in
~/.near-credentials/<network>/<account>.json or the near-cli keystore):
  COUNCIL_PROPOSER     account that proposes (e.g. 0xgoku.testnet)
  COUNCIL_1            first voter  (e.g. 0xpiccolo.testnet)
  COUNCIL_2            second voter (e.g. 0xbulma.testnet)
"""
import os

NETWORK        = os.getenv("NEAR_NETWORK",       "testnet")
RPC_URL        = os.getenv("NEAR_RPC_URL",        "https://test.rpc.fastnear.com")
CONTRACT_ID    = os.getenv("PACT_CONTRACT_ID",    "pactcontract.testnet")
DAO_ID         = os.getenv("PACT_DAO_ID",         "pactdao.testnet")
NEAR_CLI       = os.getenv("NEAR_CLI",            "near")

# Council accounts — read from env with testnet defaults
COUNCIL_PROPOSER = os.getenv("COUNCIL_PROPOSER", "0xgoku.testnet")
COUNCIL_1        = os.getenv("COUNCIL_1",         "0xpiccolo.testnet")
COUNCIL_2        = os.getenv("COUNCIL_2",         "0xbulma.testnet")

# Proposal bond (1 NEAR in yocto) — from policy, confirmed on-chain
PROPOSAL_BOND_YOCTO = "1000000000000000000000000"

def require_env():
    """Abort early with a clear message if any required var is missing."""
    missing = [k for k, v in {
        "PACT_CONTRACT_ID": CONTRACT_ID,
        "PACT_DAO_ID":      DAO_ID,
        "COUNCIL_PROPOSER": COUNCIL_PROPOSER,
        "COUNCIL_1":        COUNCIL_1,
        "COUNCIL_2":        COUNCIL_2,
    }.items() if not v]
    if missing:
        raise SystemExit(
            f"ERROR: required env vars not set: {', '.join(missing)}\n"
            "Set them before running any DAO script."
        )
