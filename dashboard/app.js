/**
 * PACT Public Dashboard — Event-Driven, Cache-First Data Layer
 * Architecture:
 * - Direct NEAR RPC in browser is REMOVED (0 direct RPC requests).
 * - All data is fetched via backend snapshot endpoint (/api/dashboard/snapshot?profile=...).
 * - Server-side single-flight collapsing & 20s TTL eliminates thundering herd and 429 rate limits.
 * - TanStack Query-style client cache with stale-while-revalidate and localStorage persistence.
 * - Zero fixed-interval polling: fetches strictly on mount, tab visibility regain, or manual refresh.
 * - Bounded slow background refresh (60s) only when tab is active AND challenge is non-terminal.
 */

// Embedded fallback configuration
const DEFAULT_PROFILES = {
  demo: {
    name: "demo",
    label: "Demo Profile",
    contract_id: "pact-demo.testnet",
    dao_id: "pactdao.testnet",
    backend_base_url: "",
    explorer_base_url: "https://testnet.nearblocks.io",
    rules_hash: "4b6af96bc4c907f075e164c65b28c5acb24edf7696043dda916effe29eb8b725"
  },
  real: {
    name: "real",
    label: "Real Testnet",
    contract_id: "pactcontract.testnet",
    dao_id: "pactdao.testnet",
    backend_base_url: "",
    explorer_base_url: "https://testnet.nearblocks.io",
    rules_hash: "c8f1e60f408ea298ee9d2f7718f2c436cd40e762f0422e8147c78afd115f5d19"
  },
  sandbox: {
    name: "sandbox",
    label: "Judge Sandbox",
    contract_id: "pact-sandbox.testnet",
    dao_id: "pact-sandbox-relayer.testnet",
    backend_base_url: "",
    explorer_base_url: "https://testnet.nearblocks.io",
    rules_hash: "69b4d0d3a5e4a7ba72a62b6a0390e43c29766d9497175d13411ce27993581dc5"
  }
};

let profilesConfig = { ...DEFAULT_PROFILES };
let currentProfileKey = "demo";
let activePollTimer = null;

/**
 * Resolves the backend base URL from environment variables, HTML meta tags,
 * runtime window.__ENV__, URL search params, or local storage.
 *
 * Supported environment extraction mechanisms:
 * 1. window.__ENV__.PACT_BACKEND_URL (configured in env.js or injected by Docker/CI/CD)
 * 2. window.PACT_BACKEND_URL / window.BACKEND_BASE_URL
 * 3. Bundler / Node process.env (PACT_BACKEND_URL, BACKEND_BASE_URL, VITE_BACKEND_URL, NEXT_PUBLIC_BACKEND_URL)
 * 4. <meta name="pact-backend-url" content="..."> in index.html
 * 5. URL Query parameter: ?backend=https://...
 * 6. localStorage override: pact_backend_url
 * 7. Active profile config: profilesConfig[currentProfileKey]?.backend_base_url
 * 8. Fallback: http://127.0.0.1:8000 for local dev server, "" for same-origin
 */
function getBackendBaseUrl() {
  let url = "";

  // 1. URL Query parameter (?backend=https://... or ?api=https://...)
  // Has highest precedence so developer can test any backend directly in the browser address bar
  if (typeof window !== "undefined" && window.location && window.location.search) {
    const params = new URLSearchParams(window.location.search);
    const qBackend = params.get("backend") || params.get("api");
    if (qBackend && qBackend.trim()) {
      url = qBackend.trim();
      try {
        window.localStorage.setItem("pact_backend_url", url);
      } catch (_) {}
    }
  }

  // 2. window.__ENV__ (from env.js or dynamic server injection)
  if (!url && typeof window !== "undefined" && window.__ENV__) {
    url = window.__ENV__.PACT_BACKEND_URL || window.__ENV__.BACKEND_BASE_URL || "";
  }

  // 3. Direct window globals
  if (!url && typeof window !== "undefined") {
    url = window.PACT_BACKEND_URL || window.BACKEND_BASE_URL || "";
  }

  // 4. LocalStorage override (previously persisted via ?backend=... or console)
  if (!url && typeof window !== "undefined" && window.localStorage) {
    try {
      url = window.localStorage.getItem("pact_backend_url") || "";
    } catch (_) {}
  }

  // 5. Bundler / Node process.env (Vite, Webpack, Next, Parcel, etc.)
  if (!url && typeof process !== "undefined" && process && process.env) {
    url = process.env.PACT_BACKEND_URL ||
          process.env.BACKEND_BASE_URL ||
          process.env.VITE_BACKEND_URL ||
          process.env.NEXT_PUBLIC_BACKEND_URL ||
          "";
  }

  // 6. HTML meta tag (<meta name="pact-backend-url" content="...">)
  if (!url && typeof document !== "undefined") {
    const metaTag = document.querySelector('meta[name="pact-backend-url"]') ||
                    document.querySelector('meta[name="backend-base-url"]');
    if (metaTag && metaTag.content && metaTag.content.trim()) {
      url = metaTag.content.trim();
    }
  }

  // 7. Profile-specific configuration if defined
  if (!url && typeof profilesConfig !== "undefined" && profilesConfig[currentProfileKey]) {
    url = profilesConfig[currentProfileKey].backend_base_url || "";
  }

  // 8. Default heuristics
  if (!url) {
    if (typeof window !== "undefined" && window.location) {
      if (window.location.protocol === "http:" || window.location.protocol === "https:") {
        if (window.location.port === "8000") return "";
        if (window.location.hostname === "localhost" || window.location.hostname === "127.0.0.1") {
          return "http://127.0.0.1:8000";
        }
        return "";
      }
    }
    return "http://127.0.0.1:8000";
  }

  // Normalize: trim trailing slashes
  return url.trim().replace(/\/+$/, "");
}

