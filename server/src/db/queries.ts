import Database from "better-sqlite3";
import { config } from "../config.js";
import { runMigrations } from "./migrations.js";
import { createLogger } from "../utils/logger.js";
import { GamePhase } from "../utils/types.js";
import type {
  GameConfig,
  ActiveSubPhase,
  Player,
  TargetAssignment,
  Kill,
  LocationPing,
  OperatorTx,
  ZoneShrink,
  LeaderboardEntry,
  GamePhoto,
} from "../utils/types.js";

const log = createLogger("db");

let db: Database.Database;

/**
 * Initialize the database connection and run migrations.
 */
export function initDb(): Database.Database {
  db = new Database(config.dbPath);
  runMigrations(db);
  log.info({ path: config.dbPath }, "Database initialized");
  return db;
}

export function getDb(): Database.Database {
  if (!db) throw new Error("Database not initialized. Call initDb() first.");
  return db;
}

// ============ Reset ============

/**
 * Wipe all game-related data from the DB (for full rebuild from chain).
 * Preserves schema_version and sync_state structure but clears sync_state values.
 */
export function resetGameData(): void {
  const d = getDb();
  d.exec("DELETE FROM location_pings");
  d.exec("DELETE FROM heartbeat_scans");
  d.exec("DELETE FROM kills");
  d.exec("DELETE FROM target_assignments");
  d.exec("DELETE FROM operator_txs");
  d.exec("DELETE FROM game_photos");
  d.exec("DELETE FROM zone_shrinks");
  d.exec("DELETE FROM players");
  d.exec("DELETE FROM games");
  d.exec("DELETE FROM sync_state");
  log.info("All game data wiped for rebuild");
}

// ============ Games ============

export function insertGame(game: GameConfig & { phase?: number; totalCollected?: string; playerCount?: number }): void {
  getDb()
    .prepare(
      `INSERT INTO games (
        game_id, title, entry_fee, min_players, max_players,
        registration_deadline, game_date, expiry_deadline, created_at, creator,
        center_lat, center_lng, meeting_lat, meeting_lng,
        bps_1st, bps_2nd, bps_3rd, bps_kills, bps_creator, base_reward,
        total_collected, player_count, max_duration, phase
      ) VALUES (
        ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
      )`
    )
    .run(
      game.gameId,
      game.title,
      game.entryFee.toString(),
      game.minPlayers,
      game.maxPlayers,
      game.registrationDeadline,
      game.gameDate,
      game.expiryDeadline,
      game.createdAt,
      game.creator,
      game.centerLat,
      game.centerLng,
      game.meetingLat,
      game.meetingLng,
      game.bps1st,
      game.bps2nd,
      game.bps3rd,
      game.bpsKills,
      game.bpsCreator,
      game.baseReward.toString(),
      game.totalCollected ?? game.baseReward.toString(),
      game.playerCount ?? 0,
      game.maxDuration,
      game.phase ?? 0
    );
}

export function getGame(gameId: number): (GameConfig & {
  phase: GamePhase;
  subPhase: ActiveSubPhase | null;
  subPhaseStartedAt: number | null;
  startedAt: number | null;
  endedAt: number | null;
  winner1: string | null;
  winner2: string | null;
  winner3: string | null;
  topKiller: string | null;
  totalCollected: string;
  playerCount: number;
}) | null {
  const row = getDb()
    .prepare("SELECT * FROM games WHERE game_id = ?")
    .get(gameId) as Record<string, unknown> | undefined;
  if (!row) return null;
  return {
    gameId: row.game_id as number,
    title: row.title as string,
    entryFee: BigInt(row.entry_fee as string),
    minPlayers: row.min_players as number,
    maxPlayers: row.max_players as number,
    registrationDeadline: row.registration_deadline as number,
    gameDate: row.game_date as number,
    expiryDeadline: row.expiry_deadline as number,
    createdAt: row.created_at as number,
    creator: row.creator as string,
    centerLat: row.center_lat as number,
    centerLng: row.center_lng as number,
    meetingLat: (row.meeting_lat as number) ?? 0,
    meetingLng: (row.meeting_lng as number) ?? 0,
    bps1st: row.bps_1st as number,
    bps2nd: row.bps_2nd as number,
    bps3rd: row.bps_3rd as number,
    bpsKills: row.bps_kills as number,
    bpsCreator: row.bps_creator as number,
    baseReward: BigInt((row.base_reward as string) ?? "0"),
    maxDuration: (row.max_duration as number) ?? 0,
    totalCollected: (row.total_collected as string) ?? "0",
    playerCount: (row.player_count as number) ?? 0,
    phase: row.phase as GamePhase,
    subPhase: (row.sub_phase as ActiveSubPhase | null) ?? null,
    subPhaseStartedAt: row.sub_phase_started_at as number | null,
    startedAt: row.started_at as number | null,
    endedAt: row.ended_at as number | null,
    winner1: row.winner1 as string | null,
    winner2: row.winner2 as string | null,
    winner3: row.winner3 as string | null,
    topKiller: row.top_killer as string | null,
  };
}

