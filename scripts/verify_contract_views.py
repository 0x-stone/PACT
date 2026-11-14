"""Verify all view methods on the deployed contract and DAO."""
import json, urllib.request, base64

RPC = "https://rpc.testnet.near.org"

def call_view(contract_id, method, args=None):
    args_b64 = base64.b64encode(json.dumps(args or {}).encode()).decode()
    body = json.dumps({"jsonrpc":"2.0","id":"1","method":"query","params":{
        "request_type":"call_function","finality":"final",
        "account_id":contract_id,"method_name":method,"args_base64":args_b64
    }}).encode()
    req = urllib.request.Request(RPC, data=body, headers={"Content-Type":"application/json"})
    with urllib.request.urlopen(req, timeout=15) as r:
        resp = json.loads(r.read())
    if "error" in resp:
        return None, str(resp["error"])
    result = resp.get("result",{})
    if "error" in result:
        return None, str(result["error"])
    raw = result.get("result")
    if raw is None:
        return None, "no result field"
    return json.loads(bytes(raw)), None

CONTRACT = "pactcontract.testnet"
DAO      = "pactdao.testnet"

print("=" * 60)
print("CONTRACT VIEW METHODS")
print("=" * 60)

views = [
    ("get_rules_hash",           {}),
    ("get_dao_account_id",       {}),
    ("get_attestation_public_key",{}),
    ("get_quorum_pct",           {}),
    ("get_min_members",          {}),
    ("get_submission_grace_secs",{}),
    ("get_reserve_balance",      {}),
    ("get_reward_escrow",        {}),
    ("get_total_minted",         {}),
    ("get_total_burned",         {}),
    ("ft_total_supply",          {}),
    ("ft_metadata",              {}),
    ("get_challenges",           {}),
    ("is_enrolled",              {"challenge_id":"w1","account_id":"0xgoku.testnet"}),
    ("is_qualifier",             {"challenge_id":"w1","account_id":"0xgoku.testnet"}),
    ("has_withdrawn_stake",      {"challenge_id":"w1","account_id":"0xgoku.testnet"}),
    ("has_claimed_reward",       {"challenge_id":"w1","account_id":"0xgoku.testnet"}),
    ("storage_balance_of",       {"account_id":"0xgoku.testnet"}),
]

for method, args in views:
    val, err = call_view(CONTRACT, method, args)
    if err:
        print(f"  {method:38s} ERROR: {err}")
    else:
        # truncate long values
        s = json.dumps(val)
        print(f"  {method:38s} = {s[:80]}")

print()
print("=" * 60)
print("DAO VIEW METHODS")
print("=" * 60)

dao_views = [
    ("get_policy",    {}),
    ("get_proposals", {"from_index": 0, "limit": 10}),
    ("get_last_proposal_id", {}),
]
for method, args in dao_views:
    val, err = call_view(DAO, method, args)
    if err:
        print(f"  {method:38s} ERROR: {err}")
    else:
        s = json.dumps(val, indent=2)
        print(f"  {method}:")
        print(s[:800])
        print()