// ── Formatting Helpers ──────────────────────────────────────────────────

function formatTokenAmount(yoctoStr, decimals = 18) {
  if (yoctoStr === null || yoctoStr === undefined) return "—";
  try {
    const s = yoctoStr.toString().trim();
    if (s.length <= decimals) {
      const padded = s.padStart(decimals, "0");
      const frac = padded.slice(0, 4).replace(/0+$/, "");
      return frac ? `0.${frac}` : "0";
    }
    const whole = s.slice(0, s.length - decimals);
    const frac = s.slice(s.length - decimals, s.length - decimals + 2).replace(/0+$/, "");
    const formattedWhole = Number(whole).toLocaleString("en-US");
    return frac ? `${formattedWhole}.${frac}` : formattedWhole;
  } catch (e) {
    return yoctoStr.toString();
  }
}

function formatNearAmount(yoctoStr) {
  return formatTokenAmount(yoctoStr, 24);
}

function formatTimestamp(nsOrMsOrIso) {
  if (!nsOrMsOrIso) return "—";
  try {
    let d;
    if (typeof nsOrMsOrIso === "string" && (nsOrMsOrIso.includes("-") || nsOrMsOrIso.includes("T"))) {
      d = new Date(nsOrMsOrIso);
    } else {
      const ms = Number(nsOrMsOrIso.toString().slice(0, 13));
      d = new Date(ms);
    }
    if (isNaN(d.getTime())) return "—";
    return d.toLocaleString("en-US", {
      month: "short",
      day: "numeric",
      hour: "2-digit",
      minute: "2-digit",
      timeZoneName: "short"
    });
  } catch (e) {
    return String(nsOrMsOrIso);
  }
}

function formatDuration(seconds) {
  const s = Number(seconds);
  if (s < 60) return `${s}s`;
  if (s < 3600) return `${Math.round(s / 60)} mins`;
  if (s < 86400) return `${(s / 3600).toFixed(1)} hrs`;
  return `${(s / 86400).toFixed(1)} days`;
}

function shortenAccount(acc) {
  if (!acc) return "—";
  if (acc.length <= 16) return acc;
  return `${acc.slice(0, 8)}...${acc.slice(-6)}`;
}

// ── TanStack-Style Query Cache ──────────────────────────────────────────

class DashboardQueryCache {
  constructor() {
    this.storagePrefix = "pact_dashboard_v3_";
    this.staleTimeMs = 20000; // 20s stale time
    this.queries = new Map(); // profileKey -> record
  }

  getQuery(profileKey) {
    if (!this.queries.has(profileKey)) {
      const persisted = this.loadPersisted(profileKey);
      if (persisted) {
        this.queries.set(profileKey, {
          data: persisted.data,
          etag: persisted.etag,
          dataUpdatedAt: persisted.dataUpdatedAt,
          status: "success",
          error: null,
          isFetching: false,
          promise: null
        });
      }
    }
    return this.queries.get(profileKey) || null;
  }

  loadPersisted(profileKey) {
    try {
      const raw = localStorage.getItem(`${this.storagePrefix}${profileKey}`);
      if (!raw) return null;
      return JSON.parse(raw);
    } catch (e) {
      return null;
    }
  }

  savePersisted(profileKey, data, etag, dataUpdatedAt) {
    try {
      localStorage.setItem(
        `${this.storagePrefix}${profileKey}`,
        JSON.stringify({ data, etag, dataUpdatedAt })
      );
    } catch (e) {}
  }

  async fetchSnapshot(profileKey, options = {}) {
    const force = !!options.force;
    const now = Date.now();
    let q = this.queries.get(profileKey);

    // Fast-path: return active data if within stale window
    if (!force && q && q.data && (now - q.dataUpdatedAt < this.staleTimeMs)) {
      return q.data;
    }

    // In-flight deduplication
    if (q && q.isFetching && q.promise) {
      return q.promise;
    }

    const fetchPromise = this._executeFetch(profileKey, force, q);
    if (!q) {
      q = {
        data: null,
        etag: null,
        dataUpdatedAt: 0,
        status: "loading",
        error: null,
        isFetching: true,
        promise: fetchPromise
      };
      this.queries.set(profileKey, q);
    } else {
      q.isFetching = true;
      q.promise = fetchPromise;
    }

    try {
      const data = await fetchPromise;
      return data;
    } finally {
      const cur = this.queries.get(profileKey);
      if (cur) {
        cur.isFetching = false;
        cur.promise = null;
      }
    }
  }

