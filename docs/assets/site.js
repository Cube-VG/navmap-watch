/* Shared behaviour: appearance, floating header, mobile drawer, reveals, waypoints,
   guide contents, copy buttons and the small pointer effects.
   Everything here is progressive — the site reads and navigates without it. */
(() => {
  'use strict';
  const root = document.documentElement;
  const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)');
  const finePointer = window.matchMedia('(hover: hover) and (pointer: fine)');
  root.classList.add('is-ready');

  /* ── Appearance ───────────────────────────────────────────────────────── */
  const themeButton = document.querySelector('.theme-toggle');
  const systemDark = window.matchMedia('(prefers-color-scheme: dark)');
  const isDark = () => (root.dataset.theme ? root.dataset.theme === 'dark' : systemDark.matches);
  function paintTheme() {
    const dark = isDark();
    if (themeButton) {
      themeButton.setAttribute('aria-label', `Switch to ${dark ? 'light' : 'dark'} appearance`);
      const icon = themeButton.querySelector('.icon');
      if (icon) icon.dataset.icon = dark ? 'sun' : 'moon';
    }
    const meta = document.querySelector('meta[name="theme-color"]');
    if (meta) meta.setAttribute('content', dark ? '#070d0b' : '#f4f1e7');
  }
  if (themeButton) {
    themeButton.hidden = false;
    paintTheme();
    themeButton.addEventListener('click', () => {
      const next = isDark() ? 'light' : 'dark';
      root.dataset.theme = next;
      try { localStorage.setItem('navmap-theme', next); } catch { /* prefer no storage errors */ }
      paintTheme();
    });
    systemDark.addEventListener('change', paintTheme);
  }

  /* ── Floating header ──────────────────────────────────────────────────── */
  const hdr = document.querySelector('[data-hdr]');
  if (hdr) {
    let lastY = window.scrollY;
    let ticking = false;
    const max = () => document.documentElement.scrollHeight - window.innerHeight;
    function paintHeader() {
      const y = window.scrollY;
      hdr.classList.toggle('is-docked', y > 20);
      if (document.body.classList.contains('nav-open')) {
        hdr.classList.remove('is-hidden');
      } else if (y > 420 && y > lastY + 8) {
        hdr.classList.add('is-hidden');
      } else if (y < lastY - 8 || y < 220) {
        hdr.classList.remove('is-hidden');
      }
      hdr.style.setProperty('--progress', Math.min(1, Math.max(0, y / (max() || 1))).toFixed(4));
      lastY = y;
      ticking = false;
    }
    window.addEventListener('scroll', () => {
      if (!ticking) { ticking = true; requestAnimationFrame(paintHeader); }
    }, { passive: true });
    window.addEventListener('resize', paintHeader, { passive: true });
    paintHeader();
  }

  /* ── Mobile drawer ────────────────────────────────────────────────────── */
  const drawer = document.getElementById('drawer');
  const menuButton = document.querySelector('.menu-btn');
  if (drawer && menuButton) {
    const setDrawer = (open) => {
      drawer.classList.toggle('is-open', open);
      drawer.setAttribute('aria-hidden', String(!open));
      menuButton.setAttribute('aria-expanded', String(open));
      menuButton.setAttribute('aria-label', open ? 'Close menu' : 'Open menu');
      menuButton.querySelector('.icon').dataset.icon = open ? 'x' : 'menu-2';
      document.body.classList.toggle('nav-open', open);
      if (open) drawer.querySelector('a')?.focus({ preventScroll: true });
      else menuButton.focus({ preventScroll: true });
    };
    menuButton.addEventListener('click', () => setDrawer(!drawer.classList.contains('is-open')));
    drawer.addEventListener('click', (event) => { if (event.target.closest('a')) setDrawer(false); });
    document.addEventListener('keydown', (event) => {
      if (event.key === 'Escape' && drawer.classList.contains('is-open')) setDrawer(false);
    });
    window.matchMedia('(min-width: 861px)').addEventListener('change', (event) => {
      if (event.matches && drawer.classList.contains('is-open')) {
        drawer.classList.remove('is-open');
        drawer.setAttribute('aria-hidden', 'true');
        menuButton.setAttribute('aria-expanded', 'false');
        document.body.classList.remove('nav-open');
      }
    });
  }

  /* ── Reveals ──────────────────────────────────────────────────────────── */
  const revealables = [...document.querySelectorAll('[data-reveal]')];
  const revealNow = (el) => el.classList.add('is-in');
  document.querySelectorAll('.hero__title').forEach((el) => requestAnimationFrame(() => revealNow(el)));
  if ('IntersectionObserver' in window && !reduceMotion.matches) {
    const io = new IntersectionObserver((entries) => {
      entries.forEach((entry) => {
        if (!entry.isIntersecting) return;
        revealNow(entry.target);
        io.unobserve(entry.target);
      });
    }, { rootMargin: '0px 0px -8% 0px', threshold: 0.08 });
    revealables.forEach((el) => io.observe(el));
    reduceMotion.addEventListener('change', (event) => { if (event.matches) revealables.forEach(revealNow); });
  } else {
    revealables.forEach(revealNow);
  }

  /* ── Waypoint rail ────────────────────────────────────────────────────── */
  const waypoints = document.querySelector('[data-waypoints]');
  if (waypoints && 'IntersectionObserver' in window) {
    const links = [...waypoints.querySelectorAll('a')];
    const targets = links.map((a) => document.getElementById(a.getAttribute('href').slice(1))).filter(Boolean);
    const spy = new IntersectionObserver((entries) => {
      entries.forEach((entry) => {
        if (!entry.isIntersecting) return;
        const id = `#${entry.target.id}`;
        links.forEach((a) => a.classList.toggle('is-active', a.getAttribute('href') === id));
      });
    }, { rootMargin: '-45% 0px -45% 0px', threshold: 0 });
    targets.forEach((t) => spy.observe(t));
  }

  /* ── Primary nav scroll-spy ───────────────────────────────────────────── */
  const navLinks = [...document.querySelectorAll('.nav a')].filter((a) => (a.getAttribute('href') || '').startsWith('#'));
  if (navLinks.length && 'IntersectionObserver' in window) {
    const pairs = navLinks
      .map((a) => [a, document.getElementById(a.getAttribute('href').slice(1))])
      .filter(([, el]) => el);
    const spy = new IntersectionObserver((entries) => {
      entries.forEach((entry) => {
        if (!entry.isIntersecting) return;
        pairs.forEach(([a, el]) => {
          if (el === entry.target) a.setAttribute('aria-current', 'true');
          else a.removeAttribute('aria-current');
        });
      });
    }, { rootMargin: '-30% 0px -60% 0px', threshold: 0 });
    pairs.forEach(([, el]) => spy.observe(el));
  }

  /* ── Guide contents ───────────────────────────────────────────────────── */
  const toc = document.querySelector('.toc');
  if (toc && 'IntersectionObserver' in window) {
    const links = [...toc.querySelectorAll('a')];
    const sections = links.map((a) => document.getElementById(a.getAttribute('href').slice(1))).filter(Boolean);
    const spy = new IntersectionObserver((entries) => {
      entries.forEach((entry) => {
        if (!entry.isIntersecting) return;
        const id = `#${entry.target.id}`;
        links.forEach((a) => a.classList.toggle('is-active', a.getAttribute('href') === id));
      });
    }, { rootMargin: '-12% 0px -70% 0px', threshold: 0 });
    sections.forEach((s) => spy.observe(s));
  }

  /* ── Copy buttons ─────────────────────────────────────────────────────── */
  document.querySelectorAll('[data-copy]').forEach((button) => {
    button.hidden = false;
    button.addEventListener('click', async () => {
      const code = document.getElementById(button.dataset.copy);
      const status = document.getElementById(button.getAttribute('aria-describedby'));
      if (!code || !status) return;
      try {
        if (!navigator.clipboard?.writeText) throw new Error('unavailable');
        await navigator.clipboard.writeText(code.textContent.trim());
        status.textContent = 'Command copied.';
        button.textContent = 'Copied';
        setTimeout(() => { button.textContent = 'Copy'; }, 1800);
      } catch {
        const range = document.createRange();
        range.selectNodeContents(code);
        const selection = window.getSelection();
        selection.removeAllRanges();
        selection.addRange(range);
        status.textContent = 'Copy is unavailable here. The command is selected — use your browser’s Copy action.';
      }
    });
  });

  /* ── Pointer effects: magnetic buttons and card spotlights ────────────── */
  const pointerEffects = finePointer.matches && !reduceMotion.matches;
  const magnets = [...document.querySelectorAll('[data-magnetic]')];
  if (pointerEffects && magnets.length) {
    magnets.forEach((el) => {
      el.addEventListener('pointermove', (event) => {
        const r = el.getBoundingClientRect();
        const dx = (event.clientX - (r.left + r.width / 2)) / (r.width / 2);
        const dy = (event.clientY - (r.top + r.height / 2)) / (r.height / 2);
        el.style.transform = `translate(${(dx * 4).toFixed(2)}px, ${(dy * 3 - 2).toFixed(2)}px)`;
      });
      el.addEventListener('pointerleave', () => { el.style.transform = ''; });
    });
  }
  const spots = [...document.querySelectorAll('[data-spot]')];
  if (pointerEffects && spots.length) {
    spots.forEach((el) => {
      el.addEventListener('pointermove', (event) => {
        const r = el.getBoundingClientRect();
        el.style.setProperty('--mx', `${event.clientX - r.left}px`);
        el.style.setProperty('--my', `${event.clientY - r.top}px`);
      });
    });
  }
})();
