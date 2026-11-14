import subprocess

cmd = [
    "/home/stone/.cargo/bin/near", "contract", "call-function", "as-transaction",
    "pact-demo.testnet", "enroll",
    "json-args", '{"challenge_id":"demo-14"}',
    "prepaid-gas", "50.0 Tgas",
    "attached-deposit", "0.5 NEAR",
    "sign-as", "pact-t4-test.testnet",
    "network-config", "testnet",
    "sign-with-legacy-keychain",
    "send"
]
print("Running command...")
res = subprocess.run(cmd, capture_output=True, text=True, timeout=25)
print("STDOUT:", res.stdout)
print("STDERR:", res.stderr)
