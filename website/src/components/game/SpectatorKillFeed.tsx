import type { SpectatorEvent } from '../../hooks/useSpectatorSocket';
import { CrosshairIcon, AlertIcon, TargetIcon, BoltIcon, TrophyIcon, GhostIcon, RadarIcon, EmpIcon, DecoyIcon } from '../icons/Icons';

function formatTime(timestamp: number): string {
  const d = new Date(timestamp);
  return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}:${String(d.getSeconds()).padStart(2, '0')}`;
}

function getEventIcon(type: string, itemId?: string) {
  const size = 14;
  switch (type) {
    case 'kill':
      return <CrosshairIcon width={size} height={size} className="spectator__feed-icon spectator__feed-icon--kill" />;
    case 'zone_elim':
      return <AlertIcon width={size} height={size} className="spectator__feed-icon spectator__feed-icon--zone" />;
    case 'zone_shrink':
      return <TargetIcon width={size} height={size} className="spectator__feed-icon spectator__feed-icon--shrink" />;
    case 'start':
      return <BoltIcon width={size} height={size} className="spectator__feed-icon spectator__feed-icon--start" />;
    case 'end':
      return <TrophyIcon width={size} height={size} className="spectator__feed-icon spectator__feed-icon--end" />;
    case 'item': {
      switch (itemId) {
        case 'ghost_mode':
          return <GhostIcon width={size} height={size} className="spectator__feed-icon spectator__feed-icon--item" />;
        case 'ping_target':
        case 'ping_hunter':
          return <RadarIcon width={size} height={size} className="spectator__feed-icon spectator__feed-icon--item" />;
        case 'emp_blast':
          return <EmpIcon width={size} height={size} className="spectator__feed-icon spectator__feed-icon--item" />;
        case 'decoy_ping':
          return <DecoyIcon width={size} height={size} className="spectator__feed-icon spectator__feed-icon--item" />;
        default:
          return <BoltIcon width={size} height={size} className="spectator__feed-icon spectator__feed-icon--item" />;
      }
    }
    default:
      return null;
  }
}

export function SpectatorKillFeed({ events }: { events: SpectatorEvent[] }) {
  return (
    <div className="spectator__killfeed">
      <h4 className="spectator__panel-title">Live Feed</h4>
      <div className="spectator__feed-list">
        {events.map((ev, i) => (
          <div key={`${ev.timestamp}-${i}`} className={`spectator__feed-item spectator__feed-item--${ev.type}`}>
            {getEventIcon(ev.type, ev.meta?.itemId)}
            <span className="spectator__feed-time">{formatTime(ev.timestamp)}</span>
            <span className="spectator__feed-text">{ev.text}</span>
          </div>
        ))}
        {events.length === 0 && (
          <p className="spectator__empty">No events yet...</p>
        )}
      </div>
    </div>
  );
}
