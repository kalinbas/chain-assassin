/**
 * Normalize a Bluetooth identifier (MAC/opaque ID) for case-insensitive matching.
 */
export function normalizeBluetoothId(value: string | null | undefined): string | null {
  if (typeof value !== "string") return null;
  const normalized = value.trim().toLowerCase();
  return normalized.length > 0 ? normalized : null;
}

/**
 * Check whether a required Bluetooth identifier appears in the nearby scan list.
 */
export function hasBleMatch(
  requiredBluetoothId: string,
  bleNearbyAddresses: string[]
): boolean {
  const required = normalizeBluetoothId(requiredBluetoothId);
  if (!required) return false;

  return bleNearbyAddresses.some((candidate) => normalizeBluetoothId(candidate) === required);
}
