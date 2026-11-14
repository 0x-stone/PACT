"""Community fitness pact on NEAR — shared rules package.

The single source of truth for challenge parameters is ``pact/rules.json``.
This package exposes the typed loader used by every consumer:

- the FastAPI backend (read-only ``GET /api/pact/rules``),
- the contract init script (``pact/scripts/init_contract.py``),
- the simulation (``pact/scripts/simulate.py``).
"""

from .rules import DEFAULT_RULES_PATH, PactRules, load_rules

__all__ = ["DEFAULT_RULES_PATH", "PactRules", "load_rules"]
