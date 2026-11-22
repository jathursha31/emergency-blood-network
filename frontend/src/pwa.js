// PWA helpers: the "Install app" prompt and Web Push subscription for this device.
import { useEffect, useState } from 'react';
import { pushApi } from './api/client';

// ---------------------------------------------------------------- install prompt

let deferredPrompt = null;
const installListeners = new Set();
const notifyInstall = () => installListeners.forEach(fn => fn(!!deferredPrompt));

if (typeof window !== 'undefined') {
    // Chrome/Edge/Android fire this when the app is installable; keep it so a button can show the prompt later
    window.addEventListener('beforeinstallprompt', (e) => {
        e.preventDefault();
        deferredPrompt = e;
        notifyInstall();
    });
    window.addEventListener('appinstalled', () => {
        deferredPrompt = null;
        notifyInstall();
    });
}

/** true when the browser offered to install the app and it is not installed yet. */
export function useCanInstall() {
    const [can, setCan] = useState(!!deferredPrompt);
    useEffect(() => {
        installListeners.add(setCan);
        return () => installListeners.delete(setCan);
    }, []);
    return can;
}

export async function promptInstall() {
    if (!deferredPrompt) return false;
    deferredPrompt.prompt();
    const { outcome } = await deferredPrompt.userChoice;
    deferredPrompt = null;
    notifyInstall();
    return outcome === 'accepted';
}

export const isStandalone = () =>
    window.matchMedia?.('(display-mode: standalone)').matches || window.navigator.standalone === true;

// ---------------------------------------------------------------- push notifications

export const pushSupported = () =>
    'serviceWorker' in navigator && 'PushManager' in window && 'Notification' in window;

/** iPhone/iPad only allow web push for apps added to the home screen. */
export const isIos = () => /iPad|iPhone|iPod/.test(navigator.userAgent) && !window.MSStream;

async function registration() {
    // In dev (vite) the service worker is not registered by main.jsx, so register it here on demand
    return (await navigator.serviceWorker.getRegistration()) || navigator.serviceWorker.register('/sw.js');
}

export async function currentSubscription() {
    if (!pushSupported()) return null;
    const reg = await navigator.serviceWorker.getRegistration();
    return reg ? reg.pushManager.getSubscription() : null;
}

function keyBytes(base64url) {
    const b64 = base64url.replace(/-/g, '+').replace(/_/g, '/');
    const raw = atob(b64 + '='.repeat((4 - b64.length % 4) % 4));
    return Uint8Array.from(raw, c => c.charCodeAt(0));
}

/** Asks for permission, subscribes this browser and registers it with the server. */
export async function enablePush() {
    if (!pushSupported()) throw new Error('This browser does not support notifications.');
    const permission = await Notification.requestPermission();
    if (permission !== 'granted') {
        throw new Error(permission === 'denied'
            ? 'Notifications are blocked. Allow them for this site in your browser settings.'
            : 'Notification permission was not given.');
    }
    const reg = await registration();
    await navigator.serviceWorker.ready;
    const { publicKey } = await pushApi.publicKey();
    let sub = await reg.pushManager.getSubscription();
    // a subscription made with a different server key cannot be used: replace it
    if (sub && sub.options?.applicationServerKey) {
        const current = new Uint8Array(sub.options.applicationServerKey);
        const wanted = keyBytes(publicKey);
        if (current.length !== wanted.length || current.some((b, i) => b !== wanted[i])) {
            await sub.unsubscribe();
            sub = null;
        }
    }
    if (!sub) sub = await reg.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: keyBytes(publicKey) });
    const json = sub.toJSON();
    await pushApi.subscribe({ endpoint: json.endpoint, keys: json.keys, userAgent: navigator.userAgent.slice(0, 200) });
    return sub;
}

/** Stops alerts on this device (server first, so a failed browser call never leaves alerts going to it). */
export async function disablePush() {
    const sub = await currentSubscription();
    if (!sub) return;
    try { await pushApi.unsubscribe(sub.endpoint); } catch { /* not logged in any more: the browser side still goes */ }
    await sub.unsubscribe();
}