export function updateGamePhase(
  gameId: number,
  phase: GamePhase,
  extra?: {
    startedAt?: number;
    endedAt?: number;
    subPhase?: ActiveSubPhase | null;
    subPhaseStartedAt?: number | null;
    winner1?: string;
    winner2?: string;
    winner3?: string;
    topKiller?: string;
  }
): void {
  let sql = "UPDATE games SET phase = ?";
  const params: unknown[] = [phase];

  if (extra?.startedAt !== undefined) {
    sql += ", started_at = ?";
    params.push(extra.startedAt);
  }
  if (extra?.endedAt !== undefined) {
    sql += ", ended_at = ?";
    params.push(extra.endedAt);
  }
  if (extra?.subPhase !== undefined) {
    sql += ", sub_phase = ?";
    params.push(extra.subPhase);
  }
  if (extra?.subPhaseStartedAt !== undefined) {
    sql += ", sub_phase_started_at = ?";
    params.push(extra.subPhaseStartedAt);
  }
  if (extra?.winner1 !== undefined) {
    sql += ", winner1 = ?";
    params.push(extra.winner1);
  }
  if (extra?.winner2 !== undefined) {
    sql += ", winner2 = ?";
    params.push(extra.winner2);
  }
  if (extra?.winner3 !== undefined) {
    sql += ", winner3 = ?";
    params.push(extra.winner3);
  }
  if (extra?.topKiller !== undefined) {
    sql += ", top_killer = ?";
    params.push(extra.topKiller);
  }

  sql += " WHERE game_id = ?";
  params.push(gameId);

  getDb().prepare(sql).run(...params);
}

export function updateSubPhase(
  gameId: number,
  subPhase: ActiveSubPhase | null,
  subPhaseStartedAt?: number | null
): void {
  if (subPhaseStartedAt === undefined) {
    getDb()
      .prepare("UPDATE games SET sub_phase = ? WHERE game_id = ?")
      .run(subPhase, gameId);
    return;
  }

  getDb()
    .prepare("UPDATE games SET sub_phase = ?, sub_phase_started_at = ? WHERE game_id = ?")
    .run(subPhase, subPhaseStartedAt, gameId);
}

export function getGamesInPhase(phase: GamePhase): GameConfig[] {
  const rows = getDb()
    .prepare("SELECT * FROM games WHERE phase = ?")
    .all(phase) as Record<string, unknown>[];
  return rows.map((row) => ({
    gameId: row.game_id as number,
    title: row.title as string,
    entryFee: BigInt(row.entry_fee as string),
    minPlayers: row.min_players as number,
    maxPlayers: row.max_players as number,
    registrationDeadline: row.registration_deadline as number,
    gameDate: row.game_date as number,
    expiryDeadline: row.expiry_deadline as number,
    createdAt: row.created_at as number,
    creator: row.creator as string,
    centerLat: row.center_lat as number,
    centerLng: row.center_lng as number,
    meetingLat: (row.meeting_lat as number) ?? 0,
    meetingLng: (row.meeting_lng as number) ?? 0,
    bps1st: row.bps_1st as number,
    bps2nd: row.bps_2nd as number,
    bps3rd: row.bps_3rd as number,
    bpsKills: row.bps_kills as number,
    bpsCreator: row.bps_creator as number,
    baseReward: BigInt((row.base_reward as string) ?? "0"),
    maxDuration: (row.max_duration as number) ?? 0,
  }));
}

