"""
Thin RPC helpers used by all DAO scripts.
All calls go through here so the URL is in one place.
"""
import base64, json, random, subprocess, time, urllib.error, urllib.request
from typing import Any

from dao.config import RPC_URL, NEAR_CLI, NETWORK


FALLBACK_RPC_URLS = [
    "https://rpc.testnet.fastnear.com",
    "https://test.rpc.fastnear.com",
    RPC_URL,
    "https://rpc.testnet.pagoda.co",
    "https://rpc.testnet.near.org",
]

_VIEW_CACHE: dict[str, tuple[float, Any]] = {}
VIEW_CACHE_TTL_SECS = 5.0


def rpc_call(method: str, params: dict, max_retries_per_url: int = 2) -> dict:
    body = json.dumps({"jsonrpc": "2.0", "id": "1", "method": method, "params": params}).encode()
    last_err = None
    for url in FALLBACK_RPC_URLS:
        for attempt in range(max_retries_per_url):
            try:
                req = urllib.request.Request(
                    url, data=body, headers={"Content-Type": "application/json", "User-Agent": "PactDao/1.0"}
                )
                with urllib.request.urlopen(req, timeout=12) as r:
                    return json.loads(r.read())
            except urllib.error.HTTPError as e:
                last_err = e
                if e.code in (429, 503):
                    retry_after = e.headers.get("Retry-After")
                    sleep_time = float(retry_after) if retry_after and retry_after.isdigit() else (0.4 * (2 ** attempt) + random.uniform(0.05, 0.2))
                    time.sleep(sleep_time)
                    continue
                else:
                    break
            except Exception as e:
                last_err = e
                break
    raise RuntimeError(f"All RPC endpoints failed: {last_err}")


def view(contract_id: str, method: str, args: dict | None = None, bypass_cache: bool = False) -> Any:
    """Call a read-only method; return parsed result or raise. Cached for 5s by default."""
    cache_key = f"{contract_id}::{method}::{json.dumps(args or {}, sort_keys=True)}"
    now = time.time()
    if not bypass_cache and cache_key in _VIEW_CACHE:
        ts, cached_val = _VIEW_CACHE[cache_key]
        if now - ts < VIEW_CACHE_TTL_SECS:
            return cached_val

    args_b64 = base64.b64encode(json.dumps(args or {}).encode()).decode()
    resp = rpc_call("query", {
        "request_type": "call_function",
        "finality":     "final",
        "account_id":   contract_id,
        "method_name":  method,
        "args_base64":  args_b64,
    })
    if "error" in resp:
        raise RuntimeError(f"RPC error calling {contract_id}::{method}: {resp['error']}")
    result = resp.get("result", {})
    if "error" in result:
        raise RuntimeError(f"Contract error calling {contract_id}::{method}: {result['error']}")
    raw = result.get("result")
    if raw is None:
        return None
    parsed = json.loads(bytes(raw))
    _VIEW_CACHE[cache_key] = (now, parsed)
    return parsed


def view_account(account_id: str) -> dict:
    resp = rpc_call("query", {
        "request_type": "view_account",
        "finality":     "final",
        "account_id":   account_id,
    })
    if "error" in resp:
        raise RuntimeError(f"Account not found: {account_id}: {resp['error']}")
    return resp["result"]


def near_cli(contract_id: str, method: str, json_args: str,
             sign_as: str, deposit: str = "0", gas: str = "300.0 Tgas") -> str:
    """
    near-cli-rs 0.22.2 verified argument chain (from --help):
      contract call-function as-transaction <contract> <method>
        json-args '<json>'
        prepaid-gas '<N Tgas>'        ← Tgas unit required, not raw gas
        attached-deposit '<N NEAR>'
        sign-as <account>             ← sign-as BEFORE network-config
        network-config testnet
        sign-with-keychain
        send
    """
    cmd = [
        NEAR_CLI,
        "contract", "call-function", "as-transaction",
        contract_id, method,
        "json-args",        json_args,
        "prepaid-gas",      gas,              # e.g. "300.0 Tgas"
        "attached-deposit", _yocto_to_near_str(deposit),
        "sign-as",          sign_as,
        "network-config",   NETWORK,
        "sign-with-legacy-keychain",
        "send",
    ]
    result = subprocess.run(cmd, capture_output=True, text=True)
    output = result.stdout + result.stderr
    tx_hash = extract_tx_hash(output)
    if result.returncode != 0:
        # If the tx reached the chain but the contract panicked, report clearly.
        if tx_hash and tx_hash != "(hash not found in output)":
            raise RuntimeError(
                f"Transaction reached chain but contract panicked (rc={result.returncode}).\n"
                f"TX: {tx_hash}\n"
                f"Explorer: https://explorer.testnet.near.org/transactions/{tx_hash}\n"
                f"Output:\n{output}"
            )
        raise RuntimeError(f"near CLI failed (rc={result.returncode}):\n{output}")
    return output


def extract_tx_hash(output: str) -> str:
    """Pull the transaction hash out of near-cli-rs output."""
    for line in output.splitlines():
        if "Transaction ID:" in line:
            return line.split("Transaction ID:")[-1].strip()
    return "(hash not found in output)"


def _yocto_to_near_str(yocto: str) -> str:
    """Convert yocto string to 'N NEAR' format for near-cli-rs."""
    if yocto == "0":
        return "0 NEAR"
    near = int(yocto) / 10**24
    if near == int(near):
        return f"{int(near)} NEAR"
    return f"{near:.6f} NEAR"
