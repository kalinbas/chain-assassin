/**
 * Normalize a Bluetooth identifier (MAC/opaque ID) for case-insensitive matching.
 */
export function normalizeBluetoothId(value: string | null | undefined): string | null {
  if (typeof value !== "string") return null;
  const trimmed = value.trim();
  if (trimmed.length === 0) return null;
  if (/^[0-9]{1,10}$/.test(trimmed)) return trimmed;
  if (/^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/.test(trimmed)) {
    return trimmed.toLowerCase();
  }

  const normalized = trimmed.toLowerCase();
  if (/^([0-9a-f]{2}:){5}[0-9a-f]{2}$/.test(normalized)) return normalized;
  return null;
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
