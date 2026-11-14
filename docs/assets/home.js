/* Homepage behaviour: split-flap board, watch-view tabs, watch filters, counters,
   the scroll rail, and the pointer-scoped parallax + tilt. */
(() => {
  'use strict';
  const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)');
  const finePointer = window.matchMedia('(hover: hover) and (pointer: fine)');

  /* ── Watch-view tabs ──────────────────────────────────────────────────── */
  const tabs = [...document.querySelectorAll('[data-tab]')];
  if (tabs.length) {
    const panels = [...document.querySelectorAll('[data-screens] .screen')];
    const caption = document.querySelector('[data-caption]');
    const note = document.querySelector('[data-tab-note]');
    const NOTES = {
      'tab-map': 'A high-contrast map that turns with you. The route, the next street and the arrival time, all in one glance.',
      'tab-turn': 'A long buzz about 25 seconds out, and three short ones about six seconds before the turn — timed to your speed.',
      'tab-dimmed': 'Between turns the display drops to an arrow and a distance. Raise your wrist or press a button to bring the map back.'
    };
    function select(tab, focus = false) {
      tabs.forEach((item) => {
        const on = item === tab;
        item.setAttribute('aria-selected', String(on));
        item.tabIndex = on ? 0 : -1;
      });
      const target = tab.getAttribute('aria-controls');
      panels.forEach((panel) => {
        const on = panel.id === target;
        panel.hidden = !on;
        panel.classList.toggle('is-active', on);
        panel.classList.remove('is-entering');
        if (on && !reduceMotion.matches) {
          void panel.offsetWidth;
          panel.classList.add('is-entering');
        }
      });
      const [title, sub] = (tab.dataset.caption || '').split('|');
      if (caption) caption.innerHTML = `${title} <span>${sub || ''}</span>`;
      if (note) note.textContent = NOTES[tab.id] || '';
      if (focus) tab.focus();
    }
    tabs.forEach((tab, index) => {
      tab.addEventListener('click', () => select(tab));
      tab.addEventListener('keydown', (event) => {
        let next = null;
        if (event.key === 'ArrowRight' || event.key === 'ArrowDown') next = (index + 1) % tabs.length;
        if (event.key === 'ArrowLeft' || event.key === 'ArrowUp') next = (index - 1 + tabs.length) % tabs.length;
        if (event.key === 'Home') next = 0;
        if (event.key === 'End') next = tabs.length - 1;
        if (next !== null) { event.preventDefault(); select(tabs[next], true); }
      });
    });
  }

  /* ── Watch family filters ─────────────────────────────────────────────── */
  const filters = [...document.querySelectorAll('[data-filter]')];
  if (filters.length) {
    const watches = [...document.querySelectorAll('[data-family]')];
    const grid = document.querySelector('.watch-grid');
    const count = document.getElementById('watch-count');
    filters.forEach((button) => button.addEventListener('click', () => {
      const family = button.dataset.filter;
      if (grid) grid.dataset.activeFilter = family;
      filters.forEach((filter) => {
        const on = filter === button;
        filter.classList.toggle('is-active', on);
        filter.setAttribute('aria-pressed', String(on));
      });
      let shown = 0;
      watches.forEach((watch) => {
        const on = family === 'all' || watch.dataset.family === family;
        watch.hidden = !on;
        if (on) shown += 1;
      });
      if (count) count.textContent = `${shown} ${shown === 1 ? 'model' : 'models'}`;
    }));
  }

  /* ── Counters ─────────────────────────────────────────────────────────── */
  const counters = [...document.querySelectorAll('[data-count]')];
  if (counters.length) {
    const run = (el) => {
      const target = Number(el.dataset.count);
      const node = el.querySelector('.stat__n') || el;
      if (reduceMotion.matches || !window.requestAnimationFrame) { node.textContent = String(target); return; }
      const start = performance.now();
      const duration = 900;
      const tick = (now) => {
        const t = Math.min(1, (now - start) / duration);
        const eased = 1 - Math.pow(1 - t, 3);
        node.textContent = String(Math.round(target * eased));
        if (t < 1) requestAnimationFrame(tick);
        else node.textContent = String(target);
      };
      requestAnimationFrame(tick);
    };
    if ('IntersectionObserver' in window) {
      const io = new IntersectionObserver((entries) => {
        entries.forEach((entry) => {
          if (!entry.isIntersecting) return;
          run(entry.target);
          io.unobserve(entry.target);
        });
      }, { threshold: 0.6 });
      counters.forEach((el) => io.observe(el));
    } else {
      counters.forEach(run);
    }
  }

  /* ── Scroll rail ──────────────────────────────────────────────────────── */
  const rail = document.querySelector('[data-rail]');
  if (rail) {
    const steps = [...rail.querySelectorAll('[data-step]')];
    const track = rail.querySelector('.rail__track');
    let thresholds = [];
    let railTick = false;

    function measureRail() {
      if (window.innerWidth <= 1023) { thresholds = []; return; }
      const tr = track.getBoundingClientRect();
      thresholds = steps.map((step) => {
        const dot = step.querySelector('.step__dot').getBoundingClientRect();
        const t = (dot.top + dot.height / 2 - tr.top) / (tr.height || 1);
        return Math.min(0.98, Math.max(0.02, t));
      });
    }

    function paintRail() {
      railTick = false;
      if (window.innerWidth <= 1023) {
        rail.style.setProperty('--p', '1');
        steps.forEach((step) => step.classList.add('is-active'));
        return;
      }
      if (!thresholds.length) measureRail();
      const rect = rail.getBoundingClientRect();
      const span = rail.offsetHeight - window.innerHeight;
      const p = Math.min(1, Math.max(0, -rect.top / (span || 1)));
      rail.style.setProperty('--p', p.toFixed(4));
      let active = -1;
      thresholds.forEach((t, i) => { if (p >= t - 0.012) active = i; });
      steps.forEach((step, i) => step.classList.toggle('is-active', i <= active));
    }

    window.addEventListener('scroll', () => {
      if (!railTick) { railTick = true; requestAnimationFrame(paintRail); }
    }, { passive: true });
    window.addEventListener('resize', () => { measureRail(); paintRail(); }, { passive: true });
    requestAnimationFrame(() => { measureRail(); paintRail(); });
  }

  /* ── Tilt and parallax ────────────────────────────────────────────────── */
  const tilt = document.querySelector('[data-tilt]');
  if (tilt && finePointer.matches && !reduceMotion.matches) {
    tilt.addEventListener('pointermove', (event) => {
      const r = tilt.getBoundingClientRect();
      const dx = (event.clientX - (r.left + r.width / 2)) / (r.width / 2);
      const dy = (event.clientY - (r.top + r.height / 2)) / (r.height / 2);
      tilt.style.setProperty('--ry', `${(dx * 4.5).toFixed(2)}deg`);
      tilt.style.setProperty('--rx', `${(-dy * 4.5).toFixed(2)}deg`);
    });
    tilt.addEventListener('pointerleave', () => {
      tilt.style.setProperty('--ry', '0deg');
      tilt.style.setProperty('--rx', '0deg');
    });
  }

  const parallax = [...document.querySelectorAll('[data-parallax]')];
  if (parallax.length && !reduceMotion.matches) {
    let pTick = false;
    const paintParallax = () => {
      pTick = false;
      const vh = window.innerHeight;
      parallax.forEach((el) => {
        const r = el.getBoundingClientRect();
        if (r.bottom < -200 || r.top > vh + 200) return;
        const offset = (r.top + r.height / 2 - vh / 2) / vh;
        el.style.setProperty('--py', `${(offset * -34).toFixed(1)}px`);
      });
      if (tilt) tilt.style.setProperty('--py', `${Math.max(-40, Math.min(0, -window.scrollY * 0.05)).toFixed(1)}px`);
    };
    window.addEventListener('scroll', () => {
      if (!pTick) { pTick = true; requestAnimationFrame(paintParallax); }
    }, { passive: true });
    paintParallax();
  }
})();