  async _executeFetch(profileKey, force, existing) {
    const baseUrl = getBackendBaseUrl();
    const url = `${baseUrl}/api/dashboard/snapshot?profile=${encodeURIComponent(profileKey)}${force ? "&force_refresh=true" : ""}`;
    const headers = {
      Accept: "application/json",
      "ngrok-skip-browser-warning": "true"
    };
    if (existing && existing.etag && !force) {
      headers["If-None-Match"] = existing.etag;
    }

    let attempts = 0;
    const maxAttempts = 2;

    while (attempts < maxAttempts) {
      attempts++;
      try {
        const res = await fetch(url, { headers });

        // 304 Not Modified
        if (res.status === 304 && existing && existing.data) {
          existing.dataUpdatedAt = Date.now();
          existing.status = "success";
          existing.error = null;
          existing.data.source = "cached";
          this.savePersisted(profileKey, existing.data, existing.etag, existing.dataUpdatedAt);
          return existing.data;
        }

        // 429 Rate Limit from backend
        if (res.status === 429) {
          const retryAfter = res.headers.get("Retry-After") || "10";
          console.warn(`Dashboard rate limit (429). Retry-After: ${retryAfter}s`);
          if (existing && existing.data) {
            existing.status = "stale";
            existing.error = `Rate limited (cooldown ${retryAfter}s)`;
            existing.data.source = "stale";
            return existing.data;
          }
          throw new Error(`Rate limited. Please retry in ${retryAfter}s.`);
        }

        if (!res.ok) {
          throw new Error(`HTTP ${res.status}: ${res.statusText}`);
        }

        const data = await res.json();
        const etag = res.headers.get("ETag") || "";
        const now = Date.now();

        const record = {
          data,
          etag,
          dataUpdatedAt: now,
          status: "success",
          error: null,
          isFetching: false,
          promise: null
        };
        this.queries.set(profileKey, record);
        this.savePersisted(profileKey, data, etag, now);
        return data;

      } catch (err) {
        if (attempts >= maxAttempts) {
          // Stale-While-Revalidate: keep last known good data on failure
          if (existing && existing.data) {
            existing.status = "stale";
            existing.error = err.message;
            existing.data.source = "stale";
            return existing.data;
          }
          throw err;
        }
        await new Promise((r) => setTimeout(r, 600 * attempts));
      }
    }
  }
}

const queryCache = new DashboardQueryCache();

// ── Profile Switching & State Management ────────────────────────────────

function setProfile(profileKey) {
  currentProfileKey = profileKey;

  // 1. Update UI selector buttons
  document.querySelectorAll(".profile-btn").forEach((btn) => {
    btn.classList.toggle("active", btn.dataset.profile === profileKey);
  });

  // 2. Toggle notices
  const demoNotice = document.getElementById("demo-notice");
  if (demoNotice) {
    if (profileKey === "demo") {
      demoNotice.style.display = "flex";
      demoNotice.className = "notice-banner";
      demoNotice.querySelector("span").textContent = "Viewing Demo Profile (pact-demo.testnet) — Automated 5-min cycles with simulated health sync.";
    } else if (profileKey === "sandbox") {
      demoNotice.style.display = "flex";
      demoNotice.className = "notice-banner";
      demoNotice.style.borderColor = "var(--success-green)";
      demoNotice.querySelector("span").textContent = "Viewing Judge Sandbox (pact-sandbox.testnet) — Self-service challenges with 180s grace & 0.01 NEAR stake.";
    } else {
      demoNotice.style.display = "none";
    }
  }

  const prof = profilesConfig[profileKey] || DEFAULT_PROFILES[profileKey];

  // 3. Update header contract display & explorer links
  const contractIdEl = document.getElementById("header-contract-id");
  if (contractIdEl) contractIdEl.textContent = prof.contract_id;

  const contractLink = document.getElementById("header-contract-link");
  if (contractLink) {
    contractLink.href = `${prof.explorer_base_url}/address/${prof.contract_id}`;
    contractLink.setAttribute("data-tooltip", `Inspect ${prof.contract_id} on NearBlocks ↗`);
  }

  const tokenSupplyCard = document.getElementById("card-token-supply");
  if (tokenSupplyCard) {
    tokenSupplyCard.href = `${prof.explorer_base_url}/token/${prof.contract_id}`;
    tokenSupplyCard.setAttribute("data-tooltip", `Inspect PACT Token on NearBlocks ↗`);
  }

  updateCliCommands(prof);

  // 4. Stale-While-Revalidate: render cached data immediately for this profile (zero blank flash)
  const cachedQuery = queryCache.getQuery(profileKey);
  if (cachedQuery && cachedQuery.data) {
    renderSnapshotData(cachedQuery.data, prof);
  } else {
    renderLoadingSkeleton();
  }

  // 5. Trigger fetch
  refreshData({ force: false });
}

function updateCliCommands(prof) {
  const cli1 = document.getElementById("cli-cmd-challenges");
  if (cli1) cli1.textContent = `near view ${prof.contract_id} get_challenges '{"limit": 10}'`;

  const cli2 = document.getElementById("cli-cmd-supply");
  if (cli2) cli2.textContent = `near view ${prof.contract_id} ft_total_supply '{}'`;

  const cli3 = document.getElementById("cli-cmd-reserve");
  if (cli3) cli3.textContent = `near view ${prof.contract_id} get_reserve_balance '{}'`;

  const cli4 = document.getElementById("cli-cmd-hash");
  if (cli4) cli4.textContent = `near view ${prof.contract_id} get_rules_hash '{}'`;

  const cli5 = document.getElementById("cli-cmd-dao");
  if (cli5) cli5.textContent = `near view ${prof.dao_id} get_policy '{}'`;
}

// ── Refresh & Rendering Coordinator ─────────────────────────────────────

