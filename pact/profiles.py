"""Pact deployment profiles: real, demo, sandbox.

Single immutable Profile definition:
(id, label, colour, contractId, daoId, backendProfileId, explorerBase)
"""

from __future__ import annotations

import json
from dataclasses import asdict, dataclass
from typing import Dict, Optional


@dataclass(frozen=True)
class PactProfile:
    id: str
    name: str  # alias for id to preserve backward compatibility
    label: str
    colour: str
    contract_id: str
    dao_id: str
    backend_profile_id: str
    explorer_base: str
    rules_hash: str
    submission_grace_secs: int
    stake_yocto: int
    min_members: int
    quorum_pct: int
    is_demo: bool = False


PROFILES: Dict[str, PactProfile] = {
    "real": PactProfile(
        id="real",
        name="real",
        label="Production (Real)",
        colour="#1E88E5",
        contract_id="pactcontract.testnet",
        dao_id="pactdao.testnet",
        backend_profile_id="real",
        explorer_base="https://testnet.nearblocks.io",
        rules_hash="c8f1e60f408ea298ee9d2f7718f2c436cd40e762f0422e8147c78afd115f5d19",
        submission_grace_secs=3600,
        stake_yocto=500_000_000_000_000_000_000_000,  # 0.5 NEAR
        min_members=5,
        quorum_pct=70,
        is_demo=False,
    ),
    "demo": PactProfile(
        id="demo",
        name="demo",
        label="Demo Profile",
        colour="#FF9800",
        contract_id="pact-demo.testnet",
        dao_id="pactdao.testnet",
        backend_profile_id="demo",
        explorer_base="https://testnet.nearblocks.io",
        rules_hash="4b6af96bc4c907f075e164c65b28c5acb24edf7696043dda916effe29eb8b725",
        submission_grace_secs=420,
        stake_yocto=500_000_000_000_000_000_000_000,  # 0.5 NEAR
        min_members=3,
        quorum_pct=70,
        is_demo=True,
    ),
    "sandbox": PactProfile(
        id="sandbox",
        name="sandbox",
        label="Judge Sandbox",
        colour="#2E7D32",
        contract_id="pact-sandbox.testnet",
        dao_id="pact-sandbox-relayer.testnet",
        backend_profile_id="sandbox",
        explorer_base="https://testnet.nearblocks.io",
        rules_hash="69b4d0d3a5e4a7ba72a62b6a0390e43c29766d9497175d13411ce27993581dc5",
        submission_grace_secs=180,
        stake_yocto=10_000_000_000_000_000_000_000,  # 0.01 NEAR
        min_members=1,
        quorum_pct=100,
        is_demo=False,
    ),
}

ALLOWLISTED_CONTRACT_IDS: Dict[str, PactProfile] = {
    p.contract_id: p for p in PROFILES.values()
}


def get_profile(name_or_contract: Optional[str] = None) -> PactProfile:
    """Resolve a profile by name ('demo', 'real', 'sandbox') or contract account ID.

    Defaults to 'real' if None or empty.
    Raises ValueError if the name or contract ID is not in the fixed allowlist.
    """
    if not name_or_contract:
        return PROFILES["real"]
    key = name_or_contract.strip().lower()
    if key in PROFILES:
        return PROFILES[key]
    raw = name_or_contract.strip()
    if raw in ALLOWLISTED_CONTRACT_IDS:
        return ALLOWLISTED_CONTRACT_IDS[raw]
    if key in ALLOWLISTED_CONTRACT_IDS:
        return ALLOWLISTED_CONTRACT_IDS[key]
    raise ValueError(
        f"Unknown or non-allowlisted contract profile '{name_or_contract}'. "
        f"Allowed profiles: {list(PROFILES.keys())}, "
        f"Allowed contracts: {list(ALLOWLISTED_CONTRACT_IDS.keys())}"
    )


def is_valid_profile_or_contract(val: Optional[str]) -> bool:
    if not val:
        return True
    try:
        get_profile(val)
        return True
    except ValueError:
        return False
