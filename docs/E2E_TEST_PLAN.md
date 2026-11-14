# PACT End-to-End Verification Test Plan
**Target**: PACT Mobile Application (`pact-latest.apk`) & Backend Services  
**Scope**: Profile Isolation (REAL / DEMO / SANDBOX), Event-Driven Data Layer, and RPC Gateway Stability

---

## 1. Environment & Setup

### Prerequisites
- Android device or emulator with Google Play services (or Health Connect).
- Fresh install or update from `pact-latest.apk` (located at workspace root).
- NEAR Testnet account with at least 0.2 NEAR for transactions.

### Artifacts Built
- `pact-latest.apk`: Compiled with `ProfileScope`, `RpcGateway`, `PactQueryCache`, and isolated SharedPreferences schema `v4_scoped`.

---

## 2. Test Scenarios

### Scenario A: Clean Cold Start & Default Profile Verification
1. Launch PACT from a clean install or clear app storage.
2. Sign in with testnet account (e.g. `yourname.testnet`).
3. **Verify Header**:
   - Top-right chip displays: **`Demo Mode`** (Orange chip).
   - "Today" screen renders cached or live challenge data.
   - No error popups or red boxes appear.
4. **Tap Top-Right Chip (`Demo Mode`)**:
   - The Profile Switcher modal opens.
   - Three distinct options are displayed:
     - **Production (Real)**: `pactcontract.testnet` (Min: 5 members, 70% quorum, 0.5 NEAR stake, 3600s grace)
     - **Demo Profile**: `pact-demo.testnet` (Min: 3 members, 70% quorum, 0.5 NEAR stake, 420s grace)
     - **Judge Sandbox**: `pact-sandbox.testnet` (Min: 1 member, 100% quorum, 0.01 NEAR stake, 180s grace)

---

### Scenario B: Judge Sandbox (Judge Mode) Flow
1. Select **Judge Sandbox** in the Profile Switcher.
2. **Verify Top Status**:
   - Chip updates to **`Judge Sandbox`** (Green chip).
   - Green banner card appears: **`Judge Sandbox [SELF-SERVICE]`**.
   - Subtitle: `Target: 10 steps · 5 min duration · 180s grace · 0.01 NEAR stake`.
3. **Inspect Scope via Debug Overlay**:
   - Tap the word **"Today"** in the top header.
   - The dark Debug Overlay appears showing:
     - `Profile: SANDBOX`
     - `Network: testnet`
     - `Contract: pact-sandbox.testnet`
     - `ScopeKey: sandbox__testnet__pact-sandbox_testnet__<account>`
     - `RPC Gateway: Idle` or `N in-flight`
     - `Cooldown: None (Circuit Closed)`
   - Tap `✕` to dismiss overlay.
4. **Create a Self-Service Challenge**:
   - Tap **`Start 5-Min Challenge (10 Steps)`**.
   - Button shows `Creating Challenge on NEAR...`.
   - Backend calls `set_next_challenge` on `pact-sandbox.testnet`.
   - On completion: Banner shows `Challenge sb-<timestamp> created! Enrollment open.`
5. **Join the Challenge**:
   - Tap **Join Challenge** (or open from bottom sheet).
   - Deposit: 0.01 NEAR stake.
   - Confirm transaction.
   - Verify ring updates to show: `1 of 1 enrolled. 1 hits the 100% goal.` (NOT 3 members, NOT 70%).

---

### Scenario C: Cross-Profile Strict Isolation Test (Zero State Bleed)
1. While `sb-<timestamp>` is active in Sandbox, open the Profile Switcher.
2. Switch to **Demo Mode**.
3. **Verify Today Screen**:
   - Sandbox challenge `sb-...` is **NEVER** displayed.
   - Target displays Demo requirements (e.g. 1,000 steps, 3 members, 70% quorum).
   - No void warnings from sandbox appear.
4. **Verify Challenges Tab**:
   - Tap **Challenges** in bottom navigation.
   - Filter through **All**, **Available**, **Joined**, **Completed**.
   - Verify **ZERO** `sb-...` challenges appear in the list. Only `demo-...` challenges appear.
5. Switch to **Production (Real)**:
   - Verify Challenges tab contains only `week-...` or real production challenges.
   - Zero `sb-...` or `demo-...` challenges appear.
6. Switch back to **Judge Sandbox**:
   - Active challenge `sb-...` reappears immediately with exact remaining countdown timer intact.

---

### Scenario D: Rate Limiting & Rapid Navigation Stress Test
1. Rapidly toggle between tabs: **Today -> Challenges -> Community -> Today** 10 times in 5 seconds.
2. **Verify UI Responsiveness**:
   - App does **NOT** freeze or hang.
   - Screen never blanks to white.
   - Stale-while-revalidate displays cached data instantly while refreshing asynchronously.
3. Tap **"Today"** to inspect Debug Overlay:
   - `inFlightRpcCount` stays bounded between 0 and 3 permits.
   - If any NEAR RPC rate limit (429) occurs, `cooldownUntilMs` activates and displays remaining backoff time without crashing or thread-locking.

---

### Scenario E: Sandbox Completion & Settlement Lifecycle
1. Walk or generate 10 steps (Health Connect).
2. Pull down to refresh or wait for the 60s live sync.
3. When the 5-minute window closes, phase transitions to `SUBMITTING`.
4. Foreground coordinator triggers auto-submit:
   - Device generates attestation and submits to `pact-sandbox.testnet`.
5. When submission grace (180s) closes, phase transitions to `SETTLING`.
6. Self-service settle executes on-chain.
7. Outcome resolves to **Success**.
8. Tap **Claim Reward**:
   - Reward (100 PACT) and stake (0.01 NEAR) are withdrawable.
   - Verify transaction succeeds on `testnet.nearblocks.io`.