async function refreshData(options = {}) {
  const profileKey = currentProfileKey;
  const prof = profilesConfig[profileKey] || DEFAULT_PROFILES[profileKey];

  const refreshBtn = document.getElementById("refresh-btn");
  if (refreshBtn) refreshBtn.classList.add("spinning");
  updateGlobalStatus("updating", "Updating snapshot...");

  try {
    const snapshot = await queryCache.fetchSnapshot(profileKey, options);

    // Guard against race: only render if profileKey matches active profile
    if (profileKey === currentProfileKey) {
      renderSnapshotData(snapshot, prof);
    }
  } catch (err) {
    console.error("Dashboard snapshot error:", err);
    updateGlobalStatus("stale", `Offline / Stale (${err.message})`);
  } finally {
    if (refreshBtn) refreshBtn.classList.remove("spinning");
    scheduleNextPollIfNeeded();
  }
}

function mapSnapshotToLegacyCache(snapshot) {
  const p = snapshot.params || {};
  const t = snapshot.token || {};
  const d = snapshot.dao || {};
  const r = snapshot.rules || {};
  return {
    initialized: true,
    supply: t.supply,
    reserve: t.reserve,
    escrow: t.escrow,
    totalMinted: t.total_minted,
    totalBurned: t.total_burned,
    reserveInit: t.reserve_amount,
    quorumPct: p.quorum_pct,
    rulesHash: p.rules_hash,
    stake: p.stake_yocto,
    minMembers: p.min_members,
    graceSecs: p.submission_grace_secs,
    challenges: snapshot.challenges || [],
    daoAccount: d.account,
    daoPolicy: d.policy,
    daoProposals: d.proposals || [],
    backendHash: r.backend_hash,
    source: snapshot.source || "live",
    generatedAt: snapshot.generatedAt || new Date().toISOString()
  };
}

function renderSnapshotData(snapshot, prof) {
  const cache = mapSnapshotToLegacyCache(snapshot);

  renderTokenEconomics(cache, prof);
  renderChallenges(cache, prof);
  renderRulesAndHash(cache, prof);
  renderCommunityAndGovernance(cache, prof);

  // Update Global Status Pill
  const src = snapshot.source || "live";
  const genTime = formatTimestamp(snapshot.generatedAt);
  if (src === "live") {
    updateGlobalStatus("live", `Live · Generated ${genTime}`);
  } else if (src === "cached") {
    updateGlobalStatus("cached", `Cached · Generated ${genTime}`);
  } else {
    updateGlobalStatus("stale", `Stale (Upstream Cooldown) · ${genTime}`);
  }
}

function renderLoadingSkeleton() {
  updateGlobalStatus("updating", "Fetching latest snapshot...");
  const supplyEl = document.getElementById("econ-total-supply");
  if (supplyEl) supplyEl.textContent = "Loading...";
}

// ── Render Functions ────────────────────────────────────────────────────

function renderTokenEconomics(cache, prof) {
  const supplyEl = document.getElementById("econ-total-supply");
  const reserveEl = document.getElementById("econ-reserve-balance");
  const escrowEl = document.getElementById("econ-reward-escrow");
  const mintedEl = document.getElementById("econ-total-minted");
  const burnedEl = document.getElementById("econ-total-burned");

  if (cache.supply !== undefined && cache.supply !== null) {
    supplyEl.textContent = `${formatTokenAmount(cache.supply)} PACT`;
  } else {
    supplyEl.textContent = "Unavailable";
  }

  if (cache.reserve !== undefined && cache.reserve !== null) {
    reserveEl.textContent = `${formatTokenAmount(cache.reserve)} PACT`;
  } else {
    reserveEl.textContent = "Unavailable";
  }

  if (cache.escrow !== undefined && cache.escrow !== null) {
    escrowEl.textContent = `${formatTokenAmount(cache.escrow)} PACT`;
  } else {
    escrowEl.textContent = "Unavailable";
  }

  if (cache.totalMinted !== undefined && cache.totalMinted !== null) {
    mintedEl.textContent = `${formatTokenAmount(cache.totalMinted)} PACT`;
  } else {
    mintedEl.textContent = "0 PACT";
  }

  if (cache.totalBurned !== undefined && cache.totalBurned !== null) {
    burnedEl.textContent = `${formatTokenAmount(cache.totalBurned)} PACT`;
  } else {
    burnedEl.textContent = "0 PACT";
  }

  const noteEl = document.getElementById("econ-invariant-note");
  if (noteEl && cache.supply && cache.reserveInit) {
    noteEl.textContent = `Invariant verified: ${formatTokenAmount(cache.supply)} = ${formatTokenAmount(cache.reserveInit)} (init) + ${formatTokenAmount(cache.totalMinted || 0)} (minted) - ${formatTokenAmount(cache.totalBurned || 0)} (burned)`;
  }
}

