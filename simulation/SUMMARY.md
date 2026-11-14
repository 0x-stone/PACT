# PACT Simulation Findings & Reserve Mechanics

This report summarizes findings from the deterministic weekly settlement simulation script (`pact/scripts/simulate.py`), which models community fitness adherence against the on-chain integer settlement rules defined in `pact/rules.json`.

---

## 1. Settlement Formula & Invariants

Settlement decisions follow integer arithmetic:
$$\text{qualifiers} \times 100 \ge \text{enrolled} \times \text{quorum\_pct}$$

- **Success**: When quorum is met ($\ge 70\%$), a fixed weekly reward pool of 100 PACT (`100,000,000,000,000,000,000` base units) is divided equally among qualified participants:
  $$\text{reward} = \lfloor \text{mint\_amount} / \text{qualifiers} \rfloor$$
  Any integer division remainder is discarded and never minted.
- **Failure**: When quorum is missed ($< 70\%$), 25 PACT (`25,000,000,000,000,000,000` base units) is burned from the contract reserve balance.
- **Void**: When fewer than `min_members` (5) enroll, the challenge voids without minting or burning.
- **Stake Invariant**: In all outcomes (Success, Failure, Void), member stakes (0.5 NEAR) are strictly refundable. Stakes are never burned or confiscated.

---

## 2. Simulation Results

### Run A: High Adherence Community (fitness = 1.25, seed = 7, 12 weeks, 8 members)
```
Simulating 12 weeks x 8 members (seed 7, fitness 1.25)
Quorum met: 8 of 12 weeks (66.7%)
Total minted: 799,999,999,999,999,999,992 base units (~800 PACT)
Total burned: 100,000,000,000,000,000,000 base units (~100 PACT)
Reserve balance: 2,500,000,000,000,000,000,000 base units (~2,500 PACT remaining)
```

### Run B: Average Adherence Community (fitness = 1.0, seed = 42, 24 weeks, 8 members)
```
Simulating 24 weeks x 8 members (seed 42, fitness 1.0)
Quorum met: 9 of 24 weeks (37.5%)
Total minted: 899,999,999,999,999,999,994 base units (~900 PACT)
Total burned: 375,000,000,000,000,000,000 base units (~375 PACT)
Reserve balance: 2,225,000,000,000,000,000,000 base units (~2,225 PACT remaining)
```

---

## 3. Vulnerabilities & Economic Challenges Identified

1. **Reserve Depletion Under Low Adherence**:
   - At a 25 PACT burn rate per failed week, an initial reserve of 2,600 PACT provides a maximum runway of 104 consecutive failed weeks (~2 years) before depletion.
   - If the reserve reaches 0, burns gracefully cap at the available balance without contract panic (verified in unit tests).

2. **Quorum Boundary Sensitivity**:
   - For an 8-member group at 70% quorum, $\lceil 8 \times 0.7 \rceil = 6$ qualifiers are required. If 5 members qualify, the week fails despite a 62.5% success rate.
   - This creates social coordination pressure, which aligns with community accountability goals.

3. **Free-Rider Consideration**:
   - Because stakes are 100% refundable, members do not risk capital by enrolling and failing to walk.
   - Economic discipline is enforced collectively through token reserve burns and opportunity cost rather than slashing personal stakes.
