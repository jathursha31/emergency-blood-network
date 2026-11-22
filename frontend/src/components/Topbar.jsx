import { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { ChangePasswordDialog } from './ChangePasswordDialog';
import { useAuth } from '../auth/AuthContext';
import { useLiveStatus } from '../hooks/live';
import { promptInstall, useCanInstall } from '../pwa';
import { useToast } from './Toast';

const ROLE_NAME = { DONOR: 'Donor', HOSPITAL: 'Hospital', ADMIN: 'Admin' };

export default function Topbar() {
    const { session, logout, logoutEverywhere } = useAuth();
    const navigate = useNavigate();
    const toast = useToast();
    const live = useLiveStatus();
    const canInstall = useCanInstall();
    const [passwordOpen, setPasswordOpen] = useState(false);
    const [menuOpen, setMenuOpen] = useState(false);
    const menuRef = useRef(null);

    // close the small-screen menu on outside tap or Escape
    useEffect(() => {
        if (!menuOpen) return;
        const onDown = (e) => { if (!menuRef.current?.contains(e.target)) setMenuOpen(false); };
        const onKey = (e) => { if (e.key === 'Escape') setMenuOpen(false); };
        document.addEventListener('pointerdown', onDown);
        document.addEventListener('keydown', onKey);
        return () => { document.removeEventListener('pointerdown', onDown); document.removeEventListener('keydown', onKey); };
    }, [menuOpen]);

    const run = (fn) => () => { setMenuOpen(false); fn(); };

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

    const install = async () => {
        if (await promptInstall()) toast('📲 App installed. Open it from your home screen.', 'ok');
    };

    return (
        <header className="topbar">
            <div className="brand">
                <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 2C9 6.5 5 10.6 5 15a7 7 0 0 0 14 0c0-4.4-4-8.5-7-13z" /></svg>
                <span>Blood Donor Network</span>
            </div>
            <div className="userbox">
                {canInstall && <button className="btn secondary small install-btn" onClick={install}>📲 Install app</button>}
                {session && (
                    <>
                        <span className={`live-dot ${live ? 'on' : ''}`}
                              title={live ? 'Live updates on' : 'Reconnecting… (data still refreshes every 20 s)'}>
                            {live ? 'Live' : 'Offline'}</span>
                        <span className="who"><b>{session.displayName}</b> · {ROLE_NAME[session.role]}</span>
                        <div className="wide-only">
                            <button className="linkbtn small" onClick={() => setPasswordOpen(true)}>Password</button>
                            <button className="btn secondary small" onClick={signOut}>Log out</button>
                            <button className="linkbtn small" onClick={signOutEverywhere}
                                    title="End every session of this account, on all devices">All devices</button>
                        </div>
                        <div className="menu narrow-only" ref={menuRef}>
                            <button className="btn secondary small menu-btn" aria-haspopup="menu" aria-expanded={menuOpen}
                                    aria-label="Account menu" onClick={() => setMenuOpen(o => !o)}>☰</button>
                            {menuOpen && (
                                <div className="menu-list" role="menu">
                                    <div className="menu-who"><b>{session.displayName}</b><span>{ROLE_NAME[session.role]}</span></div>
                                    <button role="menuitem" onClick={run(() => setPasswordOpen(true))}>🔑 Change password</button>
                                    <button role="menuitem" onClick={run(signOut)}>↩ Log out</button>
                                    <button role="menuitem" onClick={run(signOutEverywhere)}>⏏ Log out on all devices</button>
                                </div>
                            )}
                        </div>
                    </>
                )}
            </div>
            {passwordOpen && <ChangePasswordDialog onClose={() => setPasswordOpen(false)} />}
        </header>
    );
}
