import { useEffect } from 'react';
import { MapContainer, TileLayer, Circle, CircleMarker, useMap } from 'react-leaflet';
import L from 'leaflet';
import 'leaflet/dist/leaflet.css';
import type { Game } from '../../types/game';
import type { LatLngExpression } from 'leaflet';

function FitBounds({ center, radius }: { center: [number, number]; radius: number }) {
  const map = useMap();
  useEffect(() => {
    const bounds = L.latLng(center).toBounds(radius * 2.5);
    map.fitBounds(bounds);
  }, [map, center, radius]);
  return null;
}

export function GameMap({ game }: { game: Game }) {
  const center: LatLngExpression = [game.centerLat, game.centerLng];
  const initialRadius = game.zoneShrinks[0].radiusMeters;
  const finalRadius = game.zoneShrinks[game.zoneShrinks.length - 1].radiusMeters;

  return (
    <div className="game-detail__map-section">
      <h3 className="game-detail__section-title">Game Zone</h3>
      <div className="game-detail__map">
        <MapContainer
          center={center}
          zoom={14}
          scrollWheelZoom={false}
          style={{ width: '100%', height: '100%' }}
        >
          <TileLayer
            url="https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}{r}.png"
            attribution='&copy; <a href="https://www.openstreetmap.org/copyright">OSM</a> &copy; <a href="https://carto.com/">CARTO</a>'
            subdomains="abcd"
            maxZoom={19}
          />
          <Circle
            center={center}
            radius={initialRadius}
            pathOptions={{ color: '#00FF88', weight: 2, opacity: 0.8, fillColor: '#00FF88', fillOpacity: 0.08 }}
          />
          {finalRadius !== initialRadius && (
            <Circle
              center={center}
              radius={finalRadius}
              pathOptions={{ color: '#FF3B3B', weight: 1, opacity: 0.5, dashArray: '6, 4', fillColor: '#FF3B3B', fillOpacity: 0.05 }}
            />
          )}
          <CircleMarker
            center={center}
            radius={5}
            pathOptions={{ color: '#00FF88', fillColor: '#00FF88', fillOpacity: 1, weight: 0 }}
          />
          <FitBounds center={[game.centerLat, game.centerLng]} radius={initialRadius} />
        </MapContainer>
      </div>
      <p className="game-detail__map-caption">
        {game.location} — {initialRadius >= 1000 ? `${(initialRadius / 1000).toFixed(1)} km` : `${initialRadius} m`} initial radius
      </p>
    </div>
  );
}
