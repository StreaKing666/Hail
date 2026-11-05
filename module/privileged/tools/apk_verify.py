"""APK Signature Scheme v2 verification for the upstream Hail APK.

No reliance on unverified META-INF certificate records. Checks the v2 signer
signature, public key, expected signing cert and every authenticated content
chunk (including patched ZIP EOCD). Rejects unsupported signature formats.
For platform-wide verification, use Google's official ``apksigner verify`` too.
"""
from __future__ import annotations

import hashlib
import struct
from pathlib import Path

from cryptography import x509
from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec, padding, rsa

APK_SIG_MAGIC = b'APK Sig Block 42'
APK_V2_ID = 0x7109871A
CHUNK_SIZE = 1024 * 1024
EXPECTED_CERT_SHA256 = 'ae3ca1228d75917a561aa98efa4b5959af77095e1d089d26673b5567afa648cb'


def _u32(blob: bytes | memoryview, pos: int) -> int:
    if pos < 0 or pos + 4 > len(blob):
        raise ValueError('Truncated length or integer in signing data')
    return struct.unpack_from('<I', blob, pos)[0]


def _u64(blob: bytes | memoryview, pos: int) -> int:
    if pos < 0 or pos + 8 > len(blob):
        raise ValueError('Truncated 64-bit field in signing data')
    return struct.unpack_from('<Q', blob, pos)[0]


def _lp(blob: bytes | memoryview, pos: int) -> tuple[memoryview, int]:
    size = _u32(blob, pos)
    if size > len(blob) - pos - 4:
        raise ValueError('Invalid length-prefixed APK signing field')
    return memoryview(blob)[pos + 4: pos + 4 + size], pos + 4 + size


def _items(blob: bytes | memoryview):
    offset = 0
    while offset < len(blob):
        item, offset = _lp(blob, offset)
        yield item
    if offset != len(blob):
        raise ValueError('Malformed APK signing items')


def _sig_block(apk: bytes) -> tuple[memoryview, int, int, int]:
    # Android ZIP archives must have an EOCD at the actual end of the file.
    search_start = max(0, len(apk) - (65535 + 22))
    eocd = -1
    for pos in range(len(apk) - 22, search_start - 1, -1):
        if apk[pos:pos + 4] == b'PK\x05\x06' and pos + 22 + struct.unpack_from('<H', apk, pos + 20)[0] == len(apk):
            eocd = pos
            break
    if eocd < 0:
        raise ValueError('ZIP EOCD not found at end of APK')
    if _u32(apk, eocd + 12) == 0xffffffff or _u32(apk, eocd + 16) == 0xffffffff:
        raise ValueError('ZIP64 APK not supported')
    if struct.unpack_from('<H', apk, eocd + 4)[0] or struct.unpack_from('<H', apk, eocd + 6)[0]:
        raise ValueError('Multi-disk ZIP not supported')
    cd = _u32(apk, eocd + 16)
    if cd < 32 or cd >= eocd or cd + _u32(apk, eocd + 12) != eocd:
        raise ValueError('Invalid APK central directory offset or length')
    if apk[cd - 16:cd] != APK_SIG_MAGIC:
        raise ValueError('No APK Signing Block; only v2+ signed APKs are accepted')
    size = _u64(apk, cd - 24)
    if size < 24 or size > cd - 8:
        raise ValueError('Invalid APK signing block size')
    start = cd - size - 8
    if _u64(apk, start) != size:
        raise ValueError('APK signing block header/footer size mismatch')
    offset = start + 8
    v2 = None
    while offset < cd - 24:
        length = _u64(apk, offset)
        if length < 4 or length > cd - 24 - offset - 8:
            raise ValueError('Invalid signing block entry length')
        block_id = _u32(apk, offset + 8)
        if block_id == APK_V2_ID:
            if v2 is not None:
                raise ValueError('Duplicate APK v2 signature block')
            v2 = memoryview(apk)[offset + 12:offset + 8 + length]
        offset += 8 + length
    if offset != cd - 24 or v2 is None:
        raise ValueError('APK Signing Block missing valid v2 signature')
    return v2, start, cd, eocd


def _hash_contents(apk: bytes, start: int, cd: int, eocd: int, algo: str) -> bytes:
    # Android Signature Scheme v2: ZIP entry area, central directory, and
    # EOCD with its CD offset replaced with start of APK Signing Block.
    corrected_eocd = bytearray(apk[eocd:])
    struct.pack_into('<I', corrected_eocd, 16, start)
    chunks: list[bytes] = []
    for segment in (memoryview(apk)[:start], memoryview(apk)[cd:eocd], memoryview(corrected_eocd)):
        for pos in range(0, len(segment), CHUNK_SIZE):
            chunk = segment[pos:pos + CHUNK_SIZE]
            h = hashlib.new(algo)
            h.update(b'\xa5' + struct.pack('<I', len(chunk)))
            h.update(chunk)
            chunks.append(h.digest())
    final = hashlib.new(algo)
    final.update(b'\x5a' + struct.pack('<I', len(chunks)))
    for value in chunks:
        final.update(value)
    return final.digest()


