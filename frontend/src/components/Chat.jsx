import { useCallback, useEffect, useRef, useState } from 'react';
import { chatApi } from '../api/client';
import { useLiveEvents } from '../hooks/live';
import { useToast } from './Toast';

/**
 * Unread chat messages for the logged-in side, kept fresh by live "chat.message" events.
 * Returns { count(requestId, donorId?), total(requestId), refresh }.
 */
export function useChatUnread(side, onIncoming) {
    const [list, setList] = useState([]);
    const refresh = useCallback(async () => {
        try { setList(await (side === 'donor' ? chatApi.donorUnread() : chatApi.hospitalUnread())); } catch { /* keep last */ }
    }, [side]);
    useEffect(() => { refresh(); }, [refresh]);
    const incoming = useRef(onIncoming);
    incoming.current = onIncoming;
    useLiveEvents((evt) => {
        refresh();
        const fromOtherSide = evt.data?.from === (side === 'donor' ? 'HOSPITAL' : 'DONOR');
        if (fromOtherSide) incoming.current?.(evt.data);
    }, ['chat.']);

    const count = (requestId, donorId) => list
        .filter(u => u.requestId === requestId && (donorId == null || u.donorId === donorId))
        .reduce((n, u) => n + u.count, 0);
    return { count, total: (requestId) => count(requestId), refresh };
}

/** Small "💬 Chat" button with an unread count. */
export function ChatButton({ unread, onClick, label = 'Chat' }) {
    return (
        <button className="btn secondary small chat-btn" onClick={onClick}>
            💬 {label}{unread > 0 && <span className="count">{unread}</span>}
        </button>
    );
}

/**
 * Conversation between a hospital and one donor about one request, in a dialog.
 * side: 'donor' (the logged-in donor) or 'hospital' (hospital staff or admin).
 */
export function ChatDialog({ side, requestId, donorId, onClose }) {
    const toast = useToast();
    const [conv, setConv] = useState(null);
    const [error, setError] = useState(null);
    const [text, setText] = useState('');
    const [sending, setSending] = useState(false);
    const listRef = useRef(null);
    const inputRef = useRef(null);

    const load = useCallback(async () => {
        try {
            setConv(side === 'donor' ? await chatApi.donorGet(requestId) : await chatApi.hospitalGet(requestId, donorId));
            setError(null);
        } catch (e) { setError(e.message); }
    }, [side, requestId, donorId]);

    useEffect(() => { load(); }, [load]);
    useLiveEvents((evt) => {
        if (evt.data?.requestId === requestId && (side === 'donor' || evt.data?.donorId === donorId)) load();
    }, ['chat.']);

    // newest message in view
    useEffect(() => {
        const el = listRef.current;
        if (el) el.scrollTop = el.scrollHeight;
    }, [conv?.messages.length]);

    useEffect(() => {
        const onKey = (e) => { if (e.key === 'Escape') onClose(); };
        document.addEventListener('keydown', onKey);
        return () => document.removeEventListener('keydown', onKey);
    }, [onClose]);

    const send = async (e) => {
        e?.preventDefault();
        const body = text.trim();
        if (!body || sending) return;
        setSending(true);
        try {
            const msg = side === 'donor' ? await chatApi.donorSend(requestId, body) : await chatApi.hospitalSend(requestId, donorId, body);
            setText('');
            setConv(c => c && ({ ...c, messages: [...c.messages, msg] }));
            inputRef.current?.focus();
        } catch (err) { toast(err.message, 'error'); } finally { setSending(false); }
    };

    const onKeyDown = (e) => {
        if (e.key === 'Enter' && !e.shiftKey) send(e);   // Shift+Enter = new line
    };

    const other = conv ? (side === 'donor' ? conv.hospitalName : conv.donorName) : '';
    const lastMine = conv ? [...conv.messages].reverse().find(m => m.mine) : null;

    return (
        <div className="scan-backdrop" onClick={(e) => { if (e.target === e.currentTarget) onClose(); }}>
            <div className="scan-panel chat-panel" role="dialog" aria-modal="true" aria-label={`Chat with ${other}`}>
                <div className="chat-head">
                    <div>
                        <b>{other || 'Chat'}</b>
                        <div className="small muted">Request #{requestId}</div>
                    </div>
                    <button className="btn secondary small" onClick={onClose} aria-label="Close chat">✕</button>
                </div>

                <div className="chat-list" ref={listRef} aria-live="polite">
                    {error && <p className="muted small">{error}</p>}
                    {!conv && !error && <p className="muted small">Loading…</p>}
                    {conv && conv.messages.length === 0 && (
                        <p className="chat-empty">
                            {side === 'donor'
                                ? 'Ask about directions, parking or when to come. The hospital will reply here.'
                                : `Tell ${conv.donorName} where to come and when.`}
                        </p>
                    )}
                    {conv?.messages.map(m => (
                        <div key={m.id} className={`bubble ${m.mine ? 'mine' : 'theirs'}`}>
                            {!m.mine && <div className="bubble-from">{m.senderName}</div>}
                            <div className="bubble-body">{m.body}</div>
                            <div className="bubble-meta">
                                {fmtTime(m.createdAt)}
                                {m === lastMine && m.readAt && ' · Seen'}
                            </div>
                        </div>
                    ))}
                </div>

                {conv && (conv.canSend ? (
                    <form className="chat-input" onSubmit={send}>
                        <textarea ref={inputRef} rows={1} maxLength={1000} value={text} placeholder="Type a message…"
                                  onChange={(e) => setText(e.target.value)} onKeyDown={onKeyDown} aria-label="Message" autoFocus />
                        <button className="btn" disabled={sending || !text.trim()} aria-label="Send">Send</button>
                    </form>
                ) : <p className="chat-closed">{conv.closedReason}</p>)}
            </div>
        </div>
    );
}

function fmtTime(iso) {
    const d = new Date(iso);
    const today = new Date().toDateString() === d.toDateString();
    return d.toLocaleString(undefined, today ? { hour: 'numeric', minute: '2-digit' }
        : { day: 'numeric', month: 'short', hour: 'numeric', minute: '2-digit' });
}
