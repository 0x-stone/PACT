"""Shared encodings used across the pact's Python consumers.

Only Bitcoin-alphabet base58 lives here: NEAR represents Ed25519 public keys
as ``"ed25519:<base58>"`` (NEP-413 ``SignedMessage.publicKey``), and the
settlement attestation key endpoint uses the same format. Pure stdlib, no
dependencies, so every consumer (backend, scripts, tests) can share one
implementation instead of drifting copies.
"""

from __future__ import annotations

BASE58_ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
_BASE58_INDEX = {char: index for index, char in enumerate(BASE58_ALPHABET)}


def b58encode(data: bytes) -> str:
    """Encode bytes with the Bitcoin base58 alphabet (NEAR public keys)."""
    padding = 0
    for byte in data:
        if byte == 0:
            padding += 1
        else:
            break
    number = int.from_bytes(data, "big")
    encoded: list[str] = []
    while number:
        number, remainder = divmod(number, 58)
        encoded.append(BASE58_ALPHABET[remainder])
    return BASE58_ALPHABET[0] * padding + "".join(reversed(encoded))


def b58decode(text: str) -> bytes:
    """Decode a base58 string; raises ValueError on any non-alphabet char."""
    if not isinstance(text, str):
        raise ValueError("base58 input must be a string")
    padding = 0
    for char in text:
        if char == BASE58_ALPHABET[0]:
            padding += 1
        else:
            break
    number = 0
    for char in text:
        try:
            digit = _BASE58_INDEX[char]
        except KeyError:
            raise ValueError(f"invalid base58 character {char!r}") from None
        number = number * 58 + digit
    body = number.to_bytes((number.bit_length() + 7) // 8, "big") if number else b""
    return b"\x00" * padding + body
