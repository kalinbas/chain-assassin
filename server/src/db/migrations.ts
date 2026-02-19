import Database from "better-sqlite3";
import { SCHEMA_VERSION, CREATE_TABLES } from "./schema.js";
import { createLogger } from "../utils/logger.js";

const log = createLogger("migrations");

/**
 * Run database migrations. Creates all tables if they don't exist.
 * For future schema changes, add migration functions keyed by version.
 */
export function runMigrations(db: Database.Database): void {
  // Enable WAL mode for better concurrent read/write performance
  db.pragma("journal_mode = WAL");
  db.pragma("foreign_keys = ON");

  // Check current schema version
  const hasVersionTable = db
    .prepare(
      "SELECT name FROM sqlite_master WHERE type='table' AND name='schema_version'"
    )
    .get();

  let currentVersion = 0;

  if (hasVersionTable) {
    const row = db
      .prepare("SELECT version FROM schema_version ORDER BY version DESC LIMIT 1")
      .get() as { version: number } | undefined;
    currentVersion = row?.version ?? 0;
  }

  if (currentVersion >= SCHEMA_VERSION) {
    log.info({ currentVersion }, "Database schema is up to date");
    return;
  }

  log.info(
    { currentVersion, targetVersion: SCHEMA_VERSION },
    "Running database migrations"
  );

  // Run initial schema creation
  if (currentVersion === 0) {
    db.exec(CREATE_TABLES);
    db.prepare("INSERT INTO schema_version (version) VALUES (?)").run(
      SCHEMA_VERSION
    );
    log.info({ version: SCHEMA_VERSION }, "Database schema created");
  }

  // Migrations
  if (currentVersion < 2 && currentVersion > 0) {
    db.exec(`ALTER TABLE games ADD COLUMN meeting_lat INTEGER NOT NULL DEFAULT 0`);
    db.exec(`ALTER TABLE games ADD COLUMN meeting_lng INTEGER NOT NULL DEFAULT 0`);
    db.prepare("UPDATE schema_version SET version = ?").run(2);
    log.info("Migrated to v2: added meeting_lat/meeting_lng");
  }

  if (currentVersion < 3 && currentVersion > 0) {
    db.exec(`ALTER TABLE players ADD COLUMN last_heartbeat_at INTEGER`);
    db.exec(`
      CREATE TABLE IF NOT EXISTS heartbeat_scans (
        id              INTEGER PRIMARY KEY AUTOINCREMENT,
        game_id         INTEGER NOT NULL,
        scanner_address TEXT NOT NULL,
        scanned_address TEXT NOT NULL,
        timestamp       INTEGER NOT NULL,
        scanner_lat     REAL,
        scanner_lng     REAL,
        distance_meters REAL
      )
    `);
    db.exec(`CREATE INDEX IF NOT EXISTS idx_heartbeat_game ON heartbeat_scans(game_id)`);
    db.prepare("UPDATE schema_version SET version = ?").run(3);
    log.info("Migrated to v3: added heartbeat_scans table + last_heartbeat_at column");
  }

  if (currentVersion < 4 && currentVersion > 0) {
    db.exec(`
      CREATE TABLE IF NOT EXISTS game_photos (
        id        INTEGER PRIMARY KEY AUTOINCREMENT,
        game_id   INTEGER NOT NULL,
        address   TEXT NOT NULL,
        filename  TEXT NOT NULL,
        caption   TEXT,
        timestamp INTEGER NOT NULL
      )
    `);
    db.exec(`CREATE INDEX IF NOT EXISTS idx_photos_game ON game_photos(game_id)`);
    db.prepare("UPDATE schema_version SET version = ?").run(4);
    log.info("Migrated to v4: added game_photos table");
  }

  if (currentVersion < 5 && currentVersion > 0) {
    db.exec(`ALTER TABLE games ADD COLUMN sub_phase TEXT`);
    db.prepare("UPDATE schema_version SET version = ?").run(5);
    log.info("Migrated to v5: added sub_phase column to games");
  }

  if (currentVersion < 6 && currentVersion > 0) {
    db.exec(`ALTER TABLE players ADD COLUMN bluetooth_id TEXT`);
    db.prepare("UPDATE schema_version SET version = ?").run(6);
    log.info("Migrated to v6: added bluetooth_id column to players");
  }

  if (currentVersion < 8 && currentVersion > 0) {
    db.exec(`ALTER TABLE games ADD COLUMN base_reward TEXT NOT NULL DEFAULT '0'`);
    db.prepare("UPDATE schema_version SET version = ?").run(8);
    log.info("Migrated to v8: added base_reward column to games");
  }

  if (currentVersion < 9 && currentVersion > 0) {
    db.exec(`ALTER TABLE games ADD COLUMN total_collected TEXT NOT NULL DEFAULT '0'`);
    db.exec(`ALTER TABLE games ADD COLUMN player_count INTEGER NOT NULL DEFAULT 0`);
    db.exec(`ALTER TABLE games ADD COLUMN max_duration INTEGER NOT NULL DEFAULT 0`);
    db.exec(`ALTER TABLE games RENAME COLUMN bps_platform TO bps_creator`);
    db.exec(`ALTER TABLE players ADD COLUMN has_claimed INTEGER NOT NULL DEFAULT 0`);
    db.prepare("UPDATE schema_version SET version = ?").run(9);
    log.info("Migrated to v9: added total_collected, player_count, max_duration, has_claimed; renamed bps_platform to bps_creator");
  }

  if (currentVersion < 10 && currentVersion > 0) {
    db.exec(`ALTER TABLE games ADD COLUMN sub_phase_started_at INTEGER`);
    db.prepare("UPDATE schema_version SET version = ?").run(10);
    log.info("Migrated to v10: added sub_phase_started_at column to games");
  }

  if (currentVersion < 11 && currentVersion > 0) {
    db.exec(`ALTER TABLE players ADD COLUMN last_location_at INTEGER`);
    db.exec(`ALTER TABLE players ADD COLUMN last_ble_seen_at INTEGER`);
    db.exec(`ALTER TABLE players ADD COLUMN last_network_seen_at INTEGER`);
    db.prepare("UPDATE schema_version SET version = ?").run(11);
    log.info("Migrated to v11: added compliance timestamp columns to players");
  }
}
