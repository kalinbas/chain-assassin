import { useEffect } from 'react';
import { MapContainer, TileLayer, CircleMarker, useMap } from 'react-leaflet';
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

interface SpectatorMapProps {
  state: SpectatorState;
}

export function SpectatorMap({ state }: SpectatorMapProps) {
  const zone = state.zone;

  if (!zone) return null;

  const center: [number, number] = [zone.centerLat, zone.centerLng];

  // Anonymous player positions (no player numbers)
  const visiblePlayers = state.players.filter(
    (p) => p.lat != null && p.lng != null
  );

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

        {/* Anonymous player positions only */}
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

        <FitZone center={center} radius={zone.currentRadiusMeters} />
      </MapContainer>
    </div>
  );
}
