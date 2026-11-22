import { useCallback, useEffect, useState } from 'react';
import { hospitalApi, requestApi } from '../api/client';
import { HospitalMap, LocationPicker } from '../components/maps';
import { RequestForm, RequestsPanel } from '../components/requests';
import { useToast } from '../components/Toast';
import { Empty, readForm } from '../components/ui';
import { usePolling } from '../hooks/usePolling';

export default function AdminDashboard() {
    const [tab, setTab] = useState('requests');
    const [hospitals, setHospitals] = useState([]);
    const toast = useToast();

    const loadHospitals = useCallback(() =>
        hospitalApi.list().then(setHospitals).catch(e => toast(e.message, 'error')), [toast]);

    useEffect(() => { loadHospitals(); }, [loadHospitals]);

    return (
        <>
            <div className="tabs">
                <button className={`tab ${tab === 'requests' ? 'active' : ''}`} onClick={() => setTab('requests')}>Blood requests</button>
                <button className={`tab ${tab === 'hospitals' ? 'active' : ''}`} onClick={() => setTab('hospitals')}>Hospitals</button>
            </div>
            {tab === 'requests'
                ? <AdminRequests hospitals={hospitals} />
                : <AdminHospitals hospitals={hospitals} onAdded={loadHospitals} />}
        </>
    );
}

function AdminRequests({ hospitals }) {
    const [selectedId, setSelectedId] = useState(null);
    const { data: requests, error, reload } = usePolling(requestApi.list);

    return (
        <div className="grid grid-side">
            {hospitals.length
                ? <RequestForm isAdmin hospitals={hospitals} onCreated={(r) => { setSelectedId(r.id); reload(); }} />
                : <section className="card"><Empty>Add a hospital first.</Empty></section>}
            {requests
                ? <RequestsPanel requests={requests} hospitals={hospitals} showHospital title="All blood requests"
                                 selectedId={selectedId} setSelectedId={setSelectedId} reload={reload} />
                : <section className="card"><Empty>{error ? error.message : 'Loading…'}</Empty></section>}
        </div>
    );
}

function AdminHospitals({ hospitals, onAdded }) {
    const toast = useToast();
    const [location, setLocation] = useState({ latitude: '', longitude: '' });
    const [busy, setBusy] = useState(false);

    const submit = async (e) => {
        e.preventDefault();
        const form = e.currentTarget;
        const f = readForm(form);
        const body = {
            name: f.name, phone: f.phone, city: f.city || null, address: f.address || null,
            latitude: parseFloat(location.latitude), longitude: parseFloat(location.longitude),
            loginUsername: f.loginUsername, loginPassword: f.loginPassword,
        };
        setBusy(true);
        try {
            const created = await hospitalApi.create(body);
            toast(`${created.name} added. Staff can log in as "${f.loginUsername}".`, 'ok');
            form.reset();
            setLocation({ latitude: '', longitude: '' });
            onAdded();
        } catch (err) {
            toast(err.message, 'error');
        } finally {
            setBusy(false);
        }
    };

    return (
        <div className="grid grid-side">
            <section className="card">
                <h2>Add hospital</h2>
                <form onSubmit={submit}>
                    <div className="field"><label>Hospital name</label><input name="name" required maxLength={150} /></div>
                    <div className="fields-2">
                        <div className="field"><label>Phone</label>
                            <input name="phone" required pattern="[0-9]{10,12}" placeholder="10-12 digits" /></div>
                        <div className="field"><label>City / area</label><input name="city" maxLength={60} /></div>
                    </div>
                    <div className="field"><label>Address</label><input name="address" maxLength={250} /></div>
                    <div className="field"><label>Location: click on the map</label>
                        <LocationPicker value={location} onChange={setLocation} onError={m => toast(m, 'error')} /></div>
                    <h3 style={{ marginTop: '.5rem' }}>Staff login</h3>
                    <div className="fields-2">
                        <div className="field"><label>Username</label>
                            <input name="loginUsername" required pattern="[a-zA-Z0-9._@\-]{3,60}" /></div>
                        <div className="field"><label>Password</label>
                            <input name="loginPassword" type="password" minLength={8} required autoComplete="new-password" /></div>
                    </div>
                    <button className="btn block" type="submit" disabled={busy}>{busy ? 'Adding…' : 'Add hospital'}</button>
                </form>
            </section>
            <section className="card">
                <div className="card-head"><h2>Hospitals ({hospitals.length})</h2></div>
                <HospitalMap hospitals={hospitals} tall />
                <div className="table-wrap" style={{ marginTop: '.75rem' }}>
                    {hospitals.length ? (
                        <table>
                            <thead><tr><th>Name</th><th>City</th><th>Phone</th></tr></thead>
                            <tbody>{hospitals.map(h => (
                                <tr key={h.id}><td><b>{h.name}</b></td><td>{h.city}</td><td>{h.phone}</td></tr>
                            ))}</tbody>
                        </table>
                    ) : <Empty>No hospitals yet.</Empty>}
                </div>
            </section>
        </div>
    );
}
