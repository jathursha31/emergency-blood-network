/* Service worker: makes the app installable, keeps working offline, and shows push notifications.
 * - /api/* is never cached: blood requests must always be live.
 * - /assets/* (hashed, never change) and icons: cache first.
 * - Page navigations: network first, falling back to the cached app shell, then to offline.html.
 */
const VERSION = 'bdn-v1';
const SHELL = ['/', '/index.html', '/offline.html', '/manifest.webmanifest', '/icons/icon-192.png', '/icons/badge-72.png'];

self.addEventListener('install', (event) => {
    event.waitUntil(caches.open(VERSION).then((c) => c.addAll(SHELL)).then(() => self.skipWaiting()));
});

self.addEventListener('activate', (event) => {
    event.waitUntil(
        caches.keys()
            .then((keys) => Promise.all(keys.filter((k) => k !== VERSION).map((k) => caches.delete(k))))
            .then(() => self.clients.claim()),
    );
});

self.addEventListener('fetch', (event) => {
    const req = event.request;
    if (req.method !== 'GET') return;
    const url = new URL(req.url);
    if (url.origin !== self.location.origin || url.pathname.startsWith('/api/')) return; // live data only

    if (req.mode === 'navigate') {
        event.respondWith(
            fetch(req)
                .then((res) => {
                    const copy = res.clone();
                    caches.open(VERSION).then((c) => c.put('/index.html', copy));
                    return res;
                })
                .catch(async () => (await caches.match('/index.html')) || caches.match('/offline.html')),
        );
        return;
    }

    if (url.pathname.startsWith('/assets/') || url.pathname.startsWith('/icons/')) {
        event.respondWith(
            caches.match(req).then((hit) => hit || fetch(req).then((res) => {
                if (res.ok) { const copy = res.clone(); caches.open(VERSION).then((c) => c.put(req, copy)); }
                return res;
            })),
        );
    }
});

// ---------------------------------------------------------------- push notifications

self.addEventListener('push', (event) => {
    let data = {};
    try { data = event.data ? event.data.json() : {}; } catch { data = { body: event.data && event.data.text() }; }
    const title = data.title || 'Blood Donor Network';
    event.waitUntil(self.registration.showNotification(title, {
        body: data.body || '',
        icon: '/icons/icon-192.png',
        badge: '/icons/badge-72.png',
        tag: data.tag,                 // a newer alert about the same request replaces the old one
        renotify: Boolean(data.tag),
        requireInteraction: Boolean(data.urgent),
        vibrate: data.urgent ? [200, 100, 200, 100, 400] : [200, 100, 200],
        data: { url: data.url || '/' },
    }));
});

self.addEventListener('notificationclick', (event) => {
    event.notification.close();
    const target = new URL(event.notification.data?.url || '/', self.location.origin).href;
    event.waitUntil((async () => {
        const windows = await self.clients.matchAll({ type: 'window', includeUncontrolled: true });
        for (const w of windows) {
            if (new URL(w.url).origin === self.location.origin) {
                await w.focus();
                if ('navigate' in w) await w.navigate(target);
                return;
            }
        }
        await self.clients.openWindow(target);
    })());
});