export function getAllGames(): Array<GameConfig & {
  phase: GamePhase;
  subPhase: ActiveSubPhase | null;
  subPhaseStartedAt: number | null;
  startedAt: number | null;
  endedAt: number | null;
  winner1: string | null;
  winner2: string | null;
  winner3: string | null;
  topKiller: string | null;
  totalCollected: string;
  playerCount: number;
}> {
  const rows = getDb()
    .prepare("SELECT * FROM games ORDER BY game_id DESC")
    .all() as Record<string, unknown>[];
  return rows.map((row) => ({
    gameId: row.game_id as number,
    title: row.title as string,
    entryFee: BigInt(row.entry_fee as string),
    minPlayers: row.min_players as number,
    maxPlayers: row.max_players as number,
    registrationDeadline: row.registration_deadline as number,
    gameDate: row.game_date as number,
    expiryDeadline: row.expiry_deadline as number,
    createdAt: row.created_at as number,
    creator: row.creator as string,
    centerLat: row.center_lat as number,
    centerLng: row.center_lng as number,
    meetingLat: (row.meeting_lat as number) ?? 0,
    meetingLng: (row.meeting_lng as number) ?? 0,
    bps1st: row.bps_1st as number,
    bps2nd: row.bps_2nd as number,
    bps3rd: row.bps_3rd as number,
    bpsKills: row.bps_kills as number,
    bpsCreator: row.bps_creator as number,
    baseReward: BigInt((row.base_reward as string) ?? "0"),
    maxDuration: (row.max_duration as number) ?? 0,
    totalCollected: (row.total_collected as string) ?? "0",
    playerCount: (row.player_count as number) ?? 0,
    phase: row.phase as GamePhase,
    subPhase: (row.sub_phase as ActiveSubPhase | null) ?? null,
    subPhaseStartedAt: row.sub_phase_started_at as number | null,
    startedAt: row.started_at as number | null,
    endedAt: row.ended_at as number | null,
    winner1: row.winner1 as string | null,
    winner2: row.winner2 as string | null,
    winner3: row.winner3 as string | null,
    topKiller: row.top_killer as string | null,
  }));
}

export interface ActivityEvent {
  type: "create" | "register" | "start" | "kill" | "end" | "cancel";
  text: string;
  timestamp: number;
  txHash: string | null;
}

export function getGameActivity(gameId: number): ActivityEvent[] {
  const game = getGame(gameId);
  if (!game) return [];

  const events: ActivityEvent[] = [];

  // Game created
  events.push({
    type: "create",
    text: "Game created",
    timestamp: game.createdAt,
    txHash: null,
  });

  // Player registrations
  const players = getPlayers(gameId);
  for (const p of players) {
    events.push({
      type: "register",
      text: `Player #${p.playerNumber} registered`,
      timestamp: game.createdAt,
      txHash: null,
    });
  }

  // Game started
  if (game.startedAt) {
    events.push({
      type: "start",
      text: `Game started with ${game.playerCount} players`,
      timestamp: game.startedAt,
      txHash: null,
    });
  }

  // Kills
  const kills = getKills(gameId);
  for (const k of kills) {
    const hunter = getPlayer(gameId, k.hunterAddress);
    const target = getPlayer(gameId, k.targetAddress);
    const hunterNum = hunter?.playerNumber ?? 0;
    const targetNum = target?.playerNumber ?? 0;
    events.push({
      type: "kill",
      text: `Player #${hunterNum} eliminated Player #${targetNum}`,
      timestamp: k.timestamp,
      txHash: k.txHash,
    });
  }

  // Game ended or cancelled
  if (game.phase === GamePhase.ENDED && game.endedAt) {
    events.push({
      type: "end",
      text: "Game ended",
      timestamp: game.endedAt,
      txHash: null,
    });
  } else if (game.phase === GamePhase.CANCELLED && game.endedAt) {
    events.push({
      type: "cancel",
      text: "Game cancelled",
      timestamp: game.endedAt,
      txHash: null,
    });
  }

  // Sort newest first
  events.sort((a, b) => b.timestamp - a.timestamp);
  return events;
}