# sig ID -> (digest name, cryptography hash, padding type)
SUPPORTED = {
    0x0101: ('sha256', hashes.SHA256, 'pss'),
    0x0102: ('sha512', hashes.SHA512, 'pss'),
    0x0103: ('sha256', hashes.SHA256, 'pkcs1'),
    0x0104: ('sha512', hashes.SHA512, 'pkcs1'),
    0x0201: ('sha256', hashes.SHA256, 'ecdsa'),
    0x0202: ('sha512', hashes.SHA512, 'ecdsa'),
}


def verify_apk_v2(apk_path: Path | str, expected_cert: str = EXPECTED_CERT_SHA256) -> str:
    """Verify APK v2 cryptographic signature, contents and pinned signing key."""
    apk = Path(apk_path).read_bytes()
    sig_block, start, cd, eocd = _sig_block(apk)
    signers, pos = _lp(sig_block, 0)
    if pos != len(sig_block):
        raise ValueError('Unexpected trailing data in APK v2 block')
    signer_list = list(_items(signers))
    if len(signer_list) != 1:
        raise ValueError('Expected exactly one APK signer')
    signer = signer_list[0]
    signed, pos = _lp(signer, 0)
    signatures, pos = _lp(signer, pos)
    public_key_blob, pos = _lp(signer, pos)
    if pos != len(signer):
        raise ValueError('Unexpected trailing signer data')

    digests, pos = _lp(signed, 0)
    certificates, pos = _lp(signed, pos)
    attrs, pos = _lp(signed, pos)
    if bytes(signed[pos:]) not in (b'', b'\x00\x00\x00\x00'):
        raise ValueError('Unexpected signed-data remainder')
    # Additional signed attributes are authenticated within signed_data.
    _ = attrs
    cert_list = list(_items(certificates))
    if not cert_list:
        raise ValueError('Missing signer X.509 certificate')
    cert = x509.load_der_x509_certificate(bytes(cert_list[0]))
    fingerprint = cert.fingerprint(hashes.SHA256()).hex()
    if fingerprint.lower() != expected_cert.lower():
        raise ValueError('Hail signer certificate mismatch')
    public_key = cert.public_key()
    if public_key.public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo) != bytes(public_key_blob):
        raise ValueError('APK public key does not match signing certificate')

    sig_map: dict[int, bytes] = {}
    for record in _items(signatures):
        alg = _u32(record, 0)
        sig, end = _lp(record, 4)
        if end != len(record) or alg in sig_map:
            raise ValueError('Malformed or duplicate APK signature algorithm')
        sig_map[alg] = bytes(sig)
    digest_map: dict[int, bytes] = {}
    for record in _items(digests):
        alg = _u32(record, 0)
        digest, end = _lp(record, 4)
        if end != len(record) or alg in digest_map:
            raise ValueError('Malformed or duplicate APK digest')
        digest_map[alg] = bytes(digest)
    if not sig_map or sig_map.keys() != digest_map.keys() or set(sig_map) - set(SUPPORTED):
        raise ValueError('Missing, inconsistent or unsupported APK signature algorithms')

    computed = {}
    for alg, signature in sig_map.items():
        name, hash_cls, mode = SUPPORTED[alg]
        if name not in computed:
            computed[name] = _hash_contents(apk, start, cd, eocd, name)
        if computed[name] != digest_map[alg]:
            raise ValueError('APK v2 content digest mismatch (APK may be modified)')
        digest = hash_cls()
        try:
            if mode in ('pkcs1', 'pss'):
                if not isinstance(public_key, rsa.RSAPublicKey):
                    raise ValueError('Signature algorithm does not match RSA key')
                pad = padding.PKCS1v15() if mode == 'pkcs1' else padding.PSS(
                    mgf=padding.MGF1(digest), salt_length=digest.digest_size)
                public_key.verify(signature, bytes(signed), pad, digest)
            elif mode == 'ecdsa':
                if not isinstance(public_key, ec.EllipticCurvePublicKey):
                    raise ValueError('Signature algorithm does not match EC key')
                public_key.verify(signature, bytes(signed), ec.ECDSA(digest))
        except InvalidSignature as exc:
            raise ValueError('APK v2 cryptographic signature verification failed') from exc
    return fingerprint
