import { useState, useEffect } from 'react';
import { SERVER_URL } from '../../config/server';

interface Photo {
  id: number;
  url: string;
  caption: string | null;
  timestamp: number;
}

export function PhotoGallery({ gameId }: { gameId: number }) {
  const [photos, setPhotos] = useState<Photo[]>([]);
  const [loading, setLoading] = useState(true);
  const [selected, setSelected] = useState<Photo | null>(null);

  useEffect(() => {
    fetch(`${SERVER_URL}/api/games/${gameId}/photos`)
      .then((res) => (res.ok ? res.json() : []))
      .then((data) => setPhotos(data))
      .catch(() => setPhotos([]))
      .finally(() => setLoading(false));
  }, [gameId]);

  if (loading) return null;
  if (photos.length === 0) return null;

  return (
    <section className="photo-gallery">
      <h2 className="section__title" style={{ fontSize: '1.3rem', marginBottom: '1rem' }}>Game Photos</h2>
      <div className="photo-gallery__grid">
        {photos.map((photo) => (
          <div
            key={photo.id}
            className="photo-gallery__item"
            onClick={() => setSelected(photo)}
          >
            <img
              src={`${SERVER_URL}${photo.url}`}
              alt={photo.caption || 'Game photo'}
              loading="lazy"
            />
          </div>
        ))}
      </div>

      {selected && (
        <div className="photo-gallery__modal" onClick={() => setSelected(null)}>
          <img
            src={`${SERVER_URL}${selected.url}`}
            alt={selected.caption || 'Game photo'}
          />
          {selected.caption && (
            <p className="photo-gallery__modal-caption">{selected.caption}</p>
          )}
          <p className="photo-gallery__modal-time">
            {new Date(selected.timestamp * 1000).toLocaleString()}
          </p>
        </div>
      )}
    </section>
  );
}
