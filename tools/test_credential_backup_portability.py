"""Independent compatibility test for SYNTHETIC instrumented fixtures only.

Never use this script with an account backup: its password and expected values are
public test data. No decrypted payload is written or printed.
Usage: python tools/test_credential_backup_portability.py INPUT.json OUTPUT.json
Requires Python cryptography, not an Android installation or Keystore.
"""
import base64
import hashlib
import json
import os
import sys
from pathlib import Path
from cryptography.hazmat.primitives.ciphers.aead import AESGCM


def canonical(header):
    return json.dumps(header, ensure_ascii=True, separators=(",", ":")).encode("ascii")


def main(source, destination):
    raw = Path(source).read_bytes()
    assert 0 < len(raw) <= 512 * 1024
    envelope = json.loads(raw)
    header = {key: envelope[key] for key in ("format", "version", "kdf", "cipher")}
    assert header["format"] == "DerpiViewerEncryptedBackup" and header["version"] == 1
    assert header["kdf"]["algorithm"] == "PBKDF2WithHmacSHA256"
    assert 600_000 <= header["kdf"]["iterations"] <= 1_200_000
    assert header["cipher"]["algorithm"] == "AES-256-GCM" and header["cipher"]["tagLength"] == 128
    password = b"River9!Mountains#Cloud2026"
    key = hashlib.pbkdf2_hmac("sha256", password, base64.b64decode(header["kdf"]["salt"]),
                              header["kdf"]["iterations"], dklen=32)
    plain = AESGCM(key).decrypt(base64.b64decode(header["cipher"]["nonce"]),
                                base64.b64decode(envelope["ciphertext"]), canonical(header))
    payload = json.loads(plain)
    assert payload["origin"] == "https://backup-test.example/"
    assert payload["apiKey"] == "synthetic-portable-key"
    assert payload["cookies"][0]["value"] == "synthetic-portable"
    header["kdf"]["salt"] = base64.b64encode(os.urandom(16)).decode("ascii")
    header["cipher"]["nonce"] = base64.b64encode(os.urandom(12)).decode("ascii")
    key = hashlib.pbkdf2_hmac("sha256", password, base64.b64decode(header["kdf"]["salt"]), 600_000, dklen=32)
    ciphertext = AESGCM(key).encrypt(base64.b64decode(header["cipher"]["nonce"]), plain, canonical(header))
    result = dict(header, ciphertext=base64.b64encode(ciphertext).decode("ascii"))
    Path(destination).write_bytes(canonical(result))
    print("PASS: Android backup decrypted independently; fresh portable encrypted fixture written.")


if __name__ == "__main__":
    main(*sys.argv[1:])
