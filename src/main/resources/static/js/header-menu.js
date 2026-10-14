(() => {
  const header = document.querySelector('.unified-header');
  const button = header?.querySelector('[data-header-menu-button]');
  const menu = header?.querySelector('[data-header-menu]');
  if (!header || !button || !menu) return;
  const close = () => { menu.classList.remove('show'); button.setAttribute('aria-expanded', 'false'); };
  button.addEventListener('click', event => {
    event.stopPropagation();
    const open = menu.classList.toggle('show');
    button.setAttribute('aria-expanded', String(open));
  });
  document.addEventListener('click', event => { if (!header.contains(event.target)) close(); });
  document.addEventListener('keydown', event => { if (event.key === 'Escape') close(); });
})();
