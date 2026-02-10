import { useEffect, useRef, useCallback } from 'react';
import type { SpectatorState } from './useSpectatorSocket';

// Web Audio API synthetic sound effects — no external files needed

function createAudioContext(): AudioContext | null {
  try {
    return new (window.AudioContext || (window as unknown as { webkitAudioContext: typeof AudioContext }).webkitAudioContext)();
  } catch {
    return null;
  }
}

function playTone(ctx: AudioContext, frequency: number, duration: number, type: OscillatorType = 'sine', volume = 0.15) {
  const osc = ctx.createOscillator();
  const gain = ctx.createGain();
  osc.type = type;
  osc.frequency.value = frequency;
  gain.gain.setValueAtTime(volume, ctx.currentTime);
  gain.gain.exponentialRampToValueAtTime(0.001, ctx.currentTime + duration);
  osc.connect(gain);
  gain.connect(ctx.destination);
  osc.start();
  osc.stop(ctx.currentTime + duration);
}

function playKillSound(ctx: AudioContext) {
  // Low thud
  playTone(ctx, 150, 0.3, 'sine', 0.2);
  // Brief high click
  playTone(ctx, 800, 0.05, 'square', 0.08);
}

function playZoneShrinkSound(ctx: AudioContext) {
  // Descending sweep
  const osc = ctx.createOscillator();
  const gain = ctx.createGain();
  osc.type = 'sawtooth';
  osc.frequency.setValueAtTime(600, ctx.currentTime);
  osc.frequency.exponentialRampToValueAtTime(200, ctx.currentTime + 0.5);
  gain.gain.setValueAtTime(0.1, ctx.currentTime);
  gain.gain.exponentialRampToValueAtTime(0.001, ctx.currentTime + 0.5);
  osc.connect(gain);
  gain.connect(ctx.destination);
  osc.start();
  osc.stop(ctx.currentTime + 0.5);
}

function playItemSound(ctx: AudioContext) {
  // Short blip
  playTone(ctx, 1200, 0.08, 'sine', 0.12);
  setTimeout(() => playTone(ctx, 1500, 0.06, 'sine', 0.08), 80);
}

function playGameEndSound(ctx: AudioContext) {
  // Chord
  playTone(ctx, 523, 0.5, 'sine', 0.1);  // C
  playTone(ctx, 659, 0.5, 'sine', 0.1);  // E
  playTone(ctx, 784, 0.5, 'sine', 0.1);  // G
}

export function useSpectatorSound(state: SpectatorState, enabled: boolean) {
  const ctxRef = useRef<AudioContext | null>(null);
  const lastEventCountRef = useRef(0);

  // Initialize audio context on first enable
  useEffect(() => {
    if (enabled && !ctxRef.current) {
      ctxRef.current = createAudioContext();
    }
  }, [enabled]);

  // Watch events and play sounds for new ones
  useEffect(() => {
    if (!enabled || !ctxRef.current) return;

    const ctx = ctxRef.current;
    const events = state.events;
    const newCount = events.length;
    const prevCount = lastEventCountRef.current;

    if (prevCount === 0) {
      // First load, don't play sounds for existing events
      lastEventCountRef.current = newCount;
      return;
    }

    // Play sounds for new events (events are prepended, so new ones are at the start)
    const newEvents = newCount - prevCount;
    if (newEvents > 0) {
      // Only play sound for the most recent event to avoid cacophony
      const latest = events[0];
      if (latest) {
        switch (latest.type) {
          case 'kill':
            playKillSound(ctx);
            break;
          case 'zone_shrink':
            playZoneShrinkSound(ctx);
            break;
          case 'item':
            playItemSound(ctx);
            break;
          case 'end':
            playGameEndSound(ctx);
            break;
        }
      }
    }

    lastEventCountRef.current = newCount;
  }, [state.events, enabled]);

  // Resume audio context if suspended (browser autoplay policy)
  const resumeAudio = useCallback(() => {
    if (ctxRef.current?.state === 'suspended') {
      ctxRef.current.resume();
    }
  }, []);

  return { resumeAudio };
}
