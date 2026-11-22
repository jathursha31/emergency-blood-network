import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { ChangePasswordDialog } from './ChangePasswordDialog';
import { useAuth } from '../auth/AuthContext';
import { useLiveStatus } from '../hooks/live';
import { useToast } from './Toast';

const ROLE_NAME = { DONOR: 'Donor', HOSPITAL: 'Hospital', ADMIN: 'Admin' };

export default function Topbar() {
    const { session, logout, logoutEverywhere } = useAuth();
    const navigate = useNavigate();
    const toast = useToast();
    const live = useLiveStatus();
    const [passwordOpen, setPasswordOpen] = useState(false);

    const signOut = async () => {
        await logout();
        navigate('/login');
    };

    const signOutEverywhere = async () => {
        if (!window.confirm('Log out on every phone and computer where you are signed in?')) return;
        try {
            const n = await logoutEverywhere();
            toast(`Logged out of ${n} session${n === 1 ? '' : 's'}.`, 'ok');
            navigate('/login');
        } catch (e) { toast(e.message, 'error'); }
    };

    return (
        <header className="topbar">
            <div className="brand">
                <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 2C9 6.5 5 10.6 5 15a7 7 0 0 0 14 0c0-4.4-4-8.5-7-13z" /></svg>
                <span>Blood Donor Network</span>
            </div>
            {session && (
                <div className="userbox">
                    <span className={`live-dot ${live ? 'on' : ''}`}
                          title={live ? 'Live updates on' : 'Reconnecting… (data still refreshes every 20 s)'}>
                        {live ? 'Live' : 'Offline'}</span>
                    <span><b>{session.displayName}</b> · {ROLE_NAME[session.role]}</span>
                    <button className="linkbtn small" onClick={() => setPasswordOpen(true)}>Password</button>
                    <button className="btn secondary small" onClick={signOut}>Log out</button>
                    <button className="linkbtn small" onClick={signOutEverywhere}
                            title="End every session of this account, on all devices">All devices</button>
                </div>
            )}
            {passwordOpen && <ChangePasswordDialog onClose={() => setPasswordOpen(false)} />}
        </header>
    );
}
