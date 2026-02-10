import { Link } from 'react-router-dom';

export function NotFoundPage() {
  return (
    <main style={{ textAlign: 'center', padding: '8rem 1rem 4rem', minHeight: '60vh' }}>
      <h1 style={{ fontSize: '4rem', marginBottom: '0.5rem', color: 'var(--primary)' }}>404</h1>
      <p style={{ color: 'var(--text-sec)', fontSize: '1.1rem', marginBottom: '2rem' }}>
        This page doesn't exist. Maybe the zone shrank too fast.
      </p>
      <Link to="/" className="btn btn--primary">Back to Home</Link>
    </main>
  );
}
