import { useNavigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { useLiveStatus } from '../hooks/live';

const ROLE_NAME = { DONOR: 'Donor', HOSPITAL: 'Hospital', ADMIN: 'Admin' };

export default function Topbar() {
    const { session, logout } = useAuth();
    const navigate = useNavigate();
    const live = useLiveStatus();

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
                    <button className="btn secondary small" onClick={() => { logout(); navigate('/login'); }}>Log out</button>
                </div>
            )}
        </header>
    );
}