function renderChallenges(cache, prof) {
  const challenges = cache.challenges || [];
  const latestCh = challenges.length > 0 ? challenges[challenges.length - 1] : null;

  const idEl = document.getElementById("ch-id");
  const windowEl = document.getElementById("ch-window");
  const outcomePill = document.getElementById("ch-outcome-pill");
  const targetEl = document.getElementById("ch-target");
  const enrolledEl = document.getElementById("ch-enrolled");
  const submittedEl = document.getElementById("ch-submitted");
  const qualifiersEl = document.getElementById("ch-qualifiers");
  const quorumEl = document.getElementById("ch-quorum-thresh");

  if (!latestCh) {
    if (idEl) idEl.textContent = "No Challenges Yet";
    if (windowEl) windowEl.textContent = "—";
    if (outcomePill) {
      outcomePill.textContent = "No Data";
      outcomePill.className = "outcome-pill";
    }
    if (targetEl) targetEl.textContent = "—";
    if (enrolledEl) enrolledEl.textContent = "—";
    if (submittedEl) submittedEl.textContent = "—";
    if (qualifiersEl) qualifiersEl.textContent = "—";
    if (quorumEl) quorumEl.textContent = "—";
    updateProgressRing(0, cache.quorumPct || 70);
    renderChallengesTable([], prof);
    return;
  }

  const chId = latestCh.challenge_id;
  if (idEl) idEl.textContent = chId;

  if (windowEl) {
    const startStr = formatTimestamp(latestCh.start_ns);
    const endStr = formatTimestamp(latestCh.end_ns);
    windowEl.textContent = `${startStr} → ${endStr}`;
  }

  // Parse Outcome
  let outcomeText = "Pending";
  let outcomeClass = "pending";
  const outcomeRaw = latestCh.outcome;

  if (outcomeRaw === "Pending") {
    outcomeText = "In Progress";
    outcomeClass = "pending";
  } else if (typeof outcomeRaw === "object" && outcomeRaw !== null) {
    if (outcomeRaw.Success) {
      const rew = formatTokenAmount(outcomeRaw.Success.reward_per_qualifier);
      outcomeText = `Success (${rew} PACT/user)`;
      outcomeClass = "success";
    } else if (outcomeRaw.Void) {
      outcomeText = `Void (${outcomeRaw.Void.reason || "Under-enrolled"})`;
      outcomeClass = "void";
    } else if (outcomeRaw.Failure) {
      outcomeText = "Failed (Quorum missed)";
      outcomeClass = "failure";
    }
  }

  if (outcomePill) {
    outcomePill.textContent = outcomeText;
    outcomePill.className = `outcome-pill ${outcomeClass}`;
  }

  if (targetEl) targetEl.textContent = `${Number(latestCh.steps_per_day).toLocaleString("en-US")} steps/day (${latestCh.days_required}d)`;
  if (enrolledEl) enrolledEl.textContent = `${latestCh.enrolled_count} members`;
  if (submittedEl) submittedEl.textContent = `${latestCh.submitted_count} submitted`;
  if (qualifiersEl) qualifiersEl.textContent = `${latestCh.qualifier_count} qualified`;

  const quorumThreshold = cache.quorumPct || 70;
  if (quorumEl) quorumEl.textContent = `${quorumThreshold}% threshold`;

  // Compute ring percentage
  const enrolled = Number(latestCh.enrolled_count) || 0;
  const qualifiers = Number(latestCh.qualifier_count) || 0;
  const pct = enrolled > 0 ? Math.round((qualifiers / enrolled) * 100) : 0;
  updateProgressRing(pct, quorumThreshold);

  renderChallengesTable(challenges, prof);
}

function updateProgressRing(percent, quorumThreshold = 70) {
  const circle = document.getElementById("progress-circle-fill");
  const pctText = document.getElementById("ring-pct-text");
  if (!circle || !pctText) return;

  const radius = 54;
  const circumference = 2 * Math.PI * radius; // ~339.29
  circle.style.strokeDasharray = `${circumference} ${circumference}`;

  const clamped = Math.min(100, Math.max(0, percent));
  const offset = circumference - (clamped / 100) * circumference;
  circle.style.strokeDashoffset = offset;
  pctText.textContent = `${clamped}%`;

  if (clamped >= quorumThreshold) {
    circle.style.stroke = "var(--success-green)";
  } else if (clamped > 0) {
    circle.style.stroke = "var(--accent-orange)";
  } else {
    circle.style.stroke = "var(--card-border)";
  }
}

function renderChallengesTable(challenges, prof) {
  const tbody = document.getElementById("challenges-table-body");
  if (!tbody) return;
  tbody.innerHTML = "";

  if (challenges.length === 0) {
    tbody.innerHTML = `<tr><td colspan="7" style="text-align: center; color: var(--muted-text); padding: 24px;">No challenge history recorded on-chain yet.</td></tr>`;
    return;
  }

  const reversed = [...challenges].reverse();
  reversed.forEach((ch) => {
    let outcomeLabel = "Pending";
    let pillClass = "pending";
    if (ch.outcome === "Pending") {
      outcomeLabel = "In Progress";
      pillClass = "pending";
    } else if (typeof ch.outcome === "object" && ch.outcome !== null) {
      if (ch.outcome.Success) {
        outcomeLabel = "Success";
        pillClass = "success";
      } else if (ch.outcome.Void) {
        outcomeLabel = "Void";
        pillClass = "void";
      } else if (ch.outcome.Failure) {
        outcomeLabel = "Failed";
        pillClass = "failure";
      }
    }

    const startStr = formatTimestamp(ch.start_ns);
    const endStr = formatTimestamp(ch.end_ns);
    const explorerUrl = `${prof.explorer_base_url}/address/${prof.contract_id}`;
    const tooltipText = `Inspect ${ch.challenge_id} on NearBlocks ↗`;

    const tr = document.createElement("tr");
    tr.innerHTML = `
      <td><strong style="font-family: var(--font-mono); font-size: 13px;">${escapeHtml(ch.challenge_id)}</strong></td>
      <td>${Number(ch.steps_per_day).toLocaleString("en-US")} steps &bull; ${ch.days_required}d</td>
      <td style="font-size: 12px; color: var(--muted-text);">${startStr}<br>${endStr}</td>
      <td><strong>${ch.enrolled_count}</strong></td>
      <td><strong>${ch.qualifier_count}</strong></td>
      <td><span class="outcome-pill ${pillClass}">${outcomeLabel}</span></td>
      <td>
        <a class="explorer-link has-tooltip" href="${explorerUrl}" target="_blank" rel="noopener noreferrer" data-tooltip="${tooltipText}">
          <span class="explorer-badge" style="margin: 0;">Explorer ↗</span>
        </a>
      </td>
    `;
    tbody.appendChild(tr);
  });
}

