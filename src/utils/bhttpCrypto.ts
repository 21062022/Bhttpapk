/**
 * BHTTP V2 Cryptographic and Protocol Utility
 * Matches the Kotlin BhttpBridge and Python bhttp-server.py implementation:
 * - Header size: 29 bytes
 * - Keystream: SHA-256(sess[16] + mode[1] + seq[8] + d[1] + counter[4])
 * - XOR masking of payload
 */

export function generateSession(): Uint8Array {
  const sess = new Uint8Array(16);
  crypto.getRandomValues(sess);
  return sess;
}

export function bytesToHex(bytes: Uint8Array): string {
  return Array.from(bytes)
    .map(b => b.toString(16).padStart(2, '0'))
    .join('');
}

export function hexToBytes(hex: string): Uint8Array {
  const cleaned = hex.replace(/[^0-9a-fA-F]/g, '');
  const bytes = new Uint8Array(Math.floor(cleaned.length / 2));
  for (let i = 0; i < bytes.length; i++) {
    bytes[i] = parseInt(cleaned.substring(i * 2, i * 2 + 2), 16);
  }
  return bytes;
}

export function formatHexDump(bytes: Uint8Array, maxBytes = 128): string {
  const slice = bytes.slice(0, maxBytes);
  let dump = '';
  for (let i = 0; i < slice.length; i += 16) {
    const chunk = slice.slice(i, i + 16);
    const hex = Array.from(chunk)
      .map(b => b.toString(16).padStart(2, '0').toUpperCase())
      .join(' ')
      .padEnd(48, ' ');
    const ascii = Array.from(chunk)
      .map(b => (b >= 32 && b <= 126 ? String.fromCharCode(b) : '.'))
      .join('');
    const offset = i.toString(16).padStart(4, '0').toUpperCase();
    dump += `${offset}  ${hex}  |${ascii}|\n`;
  }
  if (bytes.length > maxBytes) {
    dump += `... (+${bytes.length - maxBytes} bytes más)\n`;
  }
  return dump;
}

/**
 * Creates 29-byte BHTTP header:
 * [mode: 1B] [sess: 16B] [seq: 8B BigEndian] [len: 4B BigEndian]
 */
export function createHeader(mode: number, sess: Uint8Array, seq: bigint | number, len: number): Uint8Array {
  const buf = new Uint8Array(29);
  const view = new DataView(buf.buffer);

  // 1 byte mode
  buf[0] = mode & 0xff;

  // 16 bytes session
  buf.set(sess.slice(0, 16), 1);

  // 8 bytes seq BigEndian
  const bigSeq = BigInt(seq);
  view.setBigUint64(17, bigSeq, false); // false for big-endian

  // 4 bytes length BigEndian
  view.setUint32(25, len, false);

  return buf;
}

/**
 * Parses 29-byte BHTTP header
 */
export function parseHeader(header: Uint8Array): {
  mode: number;
  sess: Uint8Array;
  sessHex: string;
  seq: bigint;
  len: number;
} | null {
  if (header.length < 29) return null;
  const view = new DataView(header.buffer, header.byteOffset, header.byteLength);

  const mode = header[0];
  const sess = header.slice(1, 17);
  const sessHex = bytesToHex(sess);
  const seq = view.getBigUint64(17, false);
  const len = view.getUint32(25, false);

  return { mode, sess, sessHex, seq, len };
}

/**
 * Generates BHTTP V2 keystream of length n using SHA-256
 * base = sess (16B) + mode (1B) + seq (8B BigEndian) + d (1B)
 * loop c = 0, 1, 2... digest = SHA-256(base + c[4B BigEndian])
 */
export async function computeKeystream(
  sess: Uint8Array,
  mode: number,
  seq: bigint | number,
  d: number,
  n: number
): Promise<Uint8Array> {
  // prefix = 26 bytes
  const prefix = new Uint8Array(26);
  const prefixView = new DataView(prefix.buffer);

  prefix.set(sess.slice(0, 16), 0);
  prefix[16] = mode & 0xff;
  prefixView.setBigUint64(17, BigInt(seq), false);
  prefix[25] = d & 0xff;

  const out = new Uint8Array(n);
  let off = 0;
  let counter = 0;

  const counterBuf = new Uint8Array(4);
  const counterView = new DataView(counterBuf.buffer);

  while (off < n) {
    counterView.setUint32(0, counter, false);

    // concatenate prefix + counter
    const hashInput = new Uint8Array(30);
    hashInput.set(prefix, 0);
    hashInput.set(counterBuf, 26);

    const digestBuffer = await crypto.subtle.digest('SHA-256', hashInput);
    const digest = new Uint8Array(digestBuffer);

    const k = Math.min(32, n - off);
    out.set(digest.subarray(0, k), off);
    off += k;
    counter++;
  }

  return out;
}

/**
 * Masks data with BHTTP V2 keystream (XOR)
 */
export async function maskData(
  data: Uint8Array,
  sess: Uint8Array,
  mode: number,
  seq: bigint | number,
  d: number
): Promise<{ masked: Uint8Array; keystream: Uint8Array }> {
  const keystream = await computeKeystream(sess, mode, seq, d, data.length);
  const masked = new Uint8Array(data.length);
  for (let i = 0; i < data.length; i++) {
    masked[i] = data[i] ^ keystream[i];
  }
  return { masked, keystream };
}

/**
 * Builds a complete BHTTP frame (29-byte header + masked payload)
 */
export async function buildFrame(
  mode: number,
  sess: Uint8Array,
  seq: bigint | number,
  payload: Uint8Array,
  d: number
): Promise<{
  header: Uint8Array;
  maskedPayload: Uint8Array;
  keystream: Uint8Array;
  fullFrame: Uint8Array;
}> {
  const header = createHeader(mode, sess, seq, payload.length);
  const { masked, keystream } = await maskData(payload, sess, mode, seq, d);

  const fullFrame = new Uint8Array(header.length + masked.length);
  fullFrame.set(header, 0);
  fullFrame.set(masked, header.length);

  return {
    header,
    maskedPayload: masked,
    keystream,
    fullFrame
  };
}
