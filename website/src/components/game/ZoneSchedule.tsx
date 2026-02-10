import type { ZoneShrink } from '../../types/game';

function formatSeconds(s: number): string {
  const m = Math.floor(s / 60);
  const sec = s % 60;
  return `${m}:${String(sec).padStart(2, '0')}`;
}

function formatRadius(meters: number): string {
  return meters >= 1000 ? `${(meters / 1000).toFixed(1)} km` : `${meters} m`;
}

export function ZoneSchedule({ zones }: { zones: ZoneShrink[] }) {
  return (
    <div className="game-detail__schedule">
      <h3 className="game-detail__section-title">Zone Schedule</h3>
      <table className="game-detail__schedule-table">
        <thead>
          <tr>
            <th>Time</th>
            <th>Radius</th>
          </tr>
        </thead>
        <tbody>
          {zones.map((z, i) => (
            <tr key={i}>
              <td>{formatSeconds(z.atSecond)}</td>
              <td>{formatRadius(z.radiusMeters)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
