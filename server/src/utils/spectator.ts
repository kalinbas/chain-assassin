const SPECTATOR_COORD_DECIMALS = 3;
const SPECTATOR_COORD_FACTOR = 10 ** SPECTATOR_COORD_DECIMALS;

/**
 * Quantize coordinates for spectator mode to avoid exposing exact positions.
 * 3 decimals ~= 110m at the equator.
 */
export function approximateSpectatorPosition(
  lat: number,
  lng: number
): { lat: number; lng: number } | null {
  if (!Number.isFinite(lat) || !Number.isFinite(lng)) return null;
  if (lat < -90 || lat > 90 || lng < -180 || lng > 180) return null;

  return {
    lat: Math.round(lat * SPECTATOR_COORD_FACTOR) / SPECTATOR_COORD_FACTOR,
    lng: Math.round(lng * SPECTATOR_COORD_FACTOR) / SPECTATOR_COORD_FACTOR,
  };
}