// ============ Zone Shrinks ============

export function insertZoneShrinks(gameId: number, shrinks: ZoneShrink[]): void {
  const stmt = getDb().prepare(
    "INSERT INTO zone_shrinks (game_id, at_second, radius_meters) VALUES (?, ?, ?)"
  );
  const insertMany = getDb().transaction((items: ZoneShrink[]) => {
    for (const s of items) {
      stmt.run(gameId, s.atSecond, s.radiusMeters);
    }
  });
  insertMany(shrinks);
}

export function getZoneShrinks(gameId: number): ZoneShrink[] {
  return (
    getDb()
      .prepare(
        "SELECT at_second, radius_meters FROM zone_shrinks WHERE game_id = ? ORDER BY at_second ASC"
      )
      .all(gameId) as { at_second: number; radius_meters: number }[]
  ).map((r) => ({ atSecond: r.at_second, radiusMeters: r.radius_meters }));
}

// ============ Players ============

export function insertPlayer(
  gameId: number,
  address: string,
  playerNumber: number
): void {
  getDb()
    .prepare(
      "INSERT INTO players (game_id, address, player_number) VALUES (?, ?, ?)"
    )
    .run(gameId, address.toLowerCase(), playerNumber);
}

export function getPlayer(gameId: number, address: string): Player | null {
  const row = getDb()
    .prepare("SELECT * FROM players WHERE game_id = ? AND address = ?")
    .get(gameId, address.toLowerCase()) as Record<string, unknown> | undefined;
  if (!row) return null;
  return mapPlayer(row);
}

export function getPlayerByNumber(gameId: number, playerNumber: number): Player | null {
  const row = getDb()
    .prepare("SELECT * FROM players WHERE game_id = ? AND player_number = ?")
    .get(gameId, playerNumber) as Record<string, unknown> | undefined;
  if (!row) return null;
  return mapPlayer(row);
}

export function getPlayers(gameId: number): Player[] {
  const rows = getDb()
    .prepare("SELECT * FROM players WHERE game_id = ? ORDER BY player_number ASC")
    .all(gameId) as Record<string, unknown>[];
  return rows.map(mapPlayer);
}

export function getAlivePlayers(gameId: number): Player[] {
  const rows = getDb()
    .prepare(
      "SELECT * FROM players WHERE game_id = ? AND is_alive = 1 ORDER BY player_number ASC"
    )
    .all(gameId) as Record<string, unknown>[];
  return rows.map(mapPlayer);
}

export function getPlayerCount(gameId: number): number {
  const row = getDb()
    .prepare("SELECT COUNT(*) as count FROM players WHERE game_id = ?")
    .get(gameId) as { count: number };
  return row.count;
}

export function getAlivePlayerCount(gameId: number): number {
  const row = getDb()
    .prepare(
      "SELECT COUNT(*) as count FROM players WHERE game_id = ? AND is_alive = 1"
    )
    .get(gameId) as { count: number };
  return row.count;
}

export function eliminatePlayer(
  gameId: number,
  address: string,
  eliminatedBy: string | null,
  eliminatedAt: number
): void {
  getDb()
    .prepare(
      "UPDATE players SET is_alive = 0, eliminated_at = ?, eliminated_by = ? WHERE game_id = ? AND address = ?"
    )
    .run(eliminatedAt, eliminatedBy?.toLowerCase() ?? null, gameId, address.toLowerCase());
}

export function incrementPlayerKills(gameId: number, address: string): void {
  getDb()
    .prepare(
      "UPDATE players SET kills = kills + 1 WHERE game_id = ? AND address = ?"
    )
    .run(gameId, address.toLowerCase());
}

