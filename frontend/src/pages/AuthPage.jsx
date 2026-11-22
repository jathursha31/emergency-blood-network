import { useEffect, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { publicApi } from '../api/client';
import { HOME_BY_ROLE, useAuth } from '../auth/AuthContext';
import { LocationPicker } from '../components/maps';
import { useToast } from '../components/Toast';
import { BloodGroupSelect, readForm } from '../components/ui';
import { BLOOD_GROUPS } from '../utils/format';

const DEMO_ACCOUNTS = [
    { label: '🏥 Hospital: City Care Hospital, T. Nagar', username: '04420001111', password: 'hospital123' },
    { label: '🩸 Donor: Arun Kumar, B+', username: '9840000001', password: 'donor123' },
    { label: '🛠️ Admin', username: 'admin', password: 'admin123' },
];

export default function AuthPage({ tab }) {
    return (
        <div className="auth-wrap">
            <section className="hero">
                <h1>Find blood donors nearby, in minutes.</h1>
                <p>Hospitals raise an emergency request. Eligible, blood-compatible donors near the hospital are
                    notified at once, and the search widens automatically if nobody responds.</p>
                <ul className="features">
                    <li>Blood compatibility built in (a B+ patient gets B+, B-, O+, O- donors)</li>
                    <li>Only donors who waited 90 days since their last donation are contacted</li>
                    <li>Phone numbers stay hidden until a donor accepts</li>
                </ul>
                <CompatibilityChecker />
            </section>

            <section className="card">
                <div className="tabs">
                    <Link to="/login" className={`tab ${tab === 'login' ? 'active' : ''}`}>Log in</Link>
                    <Link to="/signup" className={`tab ${tab === 'signup' ? 'active' : ''}`}>Become a donor</Link>
                </div>
                {tab === 'login' ? <LoginForm /> : <SignupForm />}
            </section>
        </div>
    );
}

function CompatibilityChecker() {
    const [group, setGroup] = useState('B_POS');
    const [info, setInfo] = useState(null);

    useEffect(() => {
        publicApi.compatibility(group).then(setInfo).catch(() => setInfo(null));
    }, [group]);

    return (
        <div className="card">
            <h3>Who can donate to whom?</h3>
            <p className="small muted">Pick the patient's blood group:</p>
            <div className="compat">
                {BLOOD_GROUPS.map(([v, l]) => (
                    <button key={v} className={v === group ? 'active' : ''} onClick={() => setGroup(v)}>{l}</button>
                ))}
            </div>
            {info && (
                <p className="small" style={{ margin: '.75rem 0 0' }}>
                    <b>{info.label}</b> patient can receive from: <b>{info.canReceiveFrom.join(', ')}</b><br />
                    <b>{info.label}</b> donor can give to: <b>{info.canDonateTo.join(', ')}</b>
                </p>
            )}
        </div>
    );
}

function LoginForm() {
    const { login } = useAuth();
    const toast = useToast();
    const navigate = useNavigate();
    const [username, setUsername] = useState('');
    const [password, setPassword] = useState('');
    const [busy, setBusy] = useState(false);

    const submit = async (e) => {
        e.preventDefault();
        setBusy(true);
        try {
            const s = await login(username.trim(), password);
            navigate(HOME_BY_ROLE[s.role], { replace: true });
        } catch (err) {
            toast(err.message, 'error');
        } finally {
            setBusy(false);
        }
    };

    return (
        <>
            <form onSubmit={submit}>
                <div className="field">
                    <label htmlFor="u">Username</label>
                    <input id="u" value={username} onChange={e => setUsername(e.target.value)}
                           autoComplete="username" required placeholder="Phone number or username" />
                </div>
                <div className="field">
                    <label htmlFor="p">Password</label>
                    <input id="p" type="password" value={password} onChange={e => setPassword(e.target.value)}
                           autoComplete="current-password" required />
                </div>
                <button className="btn block" type="submit" disabled={busy}>{busy ? 'Logging in…' : 'Log in'}</button>
            </form>
            <p className="small muted" style={{ marginTop: '1rem' }}>Demo accounts from the sample data (click to fill):</p>
            <div className="demo">
                {DEMO_ACCOUNTS.map(a => (
                    <button key={a.username} className="btn secondary small"
                            onClick={() => { setUsername(a.username); setPassword(a.password); }}>{a.label}</button>
                ))}
            </div>
        </>
    );
}

function SignupForm() {
    const { register } = useAuth();
    const toast = useToast();
    const navigate = useNavigate();
    const [location, setLocation] = useState({ latitude: '', longitude: '' });
    const [busy, setBusy] = useState(false);

    const submit = async (e) => {
        e.preventDefault();
        const f = readForm(e.currentTarget);
        const donor = {
            fullName: f.fullName, phone: f.phone, email: f.email || null, dateOfBirth: f.dateOfBirth,
            bloodGroup: f.bloodGroup, latitude: parseFloat(location.latitude), longitude: parseFloat(location.longitude),
            city: f.city || null, lastDonationDate: f.lastDonationDate || null,
        };
        setBusy(true);
        try {
            await register(donor, f.password);
            toast('Welcome! You are registered as a donor.', 'ok');
            navigate('/donor', { replace: true });
        } catch (err) {
            toast(err.message, 'error');
        } finally {
            setBusy(false);
        }
    };

    return (
        <form onSubmit={submit}>
            <div className="fields-2">
                <div className="field"><label>Full name</label><input name="fullName" required maxLength={100} /></div>
                <div className="field"><label>Mobile number</label>
                    <input name="phone" required pattern="[6-9][0-9]{9}" placeholder="10 digits" inputMode="numeric" /></div>
                <div className="field"><label>Date of birth</label><input name="dateOfBirth" type="date" required /></div>
                <div className="field"><label>Blood group</label><BloodGroupSelect name="bloodGroup" /></div>
                <div className="field"><label>City / area</label><input name="city" maxLength={60} placeholder="e.g. Adyar" /></div>
                <div className="field"><label>Last donation (optional)</label><input name="lastDonationDate" type="date" /></div>
            </div>
            <div className="field">
                <label>Your location: click on the map</label>
                <LocationPicker value={location} onChange={setLocation} onError={m => toast(m, 'error')} />
            </div>
            <div className="fields-2">
                <div className="field"><label>Email (optional)</label><input name="email" type="email" /></div>
                <div className="field"><label>Password</label>
                    <input name="password" type="password" minLength={8} required autoComplete="new-password" /></div>
            </div>
            <button className="btn block" type="submit" disabled={busy}>{busy ? 'Registering…' : 'Register as donor'}</button>
            <p className="hint">You will log in with your mobile number.</p>
        </form>
    );
}
