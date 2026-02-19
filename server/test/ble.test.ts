import { describe, expect, it } from "vitest";
import { hasBleMatch, normalizeBluetoothId } from "../src/game/ble.js";

describe("BLE token normalization", () => {
  it("accepts numeric player tokens", () => {
    expect(normalizeBluetoothId("71173941")).toBe("71173941");
    expect(normalizeBluetoothId(" 72 ")).toBe("72");
  });

  it("accepts UUID identifiers", () => {
    expect(normalizeBluetoothId("3F2504E0-4F89-11D3-9A0C-0305E82C3301"))
      .toBe("3f2504e0-4f89-11d3-9a0c-0305e82c3301");
  });

  it("accepts canonical MAC IDs for legacy compatibility", () => {
    expect(normalizeBluetoothId("AA:BB:CC:DD:EE:FF")).toBe("aa:bb:cc:dd:ee:ff");
  });

  it("rejects non-token garbage", () => {
    expect(normalizeBluetoothId("player-72")).toBeNull();
    expect(normalizeBluetoothId("")).toBeNull();
    expect(normalizeBluetoothId("  ")).toBeNull();
    expect(normalizeBluetoothId(null)).toBeNull();
  });
});

describe("BLE proximity matching", () => {
  it("matches normalized values in nearby list", () => {
    expect(hasBleMatch("72", ["99", "72"])).toBe(true);
    expect(hasBleMatch("AA:BB:CC:DD:EE:FF", ["aa:bb:cc:dd:ee:ff"])).toBe(true);
    expect(hasBleMatch(
      "3F2504E0-4F89-11D3-9A0C-0305E82C3301",
      ["3f2504e0-4f89-11d3-9a0c-0305e82c3301"]
    )).toBe(true);
  });

  it("fails when normalized token is missing", () => {
    expect(hasBleMatch("72", ["73"])).toBe(false);
    expect(hasBleMatch("invalid", ["invalid"])).toBe(false);
  });
});
