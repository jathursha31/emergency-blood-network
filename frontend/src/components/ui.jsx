import { useState } from 'react';
import { BLOOD_GROUPS } from '../utils/format';

const BADGE_CLASS = {
    OPEN: 'b-amber', MATCHED: 'b-blue', FULFILLED: 'b-green', CANCELLED: 'b-gray', EXPIRED: 'b-gray',
    NOTIFIED: 'b-amber', ACCEPTED: 'b-blue', DECLINED: 'b-gray', DONATED: 'b-green',
    CRITICAL: 'b-red', HIGH: 'b-amber', NORMAL: 'b-gray',
};

export function Badge({ value }) {
    return <span className={`badge ${BADGE_CLASS[value] || 'b-gray'}`}>{value}</span>;
}

export function BloodDrop({ label, small }) {
    return <span className={`blood ${small ? 'sm' : ''}`}>{label}</span>;
}

export function Empty({ children }) {
    return <div className="empty">{children}</div>;
}

export function BloodGroupSelect({ name, defaultValue = 'B_POS' }) {
    return (
        <select name={name} defaultValue={defaultValue} required>
            {BLOOD_GROUPS.map(([v, l]) => <option key={v} value={v}>{l}</option>)}
        </select>
    );
}

/** Button that disables itself while its async onClick runs (prevents double submits). */
export function AsyncButton({ onClick, children, className = 'btn', ...rest }) {
    const [busy, setBusy] = useState(false);
    const handle = async (e) => {
        setBusy(true);
        try { await onClick(e); } finally { setBusy(false); }
    };
    return <button type="button" className={className} disabled={busy} onClick={handle} {...rest}>{children}</button>;
}

/** Reads a <form> into a plain object with trimmed strings. */
export function readForm(form) {
    const out = {};
    new FormData(form).forEach((v, k) => { out[k] = typeof v === 'string' ? v.trim() : v; });
    return out;
}