export function setPlayerCheckedIn(gameId: number, address: string, bluetoothId?: string): void {
  getDb()
    .prepare(
      "UPDATE players SET checked_in = 1, bluetooth_id = ? WHERE game_id = ? AND address = ?"
    )
    .run(bluetoothId ?? null, gameId, address.toLowerCase());
}

export function getCheckedInCount(gameId: number): number {
  const row = getDb()
    .prepare(
      "SELECT COUNT(*) as count FROM players WHERE game_id = ? AND checked_in = 1"
    )
    .get(gameId) as { count: number };
  return row.count;
}

export function setPlayerClaimed(gameId: number, address: string): void {
  getDb()
    .prepare(
      "UPDATE players SET has_claimed = 1 WHERE game_id = ? AND address = ?"
    )
    .run(gameId, address.toLowerCase());
}

export function updatePlayerCount(gameId: number, playerCount: number): void {
  getDb()
    .prepare("UPDATE games SET player_count = ? WHERE game_id = ?")
    .run(playerCount, gameId);
}

export function updateTotalCollected(gameId: number, totalCollected: string): void {
  getDb()
    .prepare("UPDATE games SET total_collected = ? WHERE game_id = ?")
    .run(totalCollected, gameId);
}

function mapPlayer(row: Record<string, unknown>): Player {
  return {
    address: row.address as string,
    gameId: row.game_id as number,
    playerNumber: row.player_number as number,
    isAlive: (row.is_alive as number) === 1,
    kills: row.kills as number,
    checkedIn: (row.checked_in as number) === 1,
    bluetoothId: (row.bluetooth_id as string | null) ?? null,
    eliminatedAt: row.eliminated_at as number | null,
    eliminatedBy: row.eliminated_by as string | null,
    lastHeartbeatAt: row.last_heartbeat_at as number | null,
    hasClaimed: (row.has_claimed as number) === 1,
  };
}

// ============ Target Assignments ============

export function setTargetAssignment(
  gameId: number,
  hunter: string,
  target: string,
  assignedAt: number
): void {
  getDb()
    .prepare(
      `INSERT OR REPLACE INTO target_assignments (game_id, hunter_address, target_address, assigned_at)
       VALUES (?, ?, ?, ?)`
    )
    .run(gameId, hunter.toLowerCase(), target.toLowerCase(), assignedAt);
}

export function getTargetAssignment(
  gameId: number,
  hunter: string
): TargetAssignment | null {
  const row = getDb()
    .prepare(
      "SELECT * FROM target_assignments WHERE game_id = ? AND hunter_address = ?"
    )
    .get(gameId, hunter.toLowerCase()) as Record<string, unknown> | undefined;
  if (!row) return null;
  return {
    hunterAddress: row.hunter_address as string,
    targetAddress: row.target_address as string,
    assignedAt: row.assigned_at as number,
  };
}

export function getAllTargetAssignments(gameId: number): TargetAssignment[] {
  const rows = getDb()
    .prepare("SELECT * FROM target_assignments WHERE game_id = ?")
    .all(gameId) as Record<string, unknown>[];
  return rows.map((r) => ({
    hunterAddress: r.hunter_address as string,
    targetAddress: r.target_address as string,
    assignedAt: r.assigned_at as number,
  }));
}

export function removeTargetAssignment(gameId: number, hunter: string): void {
  getDb()
    .prepare(
      "DELETE FROM target_assignments WHERE game_id = ? AND hunter_address = ?"
    )
    .run(gameId, hunter.toLowerCase());
}

/**
 * Find who is hunting a given target.
 */
export function findHunterOf(gameId: number, target: string): string | null {
  const row = getDb()
    .prepare(
      "SELECT hunter_address FROM target_assignments WHERE game_id = ? AND target_address = ?"
    )
    .get(gameId, target.toLowerCase()) as { hunter_address: string } | undefined;
  return row?.hunter_address ?? null;
}

// ============ Kills ============

