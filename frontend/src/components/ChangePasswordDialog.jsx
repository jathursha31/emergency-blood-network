import { useEffect, useState } from 'react';
import { useAuth } from '../auth/AuthContext';
import { useToast } from './Toast';

/** Modal to change your own password. Other devices get logged out; this one stays in. */
export function ChangePasswordDialog({ onClose }) {
    const { changePassword } = useAuth();
    const toast = useToast();
    const [busy, setBusy] = useState(false);

    useEffect(() => {
        const onKey = (e) => { if (e.key === 'Escape') onClose(); };
        window.addEventListener('keydown', onKey);
        return () => window.removeEventListener('keydown', onKey);
    }, [onClose]);

    const submit = async (e) => {
        e.preventDefault();
        const f = new FormData(e.currentTarget);
        const next = f.get('newPassword');
        if (next !== f.get('confirm')) { toast('The two new passwords are different', 'error'); return; }
        setBusy(true);
        try {
            await changePassword(f.get('currentPassword'), next);
            toast('Password changed. Other devices have been logged out.', 'ok');
            onClose();
        } catch (err) { toast(err.message, 'error'); }
        finally { setBusy(false); }
    };

    return (
        <div className="scan-backdrop" role="dialog" aria-modal="true" aria-label="Change password" onClick={onClose}>
            <form className="scan-panel" onSubmit={submit} onClick={e => e.stopPropagation()}>
                <div className="row" style={{ marginBottom: '.75rem' }}>
                    <b>🔑 Change password</b><span className="spacer" />
                    <button type="button" className="btn secondary small" onClick={onClose}>Close</button>
                </div>
                <div className="field"><label>Current password</label>
                    <input name="currentPassword" type="password" required autoComplete="current-password" autoFocus /></div>
                <div className="field"><label>New password</label>
                    <input name="newPassword" type="password" required minLength={8} autoComplete="new-password" /></div>
                <div className="field"><label>New password again</label>
                    <input name="confirm" type="password" required minLength={8} autoComplete="new-password" /></div>
                <button className="btn block" type="submit" disabled={busy}>{busy ? 'Saving…' : 'Change password'}</button>
                <p className="hint">You stay logged in here. Every other phone or computer using this account is logged out.</p>
            </form>
        </div>
    );
}
