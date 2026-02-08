/* ========================================
   Chain-Assassin — Main JavaScript
   ======================================== */

document.addEventListener('DOMContentLoaded', () => {
    initHamburger();
    initCarousel();
    initFAQ();
    initCalculator();
    initScrollAnimations();
    initSmoothScroll();
});

/* ========== HAMBURGER MENU ========== */
function initHamburger() {
    const hamburger = document.getElementById('hamburger');
    const navLinks = document.getElementById('navLinks');

    if (!hamburger || !navLinks) return;

    hamburger.addEventListener('click', () => {
        hamburger.classList.toggle('active');
        navLinks.classList.toggle('open');
        document.body.style.overflow = navLinks.classList.contains('open') ? 'hidden' : '';
    });

    // Close menu on link click
    navLinks.querySelectorAll('a').forEach(link => {
        link.addEventListener('click', () => {
            hamburger.classList.remove('active');
            navLinks.classList.remove('open');
            document.body.style.overflow = '';
        });
    });
}

/* ========== SCREENSHOT CAROUSEL ========== */
function initCarousel() {
    const track = document.getElementById('carouselTrack');
    const dotsContainer = document.getElementById('carouselDots');
    const prevBtn = document.getElementById('carouselPrev');
    const nextBtn = document.getElementById('carouselNext');

    if (!track || !dotsContainer) return;

    const slides = track.querySelectorAll('.carousel__slide');
    const totalSlides = slides.length;
    let currentIndex = 0;
    let slidesPerView = getSlidesPerView();
    let autoPlayTimer = null;
    let touchStartX = 0;
    let touchDeltaX = 0;

    function getSlidesPerView() {
        if (window.innerWidth <= 768) return 1;
        if (window.innerWidth <= 1024) return 2;
        return 3;
    }

    function getMaxIndex() {
        return Math.max(0, totalSlides - slidesPerView);
    }

    function buildDots() {
        dotsContainer.innerHTML = '';
        const maxIdx = getMaxIndex();
        for (let i = 0; i <= maxIdx; i++) {
            const dot = document.createElement('button');
            dot.className = 'carousel__dot' + (i === currentIndex ? ' active' : '');
            dot.setAttribute('aria-label', `Go to slide ${i + 1}`);
            dot.addEventListener('click', () => goTo(i));
            dotsContainer.appendChild(dot);
        }
    }

    function updatePosition() {
        const slideWidth = slides[0].offsetWidth + 24; // gap
        track.style.transform = `translateX(-${currentIndex * slideWidth}px)`;

        // Update dots
        dotsContainer.querySelectorAll('.carousel__dot').forEach((dot, i) => {
            dot.classList.toggle('active', i === currentIndex);
        });
    }

    function goTo(index) {
        currentIndex = Math.max(0, Math.min(index, getMaxIndex()));
        updatePosition();
    }

    function next() {
        if (currentIndex >= getMaxIndex()) {
            goTo(0);
        } else {
            goTo(currentIndex + 1);
        }
    }

    function prev() {
        if (currentIndex <= 0) {
            goTo(getMaxIndex());
        } else {
            goTo(currentIndex - 1);
        }
    }

    function startAutoPlay() {
        stopAutoPlay();
        autoPlayTimer = setInterval(next, 4000);
    }

    function stopAutoPlay() {
        if (autoPlayTimer) {
            clearInterval(autoPlayTimer);
            autoPlayTimer = null;
        }
    }

    // Buttons
    if (prevBtn) prevBtn.addEventListener('click', () => { prev(); startAutoPlay(); });
    if (nextBtn) nextBtn.addEventListener('click', () => { next(); startAutoPlay(); });

    // Pause on hover
    const carousel = document.getElementById('carousel');
    if (carousel) {
        carousel.addEventListener('mouseenter', stopAutoPlay);
        carousel.addEventListener('mouseleave', startAutoPlay);
    }

    // Touch/swipe support
    track.addEventListener('touchstart', (e) => {
        touchStartX = e.touches[0].clientX;
        touchDeltaX = 0;
        stopAutoPlay();
    }, { passive: true });

    track.addEventListener('touchmove', (e) => {
        touchDeltaX = e.touches[0].clientX - touchStartX;
    }, { passive: true });

    track.addEventListener('touchend', () => {
        if (Math.abs(touchDeltaX) > 50) {
            if (touchDeltaX < 0) next();
            else prev();
        }
        startAutoPlay();
    });

    // Resize handler
    window.addEventListener('resize', () => {
        const newSPV = getSlidesPerView();
        if (newSPV !== slidesPerView) {
            slidesPerView = newSPV;
            if (currentIndex > getMaxIndex()) currentIndex = getMaxIndex();
            buildDots();
        }
        updatePosition();
    });

    // Init
    buildDots();
    updatePosition();
    startAutoPlay();
}

