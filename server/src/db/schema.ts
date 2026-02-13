export const SCHEMA_VERSION = 10;

export const CREATE_TABLES = `
-- Schema version tracking
CREATE TABLE IF NOT EXISTS schema_version (
  version INTEGER NOT NULL
);

-- Games tracked from blockchain events
CREATE TABLE IF NOT EXISTS games (
  game_id               INTEGER PRIMARY KEY,
  title                 TEXT NOT NULL,
  entry_fee             TEXT NOT NULL,
  min_players           INTEGER NOT NULL,
  max_players           INTEGER NOT NULL,
  registration_deadline INTEGER NOT NULL,
  game_date             INTEGER NOT NULL,
  expiry_deadline       INTEGER NOT NULL,
  created_at            INTEGER NOT NULL,
  creator               TEXT NOT NULL,
  center_lat            INTEGER NOT NULL,
  center_lng            INTEGER NOT NULL,
  meeting_lat           INTEGER NOT NULL DEFAULT 0,
  meeting_lng           INTEGER NOT NULL DEFAULT 0,
  bps_1st               INTEGER NOT NULL,
  bps_2nd               INTEGER NOT NULL,
  bps_3rd               INTEGER NOT NULL,
  bps_kills             INTEGER NOT NULL,
  bps_creator           INTEGER NOT NULL,
  base_reward           TEXT NOT NULL DEFAULT '0',
  total_collected       TEXT NOT NULL DEFAULT '0',
  player_count          INTEGER NOT NULL DEFAULT 0,
  max_duration          INTEGER NOT NULL DEFAULT 0,
  phase                 INTEGER NOT NULL DEFAULT 0,
  sub_phase             TEXT,
  sub_phase_started_at  INTEGER,
  started_at            INTEGER,
  ended_at              INTEGER,
  winner1               TEXT,
  winner2               TEXT,
  winner3               TEXT,
  top_killer            TEXT
);

-- Zone shrink schedule per game
CREATE TABLE IF NOT EXISTS zone_shrinks (
  game_id       INTEGER NOT NULL REFERENCES games(game_id),
  at_second     INTEGER NOT NULL,
  radius_meters INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_zone_shrinks_game ON zone_shrinks(game_id);

-- Players registered in games
CREATE TABLE IF NOT EXISTS players (
  game_id       INTEGER NOT NULL REFERENCES games(game_id),
  address       TEXT NOT NULL,
  player_number INTEGER NOT NULL,
  is_alive      INTEGER NOT NULL DEFAULT 1,
  kills         INTEGER NOT NULL DEFAULT 0,
  checked_in    INTEGER NOT NULL DEFAULT 0,
  bluetooth_id  TEXT,
  eliminated_at INTEGER,
  eliminated_by TEXT,
  last_heartbeat_at INTEGER,
  has_claimed   INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (game_id, address)
);

CREATE INDEX IF NOT EXISTS idx_players_game ON players(game_id);

-- Target chain assignments
CREATE TABLE IF NOT EXISTS target_assignments (
  game_id        INTEGER NOT NULL REFERENCES games(game_id),
  hunter_address TEXT NOT NULL,
  target_address TEXT NOT NULL,
  assigned_at    INTEGER NOT NULL,
  PRIMARY KEY (game_id, hunter_address)
);

-- Kill records
CREATE TABLE IF NOT EXISTS kills (
  id              INTEGER PRIMARY KEY AUTOINCREMENT,
  game_id         INTEGER NOT NULL,
  hunter_address  TEXT NOT NULL,
  target_address  TEXT NOT NULL,
  timestamp       INTEGER NOT NULL,
  hunter_lat      REAL,
  hunter_lng      REAL,
  target_lat      REAL,
  target_lng      REAL,
  distance_meters REAL,
  tx_hash         TEXT
);

CREATE INDEX IF NOT EXISTS idx_kills_game ON kills(game_id);

-- Heartbeat scans (audit trail for anti-QR-hiding fairplay)
CREATE TABLE IF NOT EXISTS heartbeat_scans (
  id              INTEGER PRIMARY KEY AUTOINCREMENT,
  game_id         INTEGER NOT NULL,
  scanner_address TEXT NOT NULL,
  scanned_address TEXT NOT NULL,
  timestamp       INTEGER NOT NULL,
  scanner_lat     REAL,
  scanner_lng     REAL,
  distance_meters REAL
);

CREATE INDEX IF NOT EXISTS idx_heartbeat_game ON heartbeat_scans(game_id);

-- Location pings (recent only, older ones get pruned)
CREATE TABLE IF NOT EXISTS location_pings (
  game_id   INTEGER NOT NULL,
  address   TEXT NOT NULL,
  lat       REAL NOT NULL,
  lng       REAL NOT NULL,
  timestamp INTEGER NOT NULL,
  is_in_zone INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_location_pings_game_addr ON location_pings(game_id, address);

-- Operator transaction log
CREATE TABLE IF NOT EXISTS operator_txs (
  id           INTEGER PRIMARY KEY AUTOINCREMENT,
  game_id      INTEGER NOT NULL,
  action       TEXT NOT NULL,
  tx_hash      TEXT,
  status       TEXT NOT NULL DEFAULT 'pending',
  created_at   INTEGER NOT NULL,
  confirmed_at INTEGER,
  error        TEXT,
  params       TEXT
);

CREATE INDEX IF NOT EXISTS idx_operator_txs_game ON operator_txs(game_id);
CREATE INDEX IF NOT EXISTS idx_operator_txs_status ON operator_txs(status);

-- Game photos (uploaded by players)
CREATE TABLE IF NOT EXISTS game_photos (
  id        INTEGER PRIMARY KEY AUTOINCREMENT,
  game_id   INTEGER NOT NULL,
  address   TEXT NOT NULL,
  filename  TEXT NOT NULL,
  caption   TEXT,
  timestamp INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_photos_game ON game_photos(game_id);

-- Block tracking for event recovery
CREATE TABLE IF NOT EXISTS sync_state (
  key   TEXT PRIMARY KEY,
  value TEXT NOT NULL
);
`;