/* ── FAQ accordion (independent of the rest) ────────────────────────────── */
(() => {
  const acc = document.querySelector('[data-acc]');
  if (!acc) return;
  const items = [...acc.querySelectorAll('.acc__item')];
  const api = items.map((item) => ({
    item,
    trigger: item.querySelector('.acc__trigger'),
    panel: item.querySelector('.acc__panel')
  }));

  api.forEach((entry) => {
    if (entry.trigger.getAttribute('aria-expanded') !== 'true') entry.panel.inert = true;
  });

  function close(entry) {
    entry.panel.inert = true;
    entry.trigger.setAttribute('aria-expanded', 'false');
    if (entry.panel.style.height === 'auto') entry.panel.style.height = `${entry.panel.scrollHeight}px`;
    requestAnimationFrame(() => { entry.panel.style.height = '0px'; });
  }
  function open(entry) {
    entry.panel.inert = false;
    entry.trigger.setAttribute('aria-expanded', 'true');
    entry.panel.style.height = `${entry.panel.scrollHeight}px`;
  }

  api.forEach((entry) => {
    entry.trigger.addEventListener('click', () => {
      const isOpen = entry.trigger.getAttribute('aria-expanded') === 'true';
      api.forEach((other) => {
        if (other === entry) return;
        if (other.trigger.getAttribute('aria-expanded') === 'true') close(other);
      });
      if (isOpen) close(entry); else open(entry);
    });
    entry.panel.addEventListener('transitionend', (event) => {
      if (event.propertyName !== 'height') return;
      if (entry.trigger.getAttribute('aria-expanded') === 'true') entry.panel.style.height = 'auto';
    });
  });

  window.addEventListener('resize', () => {
    api.forEach((entry) => {
      if (entry.trigger.getAttribute('aria-expanded') === 'true') entry.panel.style.height = 'auto';
    });
  }, { passive: true });
})();
