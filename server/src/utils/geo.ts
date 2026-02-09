const EARTH_RADIUS_METERS = 6_371_000;

/**
 * Convert degrees to radians.
 */
function toRad(deg: number): number {
  return (deg * Math.PI) / 180;
}

/**
 * Haversine distance between two GPS points in meters.
 */
export function haversineDistance(
  lat1: number,
  lng1: number,
  lat2: number,
  lng2: number
): number {
  const dLat = toRad(lat2 - lat1);
  const dLng = toRad(lng2 - lng1);
  const a =
    Math.sin(dLat / 2) ** 2 +
    Math.cos(toRad(lat1)) * Math.cos(toRad(lat2)) * Math.sin(dLng / 2) ** 2;
  const c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
  return EARTH_RADIUS_METERS * c;
}

/**
 * Check if a point is within a circular zone.
 */
export function isInsideZone(
  playerLat: number,
  playerLng: number,
  centerLat: number,
  centerLng: number,
  radiusMeters: number
): boolean {
  return haversineDistance(playerLat, playerLng, centerLat, centerLng) <= radiusMeters;
}

/**
 * Convert contract int32 coordinate (lat/lng * 1e6) to decimal degrees.
 * Contract stores lat/lng as int32 with 6 decimal places of precision.
 */
export function contractCoordToDegrees(coord: number): number {
  return coord / 1_000_000;
}

/**
 * Convert decimal degrees to contract int32 coordinate.
 */
export function degreesToContractCoord(degrees: number): number {
  return Math.round(degrees * 1_000_000);
}
