import { useCallback, useEffect, useState } from 'react';
import { adminApi } from '../api/client';
import { useAuth } from '../auth/AuthContext';
import { useToast } from './Toast';
import { AsyncButton, Empty } from './ui';
import { fmtDateTime } from '../utils/format';

const ROLES = [['', 'All'], ['DONOR', 'Donors'], ['HOSPITAL', 'Hospitals'], ['ADMIN', 'Admins']];
const ROLE_BADGE = { DONOR: 'b-red', HOSPITAL: 'b-blue', ADMIN: 'b-gray' };

/** Search accounts; disable/enable (instant logout) and reset passwords. */
export function UsersPanel() {
    const { session } = useAuth();
    const toast = useToast();
    const [q, setQ] = useState('');
    const [role, setRole] = useState('');
    const [status, setStatus] = useState('');
    const [page, setPage] = useState(0);
    const [data, setData] = useState(null);
    const [reset, setReset] = useState(null);

    const load = useCallback(async () => {
        try {
            setData(await adminApi.users({ q, role, enabled: status, page, size: 15 }));
        } catch (e) { toast(e.message, 'error'); }
    }, [q, role, status, page, toast]);

    // small delay so typing doesn't fire a request per key
    useEffect(() => { const t = setTimeout(load, 250); return () => clearTimeout(t); }, [load]);

    const disable = async (u) => {
        const reason = window.prompt(`Disable ${u.username} (${u.displayName})?\nThey are logged out everywhere at once.\n\nReason (kept in the audit log):`);
        if (reason === null) return;
        try { await adminApi.disable(u.id, reason); toast(`${u.username} disabled and logged out`, 'ok'); load(); }
        catch (e) { toast(e.message, 'error'); }
    };
    const enable = async (u) => {
        try { await adminApi.enable(u.id); toast(`${u.username} enabled`, 'ok'); load(); }
        catch (e) { toast(e.message, 'error'); }
    };
    const unlock = async (u) => {
        try { await adminApi.unlock(u.id); toast(`${u.username} unlocked`, 'ok'); load(); }
        catch (e) { toast(e.message, 'error'); }
    };
    const resetPassword = async (u) => {
        if (!window.confirm(`Reset the password of ${u.username}? Their current password stops working and they are logged out.`)) return;
        try { setReset(await adminApi.resetPassword(u.id)); load(); }
        catch (e) { toast(e.message, 'error'); }
    };

    const pages = data ? Math.max(1, Math.ceil(data.total / data.size)) : 1;

    return (
        <section className="card">
            <div className="card-head"><h2>👥 Accounts</h2>
                {data && <span className="small muted">{data.total} account{data.total === 1 ? '' : 's'}</span>}</div>
            <div className="row" style={{ marginBottom: '.75rem' }}>
                <input value={q} onChange={e => { setQ(e.target.value); setPage(0); }}
                       placeholder="Search username, donor or hospital name" style={{ flex: '1 1 240px' }} aria-label="Search accounts" />
                <div className="chips">
                    {ROLES.map(([v, l]) => (
                        <button key={l} className={`chip ${role === v ? 'active' : ''}`} onClick={() => { setRole(v); setPage(0); }}>{l}</button>
                    ))}
                </div>
                <select value={status} onChange={e => { setStatus(e.target.value); setPage(0); }} style={{ width: 'auto' }} aria-label="Status">
                    <option value="">Any status</option><option value="true">Enabled</option><option value="false">Disabled</option>
                </select>
            </div>

            {reset && (
                <div className="status-box ok" style={{ marginBottom: '.75rem' }}>
                    <div className="big-num">🔑</div>
                    <div>
                        <b>Temporary password for {reset.username}</b>
                        <span className="small">Give it to them privately. It is shown only now. {reset.sessionsClosed} session(s) were closed.</span>
                        <div className="row" style={{ marginTop: '.35rem' }}>
                            <code style={{ fontSize: '1.05rem', letterSpacing: '.08em' }}>{reset.temporaryPassword}</code>
                            <button className="btn secondary small" onClick={() => navigator.clipboard?.writeText(reset.temporaryPassword)
                                .then(() => toast('Copied', 'ok'), () => toast('Copy it by hand', 'error'))}>Copy</button>
                            <button className="linkbtn small" onClick={() => setReset(null)}>Hide</button>
                        </div>
                    </div>
                </div>
            )}

            {!data ? <Empty>Loading…</Empty> : !data.items.length ? <Empty>No accounts match.</Empty> : (
                <div className="table-wrap">
                    <table>
                        <thead><tr><th>Account</th><th>Role</th><th>Status</th><th>Last login</th><th>Sessions</th><th /></tr></thead>
                        <tbody>{data.items.map(u => (
                            <tr key={u.id} className={u.enabled ? '' : 'row-disabled'}>
                                <td><b>{u.displayName}</b><div className="small muted">{u.username}</div></td>
                                <td><span className={`badge ${ROLE_BADGE[u.role]}`}>{u.role}</span></td>
                                <td>{!u.enabled ? <span className="badge b-gray">DISABLED</span>
                                    : u.lockedUntil ? <span className="badge b-amber" title={`Too many failed logins. Locked until ${fmtDateTime(u.lockedUntil)}`}>
                                        🔒 LOCKED</span>
                                        : <span className="badge b-green">ACTIVE</span>}
                                    {u.lockedUntil && <div className="small muted">until {fmtDateTime(u.lockedUntil)}</div>}</td>
                                <td className="small">{u.lastLoginAt ? fmtDateTime(u.lastLoginAt) : <span className="muted">never</span>}</td>
                                <td>{u.activeSessions}</td>
                                <td><div className="row" style={{ justifyContent: 'flex-end', flexWrap: 'nowrap' }}>
                                    {u.lockedUntil && <AsyncButton className="btn green small" onClick={() => unlock(u)}>Unlock</AsyncButton>}
                                    {u.username !== session.username && (u.enabled
                                        ? <AsyncButton className="btn secondary small" onClick={() => disable(u)}>Disable</AsyncButton>
                                        : <AsyncButton className="btn green small" onClick={() => enable(u)}>Enable</AsyncButton>)}
                                    <AsyncButton className="btn secondary small" onClick={() => resetPassword(u)}>Reset password</AsyncButton>
                                </div></td>
                            </tr>
                        ))}</tbody>
                    </table>
                </div>
            )}
            {data && pages > 1 && (
                <div className="row" style={{ marginTop: '.75rem', justifyContent: 'center' }}>
                    <button className="btn secondary small" disabled={page === 0} onClick={() => setPage(p => p - 1)}>‹ Prev</button>
                    <span className="small muted">Page {page + 1} of {pages}</span>
                    <button className="btn secondary small" disabled={page + 1 >= pages} onClick={() => setPage(p => p + 1)}>Next ›</button>
                </div>
            )}
        </section>
    );
}