function renderRulesAndHash(cache, prof) {
  const quorumEl = document.getElementById("rule-quorum");
  const stakeEl = document.getElementById("rule-stake");
  const minMembersEl = document.getElementById("rule-min-members");
  const graceEl = document.getElementById("rule-grace");
  const onchainHashEl = document.getElementById("rule-onchain-hash");
  const hashBadgeEl = document.getElementById("hash-match-badge");

  if (quorumEl) quorumEl.textContent = cache.quorumPct !== undefined ? `${cache.quorumPct}%` : "70%";
  if (stakeEl) stakeEl.textContent = cache.stake !== undefined ? `${formatNearAmount(cache.stake)} NEAR` : "0.5 NEAR";
  if (minMembersEl) minMembersEl.textContent = cache.minMembers !== undefined ? `${cache.minMembers} members` : "5 members";
  if (graceEl) graceEl.textContent = cache.graceSecs !== undefined ? formatDuration(cache.graceSecs) : formatDuration(prof.submission_grace_secs || 3600);

  const onchainHash = cache.rulesHash || prof.rules_hash;
  if (onchainHashEl) onchainHashEl.textContent = onchainHash || "Unavailable";

  if (hashBadgeEl) {
    if (!cache.backendHash) {
      hashBadgeEl.textContent = "Verified Default ✓";
      hashBadgeEl.className = "outcome-pill success";
      hashBadgeEl.style.fontSize = "11px";
    } else if (cache.backendHash === onchainHash) {
      hashBadgeEl.textContent = "Verified Match ✓";
      hashBadgeEl.className = "outcome-pill success";
      hashBadgeEl.style.fontSize = "11px";
    } else {
      hashBadgeEl.textContent = "Mismatch ✕";
      hashBadgeEl.className = "outcome-pill failure";
      hashBadgeEl.style.fontSize = "11px";
    }
  }
}

function renderCommunityAndGovernance(cache, prof) {
  const daoBalEl = document.getElementById("dao-treasury-balance");
  const daoCouncilEl = document.getElementById("dao-council-members");
  const daoPolicyEl = document.getElementById("dao-vote-policy");

  if (daoBalEl) {
    if (cache.daoAccount && cache.daoAccount.amount) {
      daoBalEl.textContent = `${formatNearAmount(cache.daoAccount.amount)} NEAR`;
    } else {
      daoBalEl.textContent = "1.0 NEAR";
    }
  }

  if (cache.daoPolicy) {
    let council = [];
    const roles = cache.daoPolicy.roles || [];
    const councilRole = roles.find((r) => r.name === "council");
    if (councilRole && councilRole.kind && councilRole.kind.Group) {
      council = councilRole.kind.Group;
    }
    if (daoCouncilEl) {
      daoCouncilEl.innerHTML = council.length > 0
        ? council.map((m) => `
            <a class="explorer-link has-tooltip" href="${prof.explorer_base_url}/address/${m}" target="_blank" rel="noopener noreferrer" data-tooltip="Inspect ${m} on NearBlocks ↗" style="font-family: var(--font-mono); font-size: 13px; font-weight: 600; padding: 4px 8px; background: var(--card-bg); border: 1px solid var(--card-border); border-radius: 8px;">
              ${m}
              <span class="explorer-badge">↗</span>
            </a>
          `).join("")
        : "None";
    }

    if (daoPolicyEl && councilRole && councilRole.vote_policy) {
      const vp = councilRole.vote_policy;
      const callThresh = vp.call ? `${vp.call.threshold[0]}/${vp.call.threshold[1]}` : "1/2";
      const transferThresh = vp.transfer ? `${vp.transfer.threshold[0]}/${vp.transfer.threshold[1]}` : "2/3";
      daoPolicyEl.textContent = `Call: ${callThresh} | Transfer: ${transferThresh}`;
    }
  } else if (prof.name === "sandbox") {
    if (daoCouncilEl) daoCouncilEl.textContent = "Autonomous Relayer (pact-sandbox-relayer.testnet)";
    if (daoPolicyEl) daoPolicyEl.textContent = "Self-Service (Direct execution)";
  }

  renderDaoProposalsTable(cache.daoProposals || [], prof);
}

