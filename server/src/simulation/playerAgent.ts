import { haversineDistance } from "../utils/geo.js";
import type { SimulatedPlayer } from "./types.js";

// Meters per degree latitude (approximate)
const METERS_PER_DEG_LAT = 111_320;

/**
 * Convert meters to approximate degrees of longitude at a given latitude.
 */
function metersToDegreesLng(meters: number, lat: number): number {
  return meters / (METERS_PER_DEG_LAT * Math.cos((lat * Math.PI) / 180));
}

/**
 * Convert meters to degrees of latitude.
 */
function metersToDegreesLat(meters: number): number {
  return meters / METERS_PER_DEG_LAT;
}

/**
 * Generate a random number from a normal-ish distribution (Box-Muller).
 */
function randNormal(): number {
  const u1 = Math.random();
  const u2 = Math.random();
  return Math.sqrt(-2 * Math.log(u1 || 0.001)) * Math.cos(2 * Math.PI * u2);
}

/**
 * Scatter players randomly within the zone at the start.
 */
export function scatterPlayers(
  players: SimulatedPlayer[],
  centerLat: number,
  centerLng: number,
  radiusMeters: number
): void {
  for (const player of players) {
    // Random angle and distance (sqrt for uniform distribution within circle)
    const angle = Math.random() * 2 * Math.PI;
    const dist = Math.sqrt(Math.random()) * radiusMeters * 0.8; // Stay within 80%
    player.lat = centerLat + metersToDegreesLat(dist * Math.sin(angle));
    player.lng = centerLng + metersToDegreesLng(dist * Math.cos(angle), centerLat);
  }
}

/**
 * Move a single player for one tick (1 game-second).
 * Returns updated (lat, lng).
 */
export function movePlayer(
  player: SimulatedPlayer,
  targetLat: number | null,
  targetLng: number | null,
  centerLat: number,
  centerLng: number,
  currentZoneRadius: number,
  baseSpeedMps: number
): void {
  if (!player.isAlive) return;

  const distToCenter = haversineDistance(player.lat, player.lng, centerLat, centerLng);
  const speed = baseSpeedMps * player.aggressiveness;

  let dlat = 0;
  let dlng = 0;

  // Determine behavior state
  if (distToCenter > currentZoneRadius) {
    // Outside zone — flee straight to center
    player.state = "fleeing_zone";
    const dirLat = centerLat - player.lat;
    const dirLng = centerLng - player.lng;
    const mag = Math.sqrt(dirLat ** 2 + dirLng ** 2) || 0.0001;
    dlat = (dirLat / mag) * metersToDegreesLat(speed * 2); // Double speed when fleeing
    dlng = (dirLng / mag) * metersToDegreesLng(speed * 2, player.lat);
  } else if (targetLat !== null && targetLng !== null) {
    const distToTarget = haversineDistance(player.lat, player.lng, targetLat, targetLng);

    if (distToTarget < 300) {
      // Close to target — pursue
      player.state = "hunting";
      const dirLat = targetLat - player.lat;
      const dirLng = targetLng - player.lng;
      const mag = Math.sqrt(dirLat ** 2 + dirLng ** 2) || 0.0001;
      const pursuitSpeed = speed * 1.3;
      dlat = (dirLat / mag) * metersToDegreesLat(pursuitSpeed);
      dlng = (dirLng / mag) * metersToDegreesLng(pursuitSpeed, player.lat);
      // Add small random jitter
      dlat += randNormal() * metersToDegreesLat(speed * 0.2);
      dlng += randNormal() * metersToDegreesLng(speed * 0.2, player.lat);
    } else {
      // Target far away — wander with general drift toward target
      player.state = "wandering";
      const dirLat = targetLat - player.lat;
      const dirLng = targetLng - player.lng;
      const mag = Math.sqrt(dirLat ** 2 + dirLng ** 2) || 0.0001;
      // Light drift toward target + random walk
      dlat = (dirLat / mag) * metersToDegreesLat(speed * 0.3) + randNormal() * metersToDegreesLat(speed * 0.7);
      dlng = (dirLng / mag) * metersToDegreesLng(speed * 0.3, player.lat) + randNormal() * metersToDegreesLng(speed * 0.7, player.lat);
    }
  } else {
    // No target known — pure random walk
    player.state = "wandering";
    dlat = randNormal() * metersToDegreesLat(speed);
    dlng = randNormal() * metersToDegreesLng(speed, player.lat);
  }

  // Zone edge avoidance: if we'd be past 80% of radius, bias inward
  const zoneRatio = distToCenter / currentZoneRadius;
  if (zoneRatio > 0.75 && player.state !== "fleeing_zone") {
    const pullStrength = (zoneRatio - 0.75) * 2; // 0 at 75%, 0.5 at 100%
    const dirLat = centerLat - player.lat;
    const dirLng = centerLng - player.lng;
    const mag = Math.sqrt(dirLat ** 2 + dirLng ** 2) || 0.0001;
    dlat += (dirLat / mag) * metersToDegreesLat(speed * pullStrength);
    dlng += (dirLng / mag) * metersToDegreesLng(speed * pullStrength, player.lat);
  }

  player.lat += dlat;
  player.lng += dlng;
}

/**
 * Check if a hunter should attempt a kill this tick.
 * Returns true if the kill should be attempted.
 */
export function shouldAttemptKill(
  hunterLat: number,
  hunterLng: number,
  targetLat: number,
  targetLng: number,
  killProximityMeters: number,
  killProbabilityPerTick: number,
  aggressiveness: number
): boolean {
  const dist = haversineDistance(hunterLat, hunterLng, targetLat, targetLng);
  if (dist > killProximityMeters) return false;
  return Math.random() < killProbabilityPerTick * aggressiveness;
}