const ACTIONS = ['LOGIN', 'LOGIN_FAILED', 'OTP_LOGIN', 'LOGOUT', 'LOGOUT_ALL', 'TOKEN_REUSE_DETECTED', 'PASSWORD_CHANGED',
    'PASSWORD_RESET', 'ACCOUNT_DISABLED', 'ACCOUNT_ENABLED', 'ACCOUNT_LOCKED', 'ACCOUNT_UNLOCKED', 'IP_BLOCKED',
    'DONOR_REGISTERED', 'HOSPITAL_CREATED'];
const ACTION_BADGE = {
    ACCOUNT_LOCKED: 'b-red', IP_BLOCKED: 'b-red', ACCOUNT_UNLOCKED: 'b-green',
    LOGIN_FAILED: 'b-amber', TOKEN_REUSE_DETECTED: 'b-red', ACCOUNT_DISABLED: 'b-red', PASSWORD_RESET: 'b-amber',
    LOGIN: 'b-green', OTP_LOGIN: 'b-green', ACCOUNT_ENABLED: 'b-green', DONOR_REGISTERED: 'b-blue', HOSPITAL_CREATED: 'b-blue',
};

/** Newest-first audit log with user and action filters. */
export function AuditPanel() {
    const toast = useToast();
    const [user, setUser] = useState('');
    const [action, setAction] = useState('');
    const [page, setPage] = useState(0);
    const [data, setData] = useState(null);

    const load = useCallback(async () => {
        try { setData(await adminApi.audit({ user, action, page, size: 25 })); }
        catch (e) { toast(e.message, 'error'); }
    }, [user, action, page, toast]);

    useEffect(() => { const t = setTimeout(load, 250); return () => clearTimeout(t); }, [load]);

    const pages = data ? Math.max(1, Math.ceil(data.total / data.size)) : 1;

    return (
        <section className="card">
            <div className="card-head"><h2>📜 Audit log</h2>
                <button className="btn secondary small" onClick={load}>↻ Refresh</button></div>
            <div className="row" style={{ marginBottom: '.75rem' }}>
                <input value={user} onChange={e => { setUser(e.target.value); setPage(0); }}
                       placeholder="Exact username (actor or target)" style={{ flex: '1 1 220px' }} aria-label="Filter by username" />
                <select value={action} onChange={e => { setAction(e.target.value); setPage(0); }} style={{ width: 'auto' }} aria-label="Filter by action">
                    <option value="">All actions</option>
                    {ACTIONS.map(a => <option key={a} value={a}>{a.replace(/_/g, ' ').toLowerCase()}</option>)}
                </select>
            </div>
            {!data ? <Empty>Loading…</Empty> : !data.items.length ? <Empty>Nothing recorded yet.</Empty> : (
                <div className="table-wrap">
                    <table>
                        <thead><tr><th>When</th><th>Action</th><th>Who</th><th>Account</th><th>Details</th><th>IP</th></tr></thead>
                        <tbody>{data.items.map(e => (
                            <tr key={e.id}>
                                <td className="small" style={{ whiteSpace: 'nowrap' }}>{fmtDateTime(e.at)}</td>
                                <td><span className={`badge ${ACTION_BADGE[e.action] || 'b-gray'}`}>{e.action.replace(/_/g, ' ')}</span></td>
                                <td className="small">{e.actor || <span className="muted">anonymous</span>}</td>
                                <td className="small">{e.target}</td>
                                <td className="small">{e.details}</td>
                                <td className="small muted">{e.ip}</td>
                            </tr>
                        ))}</tbody>
                    </table>
                </div>
            )}
            {data && pages > 1 && (
                <div className="row" style={{ marginTop: '.75rem', justifyContent: 'center' }}>
                    <button className="btn secondary small" disabled={page === 0} onClick={() => setPage(p => p - 1)}>‹ Newer</button>
                    <span className="small muted">Page {page + 1} of {pages}</span>
                    <button className="btn secondary small" disabled={page + 1 >= pages} onClick={() => setPage(p => p + 1)}>Older ›</button>
                </div>
            )}
        </section>
    );
}
