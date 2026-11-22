import { useEffect, useState } from 'react';
import { hospitalApi, requestApi } from '../api/client';
import { useAuth } from '../auth/AuthContext';
import { CampsForHospital } from '../components/camps';
import { BloodBank } from '../components/inventory';
import { HospitalMap } from '../components/maps';
import { RequestForm, RequestsPanel } from '../components/requests';
import { useToast } from '../components/Toast';
import { Empty } from '../components/ui';
import { usePolling } from '../hooks/usePolling';

const TABS = [['requests', '🚨 Requests'], ['bank', '🏦 Blood bank'], ['camps', '⛺ Camps']];

export default function HospitalDashboard() {
    const { session } = useAuth();
    const toast = useToast();
    const [tab, setTab] = useState('requests');
    const [hospital, setHospital] = useState(null);
    const [selectedId, setSelectedId] = useState(null);
    const { data: requests, error, reload } = usePolling(requestApi.list);

    useEffect(() => {
        hospitalApi.get(session.hospitalId).then(setHospital).catch(e => toast(e.message, 'error'));
    }, [session.hospitalId, toast]);

    const openRequest = (id) => { setSelectedId(id); reload(); };

    return (
        <>
            <div className="tabs">
                {TABS.map(([key, label]) => (
                    <button key={key} className={`tab ${tab === key ? 'active' : ''}`} onClick={() => setTab(key)}>{label}</button>
                ))}
            </div>

            {tab === 'requests' && (
                <div className="grid grid-side">
                    <div className="stack">
                        <RequestForm onCreated={(r) => openRequest(r.id)} />
                        <section className="card">
                            {hospital ? (
                                <>
                                    <h2>{hospital.name}</h2>
                                    <p className="small muted">{hospital.address || hospital.city} · 📞 {hospital.phone}</p>
                                    <HospitalMap hospitals={[hospital]} />
                                </>
                            ) : <Empty>Loading…</Empty>}
                        </section>
                    </div>
                    {requests
                        ? <RequestsPanel requests={requests} hospitals={hospital ? [hospital] : []}
                                         selectedId={selectedId} setSelectedId={setSelectedId} reload={reload} />
                        : <section className="card"><Empty>{error ? error.message : 'Loading…'}</Empty></section>}
                </div>
            )}

            {tab === 'bank' && <BloodBank onRequestCreated={reload} />}
            {tab === 'camps' && <CampsForHospital hospital={hospital} />}
        </>
    );
}
