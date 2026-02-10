export function Hero() {
  return (
    <section className="hero" id="hero">
      <div className="hero__bg">
        <img src="/media/banner.png" alt="Players hunting in a park with QR codes" className="hero__img" />
        <div className="hero__overlay" />
      </div>
      <div className="hero__content">
        <h1 className="hero__title">
          Hunt or Be Hunted.<br />
          <span className="text-primary">On-Chain.</span>
        </h1>
        <p className="hero__sub">Real-world elimination game. GPS-tracked zones. QR-code kills. ETH prizes on Base.</p>
        <div className="hero__ctas">
          <a href="#" className="btn btn--primary btn--lg">Download App</a>
          <a href="#demo" className="btn btn--outline btn--lg">Watch Demo</a>
        </div>
        <div className="hero__badges">
          <div className="badge">100 Players</div>
          <div className="badge">Real ETH Prizes</div>
          <div className="badge">GPS + QR Verified</div>
        </div>
      </div>
    </section>
  );
}