export function insertKill(kill: Kill): number {
  const result = getDb()
    .prepare(
      `INSERT INTO kills (game_id, hunter_address, target_address, timestamp,
        hunter_lat, hunter_lng, target_lat, target_lng, distance_meters, tx_hash)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`
    )
    .run(
      kill.gameId,
      kill.hunterAddress.toLowerCase(),
      kill.targetAddress.toLowerCase(),
      kill.timestamp,
      kill.hunterLat,
      kill.hunterLng,
      kill.targetLat,
      kill.targetLng,
      kill.distanceMeters,
      kill.txHash
    );
  return result.lastInsertRowid as number;
}

export function getKills(gameId: number): Kill[] {
  const rows = getDb()
    .prepare("SELECT * FROM kills WHERE game_id = ? ORDER BY timestamp ASC")
    .all(gameId) as Record<string, unknown>[];
  return rows.map((r) => ({
    id: r.id as number,
    gameId: r.game_id as number,
    hunterAddress: r.hunter_address as string,
    targetAddress: r.target_address as string,
    timestamp: r.timestamp as number,
    hunterLat: r.hunter_lat as number | null,
    hunterLng: r.hunter_lng as number | null,
    targetLat: r.target_lat as number | null,
    targetLng: r.target_lng as number | null,
    distanceMeters: r.distance_meters as number | null,
    txHash: r.tx_hash as string | null,
  }));
}

export function updateKillTxHash(killId: number, txHash: string): void {
  getDb()
    .prepare("UPDATE kills SET tx_hash = ? WHERE id = ?")
    .run(txHash, killId);
}

// ============ Location Pings ============

export function insertLocationPing(ping: LocationPing): void {
  getDb()
    .prepare(
      `INSERT INTO location_pings (game_id, address, lat, lng, timestamp, is_in_zone)
       VALUES (?, ?, ?, ?, ?, ?)`
    )
    .run(
      ping.gameId,
      ping.address.toLowerCase(),
      ping.lat,
      ping.lng,
      ping.timestamp,
      ping.isInZone ? 1 : 0
    );
}

export function getLatestLocationPing(
  gameId: number,
  address: string
): LocationPing | null {
  const row = getDb()
    .prepare(
      `SELECT * FROM location_pings
       WHERE game_id = ? AND address = ?
       ORDER BY timestamp DESC LIMIT 1`
    )
    .get(gameId, address.toLowerCase()) as Record<string, unknown> | undefined;
  if (!row) return null;
  return {
    gameId: row.game_id as number,
    address: row.address as string,
    lat: row.lat as number,
    lng: row.lng as number,
    timestamp: row.timestamp as number,
    isInZone: (row.is_in_zone as number) === 1,
  };
}

/**
 * Prune old location pings, keeping only the most recent per player.
 */
export function pruneLocationPings(gameId: number, keepSeconds: number): void {
  const cutoff = Math.floor(Date.now() / 1000) - keepSeconds;
  getDb()
    .prepare("DELETE FROM location_pings WHERE game_id = ? AND timestamp < ?")
    .run(gameId, cutoff);
}

// ============ Operator Transactions ============

export function insertOperatorTx(tx: OperatorTx): number {
  const result = getDb()
    .prepare(
      `INSERT INTO operator_txs (game_id, action, tx_hash, status, created_at, error, params)
       VALUES (?, ?, ?, ?, ?, ?, ?)`
    )
    .run(
      tx.gameId,
      tx.action,
      tx.txHash,
      tx.status,
      tx.createdAt,
      tx.error,
      tx.params
    );
  return result.lastInsertRowid as number;
}

export function updateOperatorTx(
  id: number,
  updates: { txHash?: string; status?: string; confirmedAt?: number; error?: string }
): void {
  const sets: string[] = [];
  const params: unknown[] = [];

  if (updates.txHash !== undefined) {
    sets.push("tx_hash = ?");
    params.push(updates.txHash);
  }
  if (updates.status !== undefined) {
    sets.push("status = ?");
    params.push(updates.status);
  }
  if (updates.confirmedAt !== undefined) {
    sets.push("confirmed_at = ?");
    params.push(updates.confirmedAt);
  }
  if (updates.error !== undefined) {
    sets.push("error = ?");
    params.push(updates.error);
  }

  if (sets.length === 0) return;

  params.push(id);
  getDb()
    .prepare(`UPDATE operator_txs SET ${sets.join(", ")} WHERE id = ?`)
    .run(...params);
}

