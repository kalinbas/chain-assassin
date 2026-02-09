import { config } from "../config.js";
import { haversineDistance, isInsideZone, contractCoordToDegrees } from "../utils/geo.js";
import { getZoneShrinks } from "../db/queries.js";
import { createLogger } from "../utils/logger.js";
import type { ZoneShrink, ZoneState, OutOfZoneTracker } from "../utils/types.js";

const log = createLogger("zoneTracker");

export class ZoneTracker {
  private gameId: number;
  private centerLat: number; // degrees
  private centerLng: number; // degrees
  private shrinks: ZoneShrink[];
  private startedAt: number; // unix seconds
  private currentShrinkIndex: number = 0;
  private currentRadius: number;
  private outOfZone: Map<string, OutOfZoneTracker> = new Map();

  constructor(
    gameId: number,
    centerLatContract: number, // int32 from contract
    centerLngContract: number,
    shrinks: ZoneShrink[],
    startedAt: number
  ) {
    this.gameId = gameId;
    this.centerLat = contractCoordToDegrees(centerLatContract);
    this.centerLng = contractCoordToDegrees(centerLngContract);
    this.shrinks = shrinks;
    this.startedAt = startedAt;

    // Initial radius is the first shrink entry
    this.currentRadius = shrinks.length > 0 ? shrinks[0].radiusMeters : 10000;

    // Find current shrink index based on elapsed time
    this.syncShrinkIndex();

    log.info(
      {
        gameId,
        center: { lat: this.centerLat, lng: this.centerLng },
        initialRadius: this.currentRadius,
        shrinkCount: shrinks.length,
      },
      "Zone tracker initialized"
    );
  }

  /**
   * Create a ZoneTracker from database state (for recovery).
   */
  static fromDb(gameId: number, centerLat: number, centerLng: number, startedAt: number): ZoneTracker {
    const shrinks = getZoneShrinks(gameId);
    return new ZoneTracker(gameId, centerLat, centerLng, shrinks, startedAt);
  }

  /**
   * Sync shrink index to current time.
   */
  private syncShrinkIndex(): void {
    const elapsed = Math.floor(Date.now() / 1000) - this.startedAt;
    let idx = 0;
    for (let i = 0; i < this.shrinks.length; i++) {
      if (elapsed >= this.shrinks[i].atSecond) {
        idx = i;
        this.currentRadius = this.shrinks[i].radiusMeters;
      } else {
        break;
      }
    }
    this.currentShrinkIndex = idx;
  }

  /**
   * Tick the zone — check if a new shrink has occurred.
   * Returns the new zone state if shrink happened, null otherwise.
   */
  tick(): ZoneState | null {
    const elapsed = Math.floor(Date.now() / 1000) - this.startedAt;
    const nextIndex = this.currentShrinkIndex + 1;

    if (nextIndex < this.shrinks.length && elapsed >= this.shrinks[nextIndex].atSecond) {
      this.currentShrinkIndex = nextIndex;
      this.currentRadius = this.shrinks[nextIndex].radiusMeters;

      log.info(
        { gameId: this.gameId, newRadius: this.currentRadius, shrinkIndex: nextIndex },
        "Zone shrunk"
      );

      return this.getZoneState();
    }

    return null;
  }

  /**
   * Check if a player position is inside the current zone.
   */
  isPlayerInZone(lat: number, lng: number): boolean {
    return isInsideZone(lat, lng, this.centerLat, this.centerLng, this.currentRadius);
  }

  /**
   * Process a player's location update.
   * Returns: { inZone, secondsRemaining } if player is outside zone.
   */
  processLocation(
    address: string,
    lat: number,
    lng: number,
    now: number
  ): { inZone: boolean; secondsRemaining?: number } {
    const inZone = this.isPlayerInZone(lat, lng);

    if (inZone) {
      // Player is in zone — clear any out-of-zone tracking
      if (this.outOfZone.has(address)) {
        log.info({ gameId: this.gameId, address }, "Player returned to zone");
        this.outOfZone.delete(address);
      }
      return { inZone: true };
    }

    // Player is outside zone
    const existing = this.outOfZone.get(address);
    if (!existing) {
      // Start tracking
      this.outOfZone.set(address, { address, exitedAt: now, warned: false });
      log.info({ gameId: this.gameId, address }, "Player exited zone");
      return { inZone: false, secondsRemaining: config.zoneGraceSeconds };
    }

    const elapsed = now - existing.exitedAt;
    const remaining = config.zoneGraceSeconds - elapsed;
    return { inZone: false, secondsRemaining: Math.max(0, remaining) };
  }

  /**
   * Get all players who have exceeded their zone grace period.
   */
  getExpiredPlayers(now: number): string[] {
    const expired: string[] = [];
    for (const [address, tracker] of this.outOfZone) {
      if (now - tracker.exitedAt >= config.zoneGraceSeconds) {
        expired.push(address);
      }
    }
    return expired;
  }

  /**
   * Remove a player from out-of-zone tracking (e.g., after elimination).
   */
  clearPlayer(address: string): void {
    this.outOfZone.delete(address);
  }

  /**
   * Get the current zone state.
   */
  getZoneState(): ZoneState {
    const nextIndex = this.currentShrinkIndex + 1;
    const hasNext = nextIndex < this.shrinks.length;

    return {
      centerLat: this.centerLat,
      centerLng: this.centerLng,
      currentRadiusMeters: this.currentRadius,
      nextShrinkAt: hasNext ? this.startedAt + this.shrinks[nextIndex].atSecond : null,
      nextRadiusMeters: hasNext ? this.shrinks[nextIndex].radiusMeters : null,
    };
  }

  /**
   * Get the distance from a point to the zone edge (negative = inside).
   */
  getDistanceToEdge(lat: number, lng: number): number {
    const dist = haversineDistance(lat, lng, this.centerLat, this.centerLng);
    return dist - this.currentRadius;
  }
}
