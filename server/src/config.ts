import dotenv from "dotenv";

// Load .env file — supports DOTENV_CONFIG_PATH override
dotenv.config({ path: process.env.DOTENV_CONFIG_PATH || ".env" });

import { createLogger } from "./utils/logger.js";

const log = createLogger("config");

function requireEnv(name: string): string {
  const value = process.env[name];
  if (!value) {
    log.fatal(`Missing required environment variable: ${name}`);
    process.exit(1);
  }
  return value;
}

function optionalEnv(name: string, defaultValue: string): string {
  return process.env[name] || defaultValue;
}

function envInt(name: string, defaultValue: number): number {
  const raw = process.env[name];
  if (!raw) return defaultValue;
  const parsed = parseInt(raw, 10);
  if (isNaN(parsed)) {
    log.fatal(`Invalid integer for ${name}: ${raw}`);
    process.exit(1);
  }
  return parsed;
}

function envBool(name: string, defaultValue: boolean): boolean {
  const raw = process.env[name];
  if (!raw) return defaultValue;
  return raw.toLowerCase() === "true" || raw === "1";
}

export const config = {
  // Blockchain
  rpcUrl: requireEnv("RPC_URL"),
  rpcWsUrl: requireEnv("RPC_WS_URL"),
  contractAddress: requireEnv("CONTRACT_ADDRESS"),
  operatorPrivateKey: requireEnv("OPERATOR_PRIVATE_KEY"),
  chainId: envInt("CHAIN_ID", 84532), // Base Sepolia

  // Server
  port: envInt("PORT", 3000),
  host: optionalEnv("HOST", "0.0.0.0"),

  // Database
  dbPath: optionalEnv("DB_PATH", "./data/chain-assassin.db"),

  // Game settings
  killProximityMeters: envInt("KILL_PROXIMITY_METERS", 100),
  zoneGraceSeconds: envInt("ZONE_GRACE_SECONDS", 60),
  gpsPingIntervalSeconds: envInt("GPS_PING_INTERVAL_SECONDS", 5),
  bleRequired: envBool("BLE_REQUIRED", true),

  // Heartbeat (anti-QR-hiding fairplay)
  heartbeatIntervalSeconds: envInt("HEARTBEAT_INTERVAL_SECONDS", 600),  // 10 minutes
  heartbeatProximityMeters: envInt("HEARTBEAT_PROXIMITY_METERS", 100),
  heartbeatDisableThreshold: envInt("HEARTBEAT_DISABLE_THRESHOLD", 4),  // disable when ≤ this many alive

  // Pregame
  pregameDurationSeconds: envInt("PREGAME_DURATION_SECONDS", 180),  // 3 minutes

  // Photos
  photosDir: optionalEnv("PHOTOS_DIR", "./data/photos"),
  maxPhotoSizeMb: envInt("MAX_PHOTO_SIZE_MB", 5),

  // Logging
  logLevel: optionalEnv("LOG_LEVEL", "info"),
} as const;

export type Config = typeof config;
