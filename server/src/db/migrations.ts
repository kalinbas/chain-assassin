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

  // Future migrations go here:
  // if (currentVersion < 2) { migrate_v1_to_v2(db); }
  // if (currentVersion < 3) { migrate_v2_to_v3(db); }
}