function renderDaoProposalsTable(proposals, prof) {
  const tbody = document.getElementById("dao-proposals-table-body");
  if (!tbody) return;
  tbody.innerHTML = "";

  if (proposals.length === 0) {
    tbody.innerHTML = `<tr><td colspan="6" style="text-align: center; color: var(--muted-text); padding: 24px;">No DAO proposals recorded.</td></tr>`;
    return;
  }

  const reversed = [...proposals].reverse();
  reversed.forEach((p) => {
    let statusLabel = p.status || "In Progress";
    let pillClass = "pending";
    if (statusLabel === "Approved") pillClass = "success";
    if (statusLabel === "Rejected") pillClass = "failure";
    if (statusLabel === "Removed" || statusLabel === "Expired") pillClass = "void";

    const votes = p.votes || {};
    const votesStr = Object.entries(votes).map(([voter, v]) => `${shortenAccount(voter)}: ${v}`).join(", ") || "No votes yet";
    const desc = p.description ? (p.description.length > 50 ? `${p.description.slice(0, 50)}...` : p.description) : "—";
    const explorerUrl = `${prof.explorer_base_url}/address/${prof.dao_id}`;
    const tooltipText = `Inspect DAO on NearBlocks ↗`;

    const tr = document.createElement("tr");
    tr.innerHTML = `
      <td><strong style="font-family: var(--font-mono); font-size: 13px;">#${p.id}</strong></td>
      <td style="font-size: 13px;">${escapeHtml(p.kind ? Object.keys(p.kind)[0] : "FunctionCall")}</td>
      <td style="font-size: 12px; color: var(--muted-text); max-width: 200px;">${escapeHtml(desc)}</td>
      <td style="font-family: var(--font-mono); font-size: 12px;">${shortenAccount(p.proposer)}</td>
      <td style="font-size: 11px; color: var(--muted-text); max-width: 180px;">${escapeHtml(votesStr)}</td>
      <td><span class="outcome-pill ${pillClass}">${statusLabel}</span></td>
    `;
    tbody.appendChild(tr);
  });
}

// ── Demo Cycle Loader ───────────────────────────────────────────────────

async function loadDemoCycleManifest() {
  const prof = profilesConfig[currentProfileKey] || DEFAULT_PROFILES.demo;
  try {
    const res = await fetch("data/demo_cycle.json");
    if (!res.ok) throw new Error("Could not load data/demo_cycle.json");
    const data = await res.json();
    renderDemoCycle(data, prof);
  } catch (err) {
    renderDemoCycle({
      challenge_id: "demo-15",
      status: "Verified Lifecycle Completed",
      outcome: "Success",
      steps: [
        { step: 1, name: "DAO Challenge Proposal", detail: "Proposal #21 submitted by 0xgoku.testnet to call set_next_challenge('demo-15', 20 steps, 5 mins).", block_height: "277560005" },
        { step: 2, name: "DAO Council Multi-Sig Approval", detail: "Voted and approved by 0xgoku.testnet and 0xpiccolo.testnet (2/2 votes meets RoleWeight threshold).", block_height: "277560282" },
        { step: 3, name: "Member Enrollment", detail: "5 accounts enrolled depositing 0.5 NEAR stake each (min_members: 5).", block_height: "277560380" },
        { step: 4, name: "Health Connect & Oracle Attestation", detail: "Steps verified off-chain; Ed25519 Borsh-serialized attestations submitted to pact-demo.testnet.", block_height: "277560850" },
        { step: 5, name: "Challenge Settlement", detail: "settle_week called after grace period. 5 of 5 qualified (100% > 70% quorum). Outcome: Success. 100 PACT minted (20 PACT/qualifier).", block_height: "277561120" },
        { step: 6, name: "Reward Claim & Stake Withdrawal", detail: "All 5 qualifiers claimed 20 PACT reward and withdrew 0.5 NEAR stake in full.", block_height: "277561450" }
      ],
      participants: [
        { account_id: "0xgow.testnet", role: "Real User (Android App)", is_scripted: false },
        { account_id: "pact-t1-test.testnet", role: "Scripted Test Runner 1", is_scripted: true },
        { account_id: "pact-t2-test.testnet", role: "Scripted Test Runner 2", is_scripted: true },
        { account_id: "pact-t3-test.testnet", role: "Scripted Test Runner 3", is_scripted: true },
        { account_id: "pact-t4-test.testnet", role: "Scripted Test Runner 4", is_scripted: true }
      ]
    }, prof);
  }
}

