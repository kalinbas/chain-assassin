export function Spinner({ text }: { text?: string }) {
  return (
    <div className="game-detail__loading">
      <div className="game-detail__loading-spinner" />
      {text && <p>{text}</p>}
    </div>
  );
}
