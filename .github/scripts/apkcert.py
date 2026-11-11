#!/usr/bin/env python3
"""Prints the SHA-256 of the signing certificate(s) in an APK's v2/v3 signing block, plus
package-level facts. Standard library only (no JDK needed). Usage: apkcert.py a.apk [b.apk ...]"""
import hashlib, struct, sys, zipfile

def u32(b, o): return struct.unpack_from("<I", b, o)[0]
def u64(b, o): return struct.unpack_from("<Q", b, o)[0]

def certs(path):
    data = open(path, "rb").read()
    eocd = data.rfind(b"PK\x05\x06")
    cd_off = u32(data, eocd + 16)
    if data[cd_off - 16:cd_off] != b"APK Sig Block 42":
        return {"error": "no v2+ signing block"}
    size = u64(data, cd_off - 24)
    start = cd_off - size - 8
    pairs = data[start + 8:cd_off - 24]
    out = {}
    o = 0
    while o < len(pairs):
        ln = u64(pairs, o); pid = u32(pairs, o + 8); val = pairs[o + 12:o + 8 + ln]; o += 8 + ln
        name = {0x7109871a: "v2", 0xf05368c0: "v3", 0x1b93ad61: "v3.1"}.get(pid)
        if not name: continue
        # signers(len-prefixed seq) -> signer -> signed data -> digests, certificates
        p = 4  # signers sequence length
        signer_len = u32(val, p); signer = val[p + 4:p + 4 + signer_len]
        sd_len = u32(signer, 0); sd = signer[4:4 + sd_len]
        dig_len = u32(sd, 0); q = 4 + dig_len
        certs_len = u32(sd, q); cs = sd[q + 4:q + 4 + certs_len]
        c_len = u32(cs, 0); cert = cs[4:4 + c_len]
        out[name] = hashlib.sha256(cert).hexdigest()
    return out

if len(sys.argv) > 2 and sys.argv[1] == "--digest":
    # Machine-readable: just the signing certificate SHA-256 (v3 if present, else v2).
    c = certs(sys.argv[2])
    print(c.get("v3") or c.get("v2") or "")
    sys.exit(0)

for p in sys.argv[1:]:
    print(p)
    print("  certs:", certs(p))
    with zipfile.ZipFile(p) as z:
        names = z.namelist()
        print("  native ABIs:", sorted({n.split("/")[1] for n in names if n.startswith("lib/")}))
        print("  has META-INF v1 sig:", any(n.startswith("META-INF/") and n.endswith((".RSA", ".EC", ".DSA")) for n in names))