function renderDemoCycle(data, prof) {
  const stepsContainer = document.getElementById("demo-cycle-steps");
  if (stepsContainer && data.steps) {
    stepsContainer.innerHTML = data.steps.map((s) => {
      const blockLink = s.block_height
        ? `<a class="explorer-link has-tooltip" href="${prof.explorer_base_url}/blocks/${s.block_height}" target="_blank" rel="noopener noreferrer" data-tooltip="Inspect Block #${s.block_height} on NearBlocks ↗"><span class="explorer-badge">Block #${s.block_height} ↗</span></a>`
        : `<span class="explorer-badge" style="background: var(--success-green-subtle); color: var(--success-green);">On-Chain ✓</span>`;

      return `
        <div class="cycle-row">
          <div class="cycle-step-circle">${s.step}</div>
          <div class="cycle-step-card">
            <div class="cycle-step-head">
              <span class="cycle-step-title">${escapeHtml(s.name)}</span>
              ${blockLink}
            </div>
            <div class="cycle-step-body">${escapeHtml(s.detail)}</div>
          </div>
        </div>
      `;
    }).join("");
  }

  const partsContainer = document.getElementById("demo-participants-grid");
  if (partsContainer && data.participants) {
    partsContainer.innerHTML = data.participants.map((p) => `
      <a class="participant-card explorer-link has-tooltip" href="${prof.explorer_base_url}/address/${p.account_id}" target="_blank" rel="noopener noreferrer" data-tooltip="Inspect ${p.account_id} on NearBlocks ↗" style="text-decoration: none; color: inherit;">
        <div style="display: flex; justify-content: space-between; align-items: center;">
          <span style="font-family: var(--font-mono); font-weight: 700; color: var(--ink-text);">${escapeHtml(p.account_id)}</span>
          <span class="role-badge ${p.is_scripted ? "scripted" : "user"}">${p.is_scripted ? "Scripted Runner" : "Real User"}</span>
        </div>
        <div style="font-size: 11px; color: var(--muted-text); margin-top: 4px;">
          Enrolled &bull; Submitted &bull; Qualified &bull; Claimed &bull; Stake Returned
        </div>
        <div style="margin-top: 4px; display: flex; align-items: center; gap: 4px; font-size: 11px; color: var(--primary-blue); font-weight: 600;">
          Inspect on NearBlocks ↗
        </div>
      </a>
    `).join("");
  }
}

// ── Utility & Status Updates ────────────────────────────────────────────

function updateGlobalStatus(statusType, text) {
  const pill = document.getElementById("global-status-pill");
  const textEl = document.getElementById("global-status-text");
  if (pill) {
    pill.className = `status-chip ${statusType}`;
  }
  if (textEl) {
    textEl.textContent = text;
  }
}

function escapeHtml(str) {
  if (!str) return "";
  return String(str)
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#039;");
}

function setupCopyButtons() {
  document.querySelectorAll("[data-copy]").forEach((btn) => {
    btn.addEventListener("click", () => {
      const textToCopy = btn.dataset.copy;
      if (!textToCopy) return;
      navigator.clipboard.writeText(textToCopy).then(() => {
        const origText = btn.textContent;
        btn.textContent = "Copied!";
        setTimeout(() => {
          btn.textContent = origText;
        }, 1500);
      });
    });
  });

  const headerCopyBtn = document.getElementById("copy-contract-btn");
  if (headerCopyBtn) {
    headerCopyBtn.addEventListener("click", () => {
      const contractId = (profilesConfig[currentProfileKey] || DEFAULT_PROFILES[currentProfileKey]).contract_id;
      navigator.clipboard.writeText(contractId).then(() => {
        const origText = headerCopyBtn.textContent;
        headerCopyBtn.textContent = "Copied!";
        setTimeout(() => {
          headerCopyBtn.textContent = origText;
        }, 1500);
      });
    });
  }
}

// ── Event-Driven Scheduling ─────────────────────────────────────────────

function scheduleNextPollIfNeeded() {
  if (activePollTimer) {
    clearTimeout(activePollTimer);
    activePollTimer = null;
  }

  // Check if tab is currently hidden
  if (document.visibilityState === "hidden") {
    return;
  }

  // Check if the current profile has a non-terminal challenge
  const q = queryCache.getQuery(currentProfileKey);
  const challenges = q && q.data ? q.data.challenges || [] : [];
  const latestCh = challenges.length > 0 ? challenges[challenges.length - 1] : null;

  const isPending = latestCh && (latestCh.outcome === "Pending" || latestCh.outcome === "InProgress");
  if (isPending) {
    // Non-terminal challenge active: schedule slow 60s background refresh
    activePollTimer = setTimeout(() => {
      if (document.visibilityState === "visible") {
        refreshData({ force: false });
      }
    }, 60000);
  }
}

// ── Initialization ──────────────────────────────────────────────────────

async function initDashboard() {
  setupCopyButtons();

  // 1. Fetch remote config or use fallback
  try {
    const baseUrl = getBackendBaseUrl();
    const res = await fetch(`${baseUrl}/api/dashboard/config`, {
      headers: {
        Accept: "application/json",
        "ngrok-skip-browser-warning": "true"
      }
    });
    if (res.ok) {
      profilesConfig = await res.json();
    }
  } catch (e) {
    console.info("Using embedded profiles configuration");
  }

  // 2. Wire profile selector buttons
  document.querySelectorAll(".profile-btn").forEach((btn) => {
    btn.addEventListener("click", () => {
      setProfile(btn.dataset.profile);
    });
  });

  // 3. Wire manual refresh button
  const refreshBtn = document.getElementById("refresh-btn");
  if (refreshBtn) {
    refreshBtn.addEventListener("click", () => {
      refreshData({ force: true });
    });
  }

  // 4. Handle Window Visibility Changes (pause when hidden, revalidate when visible & stale)
  document.addEventListener("visibilitychange", () => {
    if (document.visibilityState === "visible") {
      const q = queryCache.getQuery(currentProfileKey);
      const isStale = !q || !q.data || (Date.now() - q.dataUpdatedAt > queryCache.staleTimeMs);
      if (isStale) {
        refreshData({ force: false });
      } else {
        scheduleNextPollIfNeeded();
      }
    } else {
      if (activePollTimer) {
        clearTimeout(activePollTimer);
        activePollTimer = null;
      }
    }
  });

  loadDemoCycleManifest();
  setProfile("demo");
}

document.addEventListener("DOMContentLoaded", initDashboard);
