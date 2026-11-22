import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react';
import { api, refreshAccessToken } from '../api/client';
import { useAuth } from '../auth/AuthContext';

/**
 * One Server-Sent Events connection to /api/live for the logged-in user.
 * EventSource can't send an Authorization header, so we read the stream with fetch() instead.
 * Reconnects with back-off; components subscribe with useLiveEvents().
 */
const LiveContext = createContext({ connected: false, subscribe: () => () => {} });

function parseSse(chunk, state, emit) {
    state.buffer += chunk;
    let idx;
    while ((idx = state.buffer.indexOf('\n\n')) >= 0) {
        const block = state.buffer.slice(0, idx);
        state.buffer = state.buffer.slice(idx + 2);
        let type = 'message';
        const data = [];
        for (const raw of block.split('\n')) {
            const line = raw.replace(/\r$/, '');
            if (line.startsWith('event:')) type = line.slice(6).trim();
            else if (line.startsWith('data:')) data.push(line.slice(5).trim());
        }
        if (data.length) {
            let payload = data.join('\n');
            try { payload = JSON.parse(payload); } catch { /* plain text */ }
            emit({ type, data: payload });
        }
    }
}

export function LiveProvider({ children }) {
    const { session } = useAuth();
    const token = session?.accessToken;
    const [connected, setConnected] = useState(false);
    const listeners = useRef(new Set());

    useEffect(() => {
        if (!token) return undefined;
        const controller = new AbortController();
        let delay = 2000;
        let stopped = false;

        const connect = async () => {
            while (!stopped) {
                try {
                    const res = await fetch('/api/live', {
                        headers: { Authorization: 'Bearer ' + token, Accept: 'text/event-stream' },
                        signal: controller.signal,
                    });
                    if (res.status === 401 || res.status === 403) {
                        // access token expired: renew it; the new token re-runs this effect and reconnects.
                        // If the session is over, the next API call logs the user out.
                        refreshAccessToken();
                        return;
                    }
                    if (!res.ok || !res.body) throw new Error('live stream ' + res.status);
                    setConnected(true);
                    delay = 2000;
                    const reader = res.body.pipeThrough(new TextDecoderStream()).getReader();
                    const state = { buffer: '' };
                    for (;;) {
                        const { value, done } = await reader.read();
                        if (done) break;
                        parseSse(value, state, (evt) => {
                            // The server ended this login (logout on another tab/device, or "all devices").
                            // Any API call now gets a 401 that can't be refreshed, which logs this tab out.
                            if (evt.type === 'session.ended') { api('GET', '/api/auth/me').catch(() => {}); return; }
                            listeners.current.forEach(fn => fn(evt));
                        });
                    }
                } catch {
                    if (stopped) return;
                }
                setConnected(false);
                await new Promise(r => setTimeout(r, delay));
                delay = Math.min(delay * 2, 30000);
            }
        };
        connect();
        return () => { stopped = true; controller.abort(); setConnected(false); };
    }, [token]);

    const subscribe = useCallback((fn) => {
        listeners.current.add(fn);
        return () => listeners.current.delete(fn);
    }, []);
    const value = useMemo(() => ({ connected, subscribe }), [connected, subscribe]);

    return <LiveContext.Provider value={value}>{children}</LiveContext.Provider>;
}

export const useLiveStatus = () => useContext(LiveContext).connected;

/** Calls handler({type, data}) for every live event whose type starts with one of the prefixes. */
export function useLiveEvents(handler, prefixes = ['']) {
    const { subscribe } = useContext(LiveContext);
    const ref = useRef(handler);
    ref.current = handler;
    const key = prefixes.join(',');
    useEffect(() => subscribe((evt) => {
        if (key.split(',').some(p => evt.type.startsWith(p)) && evt.type !== 'connected') ref.current(evt);
    }), [subscribe, key]); // eslint-disable-line react-hooks/exhaustive-deps
}
