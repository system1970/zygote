/* zygote — minimal service worker.
 * Caches the app shell for offline use (stale-while-revalidate).
 * The harness lives on the loopback API, so network calls to 127.0.0.1:8787
 * are passed through untouched (same-origin relative fetches only).
 */
const CACHE = 'zygote-shell-v1';
const SHELL = ['./', './index.html', './manifest.webmanifest', './icons/zygote.svg'];

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches
      .open(CACHE)
      .then((cache) => cache.addAll(SHELL))
      .then(() => self.skipWaiting())
  );
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches
      .keys()
      .then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k))))
      .then(() => self.clients.claim())
  );
});

self.addEventListener('fetch', (event) => {
  const { request } = event;
  if (request.method !== 'GET') return;
  // Never intercept the harness loopback API.
  try {
    const url = new URL(request.url);
    if (url.hostname === '127.0.0.1' || url.hostname === 'localhost') return;
  } catch {
    return;
  }

  event.respondWith(
    caches.match(request).then((cached) => {
      const network = fetch(request)
        .then((response) => {
          if (response && response.ok) {
            const copy = response.clone();
            caches.open(CACHE).then((cache) => cache.put(request, copy));
          }
          return response;
        })
        .catch(() => cached);
      return cached || network;
    })
  );
});
