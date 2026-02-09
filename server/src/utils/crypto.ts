import { ethers } from "ethers";

/**
 * Verify an EIP-191 personal_sign signature.
 * Returns the recovered signer address (checksummed).
 */
export function recoverSigner(message: string, signature: string): string {
  return ethers.verifyMessage(message, signature);
}

/**
 * Verify that a signature was signed by the expected address.
 * Comparison is case-insensitive (checksum-safe).
 */
export function verifySignature(
  message: string,
  signature: string,
  expectedAddress: string
): boolean {
  try {
    const recovered = recoverSigner(message, signature);
    return recovered.toLowerCase() === expectedAddress.toLowerCase();
  } catch {
    return false;
  }
}

/**
 * Validate auth message format and timestamp freshness.
 * Expected format: "chain-assassin:{gameId}:{timestamp}"
 * Timestamp must be within `maxAgeSeconds` of now.
 */
export function validateAuthMessage(
  message: string,
  expectedGameId: number,
  maxAgeSeconds: number = 300 // 5 minutes
): { valid: boolean; error?: string } {
  const parts = message.split(":");
  if (parts.length !== 3 || parts[0] !== "chain-assassin") {
    return { valid: false, error: "Invalid message format" };
  }

  const gameId = parseInt(parts[1], 10);
  if (isNaN(gameId) || gameId !== expectedGameId) {
    return { valid: false, error: "Game ID mismatch" };
  }

  const timestamp = parseInt(parts[2], 10);
  if (isNaN(timestamp)) {
    return { valid: false, error: "Invalid timestamp" };
  }

  const now = Math.floor(Date.now() / 1000);
  if (Math.abs(now - timestamp) > maxAgeSeconds) {
    return { valid: false, error: "Message expired" };
  }

  return { valid: true };
}

/**
 * Validate REST API auth headers.
 * Expected message format: "chain-assassin:{timestamp}"
 */
export function validateApiAuth(
  address: string,
  signature: string,
  message: string,
  maxAgeSeconds: number = 300
): { valid: boolean; error?: string } {
  // Validate message format
  const parts = message.split(":");
  if (parts.length !== 2 || parts[0] !== "chain-assassin") {
    return { valid: false, error: "Invalid message format" };
  }

  const timestamp = parseInt(parts[1], 10);
  if (isNaN(timestamp)) {
    return { valid: false, error: "Invalid timestamp" };
  }

  const now = Math.floor(Date.now() / 1000);
  if (Math.abs(now - timestamp) > maxAgeSeconds) {
    return { valid: false, error: "Message expired" };
  }

  // Verify signature
  if (!verifySignature(message, signature, address)) {
    return { valid: false, error: "Invalid signature" };
  }

  return { valid: true };
}

/**
 * QR encoding constants.
 *
 * Payload = gameId * 10000 + playerNumber, then obfuscated with a
 * multiplicative cipher mod a Mersenne prime (2^31 − 1).
 * This produces a random-looking 9-10 digit number that stays in
 * QR numeric mode (~3.3 bits/char) and is trivially reversible.
 *
 * Max 9,999 players per game, max gameId 214,747.
 */
const QR_MULTIPLIER = 10000;
const QR_PRIME = 2147483647n;      // 2^31 - 1
const QR_SCRAMBLE = 1588635695n;   // multiplier (coprime to P)
const QR_UNSCRAMBLE = 1799631288n; // modular inverse of QR_SCRAMBLE mod P

/**
 * Encode a kill QR payload as an obfuscated pure numeric string.
 */
export function encodeKillQrPayload(gameId: number, playerNumber: number): string {
  const n = BigInt(gameId * QR_MULTIPLIER + playerNumber);
  const scrambled = (n * QR_SCRAMBLE) % QR_PRIME;
  return String(scrambled);
}

/**
 * Parse an obfuscated QR kill payload back to gameId + playerNumber.
 */
export function parseKillQrPayload(
  payload: string
): { gameId: number; playerNumber: number } | null {
  const raw = parseInt(payload, 10);
  if (isNaN(raw) || raw < 1 || String(raw) !== payload) {
    return null;
  }

  const original = Number((BigInt(raw) * QR_UNSCRAMBLE) % QR_PRIME);
  const playerNumber = original % QR_MULTIPLIER;
  const gameId = Math.floor(original / QR_MULTIPLIER);

  if (gameId < 1 || playerNumber < 1) {
    return null;
  }

  return { gameId, playerNumber };
}
