/* Progressive enhancement: navigation, screenshot tabs, filters and appearance.
   All downloads, guides and FAQ disclosures remain available without JavaScript. */
(() => {
  'use strict';
  const root = document.documentElement;
  root.classList.add('enhanced');
  const motionPreference = window.matchMedia('(prefers-reduced-motion: reduce)');
  const colorPreference = window.matchMedia('(prefers-color-scheme: dark)');
  const themeButton = document.querySelector('.theme-toggle');
  const isDark = () => root.dataset.theme ? root.dataset.theme === 'dark' : colorPreference.matches;

  function updateThemeControl() {
    if (!themeButton) return;
    const dark = isDark();
    themeButton.setAttribute('aria-label', `Switch to ${dark ? 'light' : 'dark'} appearance`);
    themeButton.querySelector('.icon').dataset.icon = dark ? 'sun' : 'moon';
    document.querySelector('meta[name="theme-color"]')?.setAttribute('content', dark ? '#10151e' : '#f7f9fc');
  }
  if (themeButton) {
    themeButton.hidden = false;
    updateThemeControl();
    themeButton.addEventListener('click', () => {
      const next = isDark() ? 'light' : 'dark';
      root.dataset.theme = next;
      try { localStorage.setItem('navmap-theme', next); } catch { /* Appearance still works without storage. */ }
      updateThemeControl();
    });
    colorPreference.addEventListener('change', updateThemeControl);
  }

  const menuButton = document.querySelector('.menu-toggle');
  const nav = document.querySelector('.main-nav');
  if (menuButton && nav) {
    menuButton.hidden = false;
    function setMenu(open, returnFocus = false) {
      menuButton.setAttribute('aria-expanded', String(open));
      menuButton.setAttribute('aria-label', open ? 'Close navigation' : 'Open navigation');
      menuButton.querySelector('.icon').dataset.icon = open ? 'x' : 'menu-2';
      nav.classList.toggle('is-open', open);
      if (returnFocus) menuButton.focus();
    }
    menuButton.addEventListener('click', () => setMenu(menuButton.getAttribute('aria-expanded') !== 'true'));
    nav.addEventListener('click', event => { if (event.target.closest('a')) setMenu(false); });
    document.addEventListener('keydown', event => {
      if (event.key === 'Escape' && menuButton.getAttribute('aria-expanded') === 'true') setMenu(false, true);
    });
    document.addEventListener('click', event => {
      if (!event.target.closest('.site-header') && menuButton.getAttribute('aria-expanded') === 'true') setMenu(false);
    });
    window.matchMedia('(min-width: 768px)').addEventListener('change', () => setMenu(false));
  }

  const tabs = [...document.querySelectorAll('.demo-tab')];
  const panels = [...document.querySelectorAll('.screen-panel')];
  function selectTab(tab, focus = false) {
    tabs.forEach(item => {
      const selected = item === tab;
      item.setAttribute('aria-selected', String(selected));
      item.tabIndex = selected ? 0 : -1;
    });
    panels.forEach(panel => {
      panel.hidden = panel.id !== tab.getAttribute('aria-controls');
      panel.classList.remove('is-entering');
      if (!panel.hidden && !motionPreference.matches) panel.classList.add('is-entering');
    });
    if (focus) tab.focus();
  }
  tabs.forEach((tab, index) => {
    tab.addEventListener('click', () => selectTab(tab));
    tab.addEventListener('keydown', event => {
      let next = null;
      if (event.key === 'ArrowDown' || event.key === 'ArrowRight') next = (index + 1) % tabs.length;
      if (event.key === 'ArrowUp' || event.key === 'ArrowLeft') next = (index - 1 + tabs.length) % tabs.length;
      if (event.key === 'Home') next = 0;
      if (event.key === 'End') next = tabs.length - 1;
      if (next !== null) { event.preventDefault(); selectTab(tabs[next], true); }
    });
  });

  const filters = [...document.querySelectorAll('[data-filter]')];
  const watches = [...document.querySelectorAll('[data-family]')];
  const watchCount = document.querySelector('#watch-count');
  filters.forEach(button => button.addEventListener('click', () => {
    const family = button.dataset.filter;
    document.querySelector('.watch-grid').dataset.activeFilter = family;
    filters.forEach(filter => filter.setAttribute('aria-pressed', String(filter === button)));
    let count = 0;
    watches.forEach(watch => {
      watch.hidden = family !== 'all' && watch.dataset.family !== family;
      if (!watch.hidden) count++;
    });
    if (watchCount) watchCount.textContent = `${count} compatible ${count === 1 ? 'model' : 'models'}`;
  }));

  const reveals = [...document.querySelectorAll('.reveal')];
  let revealObserver;
  function revealAll() {
    revealObserver?.disconnect();
    reveals.forEach(element => element.classList.remove('is-pending'));
  }
  if ('IntersectionObserver' in window && !motionPreference.matches) {
    revealObserver = new IntersectionObserver(entries => {
      entries.forEach(entry => {
        if (entry.isIntersecting) {
          entry.target.classList.remove('is-pending');
          revealObserver.unobserve(entry.target);
        }
      });
    }, { threshold: 0.08, rootMargin: '0px 0px 32px 0px' });
    // Measure together before changing classes, avoiding repeated layout work.
    const pending = reveals.map(element => ({ element, belowFold: element.getBoundingClientRect().top > window.innerHeight }));
    pending.forEach(({ element, belowFold }) => {
      if (belowFold) element.classList.add('is-pending');
      revealObserver.observe(element);
    });
    motionPreference.addEventListener('change', event => { if (event.matches) revealAll(); });
    window.addEventListener('beforeprint', revealAll);
  }

  document.querySelectorAll('[data-copy]').forEach(button => {
    button.hidden = false;
    button.addEventListener('click', async () => {
      const code = document.getElementById(button.dataset.copy);
      const status = document.getElementById(button.getAttribute('aria-describedby'));
      if (!code || !status) return;
      try {
        if (!navigator.clipboard?.writeText) throw new Error('Clipboard unavailable');
        await navigator.clipboard.writeText(code.textContent.trim());
        status.textContent = 'Command copied.';
        button.textContent = 'Copied';
        window.setTimeout(() => { button.textContent = 'Copy'; }, 2000);
      } catch {
        const range = document.createRange();
        range.selectNodeContents(code);
        const selection = window.getSelection();
        selection.removeAllRanges();
        selection.addRange(range);
        status.textContent = 'Copy is unavailable in this browser. The command is selected; use your browser’s Copy action.';
      }
    });
  });
})();