/* ========== FAQ ACCORDION ========== */
function initFAQ() {
    const faqItems = document.querySelectorAll('.faq-item');

    faqItems.forEach(item => {
        const question = item.querySelector('.faq-item__q');
        if (!question) return;

        question.addEventListener('click', () => {
            const isActive = item.classList.contains('active');

            // Close all others
            faqItems.forEach(other => {
                if (other !== item) {
                    other.classList.remove('active');
                    other.querySelector('.faq-item__q')?.setAttribute('aria-expanded', 'false');
                }
            });

            // Toggle current
            item.classList.toggle('active', !isActive);
            question.setAttribute('aria-expanded', !isActive);
        });
    });
}

/* ========== PRIZE CALCULATOR ========== */
function initCalculator() {
    const sliderPlayers = document.getElementById('sliderPlayers');
    const sliderFee = document.getElementById('sliderFee');
    const calcPlayers = document.getElementById('calcPlayers');
    const calcFee = document.getElementById('calcFee');
    const calcPool = document.getElementById('calcPool');
    const calc1st = document.getElementById('calc1st');
    const calc2nd = document.getElementById('calc2nd');
    const calc3rd = document.getElementById('calc3rd');

    if (!sliderPlayers || !sliderFee) return;

    function update() {
        const players = parseInt(sliderPlayers.value);
        const feeHundredths = parseInt(sliderFee.value);
        const fee = feeHundredths / 100;

        const pool = players * fee * 0.9;
        const first = pool * 0.40;
        const second = pool * 0.15;
        const third = pool * 0.10;

        calcPlayers.textContent = players;
        calcFee.textContent = fee.toFixed(2) + ' ETH';
        calcPool.textContent = pool.toFixed(3) + ' ETH';
        calc1st.textContent = first.toFixed(3) + ' ETH';
        calc2nd.textContent = second.toFixed(3) + ' ETH';
        calc3rd.textContent = third.toFixed(3) + ' ETH';
    }

    sliderPlayers.addEventListener('input', update);
    sliderFee.addEventListener('input', update);
    update();
}

/* ========== SCROLL ANIMATIONS ========== */
function initScrollAnimations() {
    // Add fade-in class to animatable elements
    const animatable = document.querySelectorAll(
        '.step, .feature-card, .game-card, .faq-item, .calculator, .host-info__list li, .video-wrapper, .prize-visual'
    );

    animatable.forEach(el => el.classList.add('fade-in'));

    const observer = new IntersectionObserver((entries) => {
        entries.forEach(entry => {
            if (entry.isIntersecting) {
                // Add small stagger delay based on element index among siblings
                const siblings = entry.target.parentElement?.querySelectorAll('.fade-in');
                if (siblings) {
                    const index = Array.from(siblings).indexOf(entry.target);
                    entry.target.style.transitionDelay = `${index * 0.08}s`;
                }
                entry.target.classList.add('visible');
                observer.unobserve(entry.target);
            }
        });
    }, {
        threshold: 0.15,
        rootMargin: '0px 0px -40px 0px'
    });

    animatable.forEach(el => observer.observe(el));
}

/* ========== SMOOTH SCROLL ========== */
function initSmoothScroll() {
    document.querySelectorAll('a[href^="#"]').forEach(link => {
        link.addEventListener('click', (e) => {
            const href = link.getAttribute('href');
            if (href === '#') return;

            const target = document.querySelector(href);
            if (target) {
                e.preventDefault();
                target.scrollIntoView({ behavior: 'smooth' });
            }
        });
    });
}
