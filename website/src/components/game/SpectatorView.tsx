import { useState, useRef, useEffect } from 'react';
import type { SpectatorState } from '../../hooks/useSpectatorSocket';
import { useSpectatorSound } from '../../hooks/useSpectatorSound';
import { SpectatorMap } from './SpectatorMap';
import { SpectatorLeaderboard } from './SpectatorLeaderboard';
import { SpectatorKillFeed } from './SpectatorKillFeed';
import { EyeIcon, EyeOffIcon, SoundOnIcon, SoundOffIcon } from '../icons/Icons';

function formatCountdown(nextShrinkAt: number | null | undefined): string | null {
  if (!nextShrinkAt) return null;
  const remaining = Math.max(0, Math.round(nextShrinkAt - Date.now() / 1000));
  if (remaining <= 0) return null;
  const m = Math.floor(remaining / 60);
  const s = remaining % 60;
  return `${m}:${String(s).padStart(2, '0')}`;
}

function useAliveFlash(aliveCount: number): boolean {
  const [flash, setFlash] = useState(false);
  const prevRef = useRef(aliveCount);

  useEffect(() => {
    if (aliveCount < prevRef.current) {
      setFlash(true);
      const timer = setTimeout(() => setFlash(false), 600);
      prevRef.current = aliveCount;
      return () => clearTimeout(timer);
    }
    prevRef.current = aliveCount;
  }, [aliveCount]);

  return flash;
}

function getKPM(totalKills: number, gameStartedAt: number | null): string {
  if (!gameStartedAt || totalKills === 0) return '0.0';
  const minutes = (Date.now() - gameStartedAt) / 60000;
  if (minutes < 0.1) return '0.0';
  return (totalKills / minutes).toFixed(1);
}

function formatPregameCountdown(pregameEndsAt: number | null): string | null {
  if (!pregameEndsAt) return null;
  const remaining = Math.max(0, Math.round(pregameEndsAt - Date.now() / 1000));
  if (remaining <= 0) return null;
  const m = Math.floor(remaining / 60);
  const s = remaining % 60;
  return `${m}:${String(s).padStart(2, '0')}`;
}

export function SpectatorView({ state }: { state: SpectatorState }) {
  const zone = state.zone;
  const countdown = formatCountdown(zone?.nextShrinkAt);
  const [showHuntLines, setShowHuntLines] = useState(false);
  const [soundEnabled, setSoundEnabled] = useState(false);
  const { resumeAudio } = useSpectatorSound(state, soundEnabled);
  const aliveFlash = useAliveFlash(state.aliveCount);

  // Force re-render every second during pregame for countdown
  const [, setTick] = useState(0);
  useEffect(() => {
    if (state.subPhase !== 'pregame') return;
    const timer = setInterval(() => setTick((t) => t + 1), 1000);
    return () => clearInterval(timer);
  }, [state.subPhase]);

  // Show pregame countdown overlay
  if (state.subPhase === 'pregame') {
    const pregameCountdown = formatPregameCountdown(state.pregameEndsAt);
    return (
      <div className="spectator">
        <div className="spectator__pregame">
          <div className="spectator__pregame-badge">PREGAME</div>
          <h3 className="spectator__pregame-title">Players Dispersing</h3>
          {pregameCountdown && (
            <div className="spectator__pregame-timer">{pregameCountdown}</div>
          )}
          <p className="spectator__pregame-desc">
            Targets will be assigned when the countdown ends. Players are spreading out and finding hiding spots.
          </p>
          <div className="spectator__pregame-stats">
            <span>Players: <strong>{state.aliveCount}</strong></span>
          </div>
        </div>
        <SpectatorKillFeed events={state.events} />
      </div>
    );
  }

  // Show winners if game ended
  if (state.phase === 'ended' && state.winners) {
    return (
      <div className="spectator">
        <div className="spectator__ended">
          <h3 className="spectator__ended-title">Game Over</h3>
          <div className="spectator__winners">
            {state.winners.winner1 !== 0 && (
              <div className="spectator__winner spectator__winner--1st">
                <span className="spectator__winner-rank">1st</span>
                <span className="spectator__winner-addr">Player #{state.winners.winner1}</span>
              </div>
            )}
            {state.winners.winner2 !== 0 && (
              <div className="spectator__winner spectator__winner--2nd">
                <span className="spectator__winner-rank">2nd</span>
                <span className="spectator__winner-addr">Player #{state.winners.winner2}</span>
              </div>
            )}
            {state.winners.winner3 !== 0 && (
              <div className="spectator__winner spectator__winner--3rd">
                <span className="spectator__winner-rank">3rd</span>
                <span className="spectator__winner-addr">Player #{state.winners.winner3}</span>
              </div>
            )}
          </div>
          <div className="spectator__final-stats">
            <span>Total Kills: <strong>{state.totalKills}</strong></span>
          </div>
        </div>
        <SpectatorMap state={state} showHuntLines={false} />
        <div className="spectator__panels">
          <SpectatorLeaderboard state={state} />
          <SpectatorKillFeed events={state.events} />
        </div>
      </div>
    );
  }

  return (
    <div className="spectator">
      <div className="spectator__stats-bar">
        <div className="spectator__live">
          <span className="spectator__live-dot" />
          LIVE
        </div>
        <span className={`spectator__stat ${aliveFlash ? 'spectator__stat--flash' : ''}`}>
          Alive: <strong>{state.aliveCount}</strong>/{state.playerCount}
        </span>
        {zone && (
          <span className="spectator__stat">
            Zone: <strong>{zone.currentRadiusMeters}m</strong>
            {zone.nextRadiusMeters != null && ` \u2192 ${zone.nextRadiusMeters}m`}
          </span>
        )}
        {countdown && (
          <span className="spectator__stat">
            Shrink in: <strong>{countdown}</strong>
          </span>
        )}
        <span className="spectator__stat">
          Kills: <strong>{state.totalKills}</strong>
        </span>
        <span className="spectator__stat">
          KPM: <strong>{getKPM(state.totalKills, state.gameStartedAt)}</strong>
        </span>
      </div>

      <div className="spectator__controls">
        <button
          className={`spectator__toggle ${showHuntLines ? 'spectator__toggle--active' : ''}`}
          onClick={() => setShowHuntLines(!showHuntLines)}
          title="Toggle hunt assignment lines"
        >
          {showHuntLines ? <EyeIcon width={14} height={14} /> : <EyeOffIcon width={14} height={14} />}
          Hunt Lines
        </button>
        <button
          className={`spectator__toggle ${soundEnabled ? 'spectator__toggle--active' : ''}`}
          onClick={() => { setSoundEnabled(!soundEnabled); resumeAudio(); }}
          title="Toggle sound effects"
        >
          {soundEnabled ? <SoundOnIcon width={14} height={14} /> : <SoundOffIcon width={14} height={14} />}
          Sound
        </button>
      </div>

      <SpectatorMap state={state} showHuntLines={showHuntLines} />

      <div className="spectator__panels">
        <SpectatorLeaderboard state={state} />
        <SpectatorKillFeed events={state.events} />
      </div>
    </div>
  );
}
