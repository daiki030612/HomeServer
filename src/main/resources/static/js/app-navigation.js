document.querySelectorAll('.app-switch-link[data-app-target]').forEach((link) => {
  const configuredUrl = link.dataset.url?.trim();
  if (configuredUrl) {
    link.href = new URL(configuredUrl, window.location.origin).href;
    return;
  }

  const target = new URL(window.location.href);
  target.port = link.dataset.port;
  target.pathname = `/${(link.dataset.path || '').replace(/^\/+/, '')}`;
  target.search = '';
  target.hash = '';
  link.href = target.href;
});
