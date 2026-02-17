import { useEffect, useState } from 'react';
import { MapContainer, TileLayer, Circle, CircleMarker, useMap } from 'react-leaflet';
import L from 'leaflet';
import 'leaflet/dist/leaflet.css';
import type { SpectatorState } from '../../hooks/useSpectatorSocket';

function FitZone({ center, radius }: { center: [number, number]; radius: number }) {
  const map = useMap();
  useEffect(() => {
    const bounds = L.latLng(center).toBounds(radius * 2.5);
    map.fitBounds(bounds);
  }, [map, center, radius]);
  return null;
}

// Animated kill flash circle that expands and fades
function KillFlashCircle({ lat, lng, timestamp }: { lat: number; lng: number; timestamp: number }) {
  const [radius, setRadius] = useState(5);
  const [opacity, setOpacity] = useState(0.8);

  useEffect(() => {
    const start = timestamp;
    const duration = 2000;
    let raf: number;

    const animate = () => {
      const elapsed = Date.now() - start;
      const progress = Math.min(elapsed / duration, 1);
      setRadius(5 + progress * 80); // expand from 5m to 85m
      setOpacity(0.8 * (1 - progress)); // fade out
      if (progress < 1) raf = requestAnimationFrame(animate);
    };

    raf = requestAnimationFrame(animate);
    return () => cancelAnimationFrame(raf);
  }, [timestamp]);

  if (opacity <= 0.01) return null;

  return (
    <Circle
      center={[lat, lng]}
      radius={radius}
      pathOptions={{
        color: '#FF3B3B',
        weight: 2,
        opacity,
        fillColor: '#FF3B3B',
        fillOpacity: opacity * 0.3,
      }}
    />
  );
}

interface SpectatorMapProps {
  state: SpectatorState;
}

export function SpectatorMap({ state }: SpectatorMapProps) {
  const zone = state.zone;

  if (!zone) return null;

  const center: [number, number] = [zone.centerLat, zone.centerLng];
  const now = Date.now();

  // Filter: only alive players with positions
  const visiblePlayers = state.players.filter(
    (p) => p.lat != null && p.lng != null
  );

  // Active ping circles
  const activePings = state.pingCircles.filter((c) => c.expiresAt > now);

  return (
    <div className="spectator__map">
      <MapContainer
        center={center}
        zoom={14}
        scrollWheelZoom={true}
        style={{ width: '100%', height: '100%' }}
      >
        <TileLayer
          url="https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}{r}.png"
          attribution='&copy; <a href="https://www.openstreetmap.org/copyright">OSM</a> &copy; <a href="https://carto.com/">CARTO</a>'
          subdomains="abcd"
          maxZoom={19}
        />

        {/* Current zone */}
        <Circle
          center={center}
          radius={zone.currentRadiusMeters}
          pathOptions={{ color: '#00FF88', weight: 2, opacity: 0.8, fillColor: '#00FF88', fillOpacity: 0.06 }}
        />

        {/* Next zone preview */}
        {zone.nextRadiusMeters != null && (
          <Circle
            center={center}
            radius={zone.nextRadiusMeters}
            pathOptions={{ color: '#FFBB00', weight: 1, opacity: 0.5, dashArray: '6 4', fillOpacity: 0 }}
          />
        )}

        {/* Anonymous player dots — glow circle behind each */}
        {visiblePlayers.map((player, index) => {
          const pos: [number, number] = [player.lat!, player.lng!];
          return (
            <CircleMarker
              key={`glow-${index}`}
              center={pos}
              radius={14}
              pathOptions={{
                color: 'transparent',
                fillColor: '#00FF88',
                fillOpacity: 0.12,
                weight: 0,
              }}
            />
          );
        })}

        {/* Anonymous player dots — main dot */}
        {visiblePlayers.map((player, index) => {
          const pos: [number, number] = [player.lat!, player.lng!];
          return (
            <CircleMarker
              key={`player-${index}`}
              center={pos}
              radius={7}
              pathOptions={{
                color: '#00FF88',
                fillColor: '#00FF88',
                fillOpacity: 0.85,
                weight: 1,
              }}
            />
          );
        })}

        {/* Ping circles (Ping Target = cyan, Ping Hunter = orange) */}
        {activePings.map((ping, i) => {
          const color = ping.type === 'target' ? '#00D4FF' : '#FF8800';
          const remaining = (ping.expiresAt - now) / (ping.expiresAt - (ping.expiresAt - 30000));
          const fadeOpacity = Math.min(1, Math.max(0.2, remaining));
          return (
            <Circle
              key={`ping-${i}-${ping.expiresAt}`}
              center={[ping.lat, ping.lng]}
              radius={ping.radius}
              pathOptions={{
                color,
                weight: 2,
                opacity: 0.7 * fadeOpacity,
                fillColor: color,
                fillOpacity: 0.15 * fadeOpacity,
                dashArray: '6 4',
              }}
            />
          );
        })}

        {/* Kill flashes */}
        {state.killFlashes.map((flash, i) => (
          <KillFlashCircle key={`flash-${flash.timestamp}-${i}`} lat={flash.lat} lng={flash.lng} timestamp={flash.timestamp} />
        ))}

        <FitZone center={center} radius={zone.currentRadiusMeters} />
      </MapContainer>
    </div>
  );
}
