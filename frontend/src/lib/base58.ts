const ALPHABET = '123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz';
const INDEX = new Map([...ALPHABET].map((char, i) => [char, BigInt(i)]));

/** Number of bytes a base58 string decodes to, or null if it is not valid base58. */
export function base58ByteLength(value: string): number | null {
  if (value.length === 0) return null;
  let number = 0n;
  for (const char of value) {
    const digit = INDEX.get(char);
    if (digit === undefined) return null;
    number = number * 58n + digit;
  }
  let bytes = 0;
  while (number > 0n) {
    number >>= 8n;
    bytes += 1;
  }
  let leadingZeros = 0;
  while (leadingZeros < value.length && value[leadingZeros] === '1') leadingZeros += 1;
  return bytes + leadingZeros;
}

/** True when the value is a base58 string that decodes to a 32-byte Solana address. */
export function isSolanaAddress(value: string): boolean {
  const trimmed = value.trim();
  return trimmed.length >= 32 && trimmed.length <= 44 && base58ByteLength(trimmed) === 32;
}