// ============ Sync State ============

export function getSyncState(key: string): string | null {
  const row = getDb()
    .prepare("SELECT value FROM sync_state WHERE key = ?")
    .get(key) as { value: string } | undefined;
  return row?.value ?? null;
}

export function setSyncState(key: string, value: string): void {
  getDb()
    .prepare(
      "INSERT OR REPLACE INTO sync_state (key, value) VALUES (?, ?)"
    )
    .run(key, value);
}

// ============ Heartbeat ============

export function initPlayersHeartbeat(gameId: number, timestamp: number): void {
  getDb()
    .prepare(
      "UPDATE players SET last_heartbeat_at = ? WHERE game_id = ? AND is_alive = 1"
    )
    .run(timestamp, gameId);
}

export function updateLastHeartbeat(gameId: number, address: string, timestamp: number): void {
  getDb()
    .prepare(
      "UPDATE players SET last_heartbeat_at = ? WHERE game_id = ? AND address = ?"
    )
    .run(timestamp, gameId, address.toLowerCase());
}

export function getHeartbeatExpiredPlayers(
  gameId: number,
  now: number,
  intervalSeconds: number
): Player[] {
  const rows = getDb()
    .prepare(
      `SELECT * FROM players
       WHERE game_id = ? AND is_alive = 1
       AND last_heartbeat_at IS NOT NULL
       AND (? - last_heartbeat_at) > ?`
    )
    .all(gameId, now, intervalSeconds) as Record<string, unknown>[];
  return rows.map(mapPlayer);
}

export function insertHeartbeatScan(scan: {
  gameId: number;
  scannerAddress: string;
  scannedAddress: string;
  timestamp: number;
  scannerLat: number | null;
  scannerLng: number | null;
  distanceMeters: number | null;
}): void {
  getDb()
    .prepare(
      `INSERT INTO heartbeat_scans (game_id, scanner_address, scanned_address, timestamp, scanner_lat, scanner_lng, distance_meters)
       VALUES (?, ?, ?, ?, ?, ?, ?)`
    )
    .run(
      scan.gameId,
      scan.scannerAddress.toLowerCase(),
      scan.scannedAddress.toLowerCase(),
      scan.timestamp,
      scan.scannerLat,
      scan.scannerLng,
      scan.distanceMeters
    );
}

// ============ Leaderboard ============

export function getLeaderboard(gameId: number): LeaderboardEntry[] {
  const rows = getDb()
    .prepare(
      `SELECT address, player_number, kills, is_alive, eliminated_at
       FROM players WHERE game_id = ?
       ORDER BY is_alive DESC, kills DESC, player_number ASC`
    )
    .all(gameId) as Record<string, unknown>[];
  return rows.map((r) => ({
    address: r.address as string,
    playerNumber: r.player_number as number,
    kills: r.kills as number,
    isAlive: (r.is_alive as number) === 1,
    eliminatedAt: r.eliminated_at as number | null,
  }));
}

// ============ Photos ============

export function insertPhoto(
  gameId: number,
  address: string,
  filename: string,
  caption: string | null,
  timestamp: number
): number {
  const result = getDb()
    .prepare(
      `INSERT INTO game_photos (game_id, address, filename, caption, timestamp)
       VALUES (?, ?, ?, ?, ?)`
    )
    .run(gameId, address.toLowerCase(), filename, caption, timestamp);
  return result.lastInsertRowid as number;
}

export function getGamePhotos(gameId: number): GamePhoto[] {
  const rows = getDb()
    .prepare(
      "SELECT * FROM game_photos WHERE game_id = ? ORDER BY timestamp ASC"
    )
    .all(gameId) as Record<string, unknown>[];
  return rows.map((r) => ({
    id: r.id as number,
    gameId: r.game_id as number,
    address: r.address as string,
    filename: r.filename as string,
    caption: r.caption as string | null,
    timestamp: r.timestamp as number,
  }));
}
