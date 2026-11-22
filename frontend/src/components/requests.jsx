import { useState } from 'react';
import { requestApi } from '../api/client';
import { useAuth } from '../auth/AuthContext';
import { ChatButton, ChatDialog, useChatUnread } from './Chat';
import { HospitalMap } from './maps';
import { useToast } from './Toast';
import { AsyncButton, Badge, BloodDrop, BloodGroupSelect, Empty, readForm } from './ui';
import { GROUP_LABEL, fmtDateTime, isActiveRequest, localDateTimeInput, timeLeft } from '../utils/format';

// ---------------------------------------------------------------- new request form

/** hospitals: shown as a dropdown only for admins (a hospital always raises requests for itself). */
export function RequestForm({ hospitals, isAdmin, onCreated }) {
    const toast = useToast();
    const [busy, setBusy] = useState(false);

    const submit = async (e) => {
        e.preventDefault();
        const form = e.currentTarget;
        const f = readForm(form);
        const body = {
            patientName: f.patientName, bloodGroup: f.bloodGroup, unitsNeeded: parseInt(f.unitsNeeded, 10),
            urgency: f.urgency, neededBy: f.neededBy,
        };
        if (isAdmin) body.hospitalId = parseInt(f.hospitalId, 10);
        setBusy(true);
        try {
            const r = await requestApi.create(body);
            toast(`Request #${r.id} created. ${r.notifiedCount} donor(s) notified within ${r.searchRadiusKm} km.`, 'ok');
            form.patientName.value = '';
            onCreated(r);
        } catch (err) {
            toast(err.message, 'error');
        } finally {
            setBusy(false);
        }
    };

    return (
        <section className="card">
            <h2>🚨 New emergency request</h2>
            <form onSubmit={submit}>
                {isAdmin && (
                    <div className="field"><label>Hospital</label>
                        <select name="hospitalId" required>
                            {hospitals.map(h => <option key={h.id} value={h.id}>{h.name}</option>)}
                        </select></div>
                )}
                <div className="field"><label>Patient name</label><input name="patientName" required maxLength={100} /></div>
                <div className="fields-2">
                    <div className="field"><label>Blood group needed</label><BloodGroupSelect name="bloodGroup" /></div>
                    <div className="field"><label>Units</label>
                        <input name="unitsNeeded" type="number" min={1} max={10} defaultValue={1} required /></div>
                    <div className="field"><label>Urgency</label>
                        <select name="urgency" defaultValue="HIGH">
                            <option value="CRITICAL">Critical (starts at 20 km)</option>
                            <option value="HIGH">High</option>
                            <option value="NORMAL">Normal</option>
                        </select></div>
                    <div className="field"><label>Needed by</label>
                        <input name="neededBy" type="datetime-local" defaultValue={localDateTimeInput(6)} required /></div>
                </div>
                <button className="btn block" type="submit" disabled={busy}>{busy ? 'Searching…' : 'Find donors'}</button>
                <p className="hint">Eligible compatible donors nearby are notified immediately.</p>
            </form>
        </section>
    );
}

// ---------------------------------------------------------------- list

const FILTERS = [
    ['ACTIVE', 'Active', r => isActiveRequest(r.status)],
    ['FULFILLED', 'Fulfilled', r => r.status === 'FULFILLED'],
    ['CLOSED', 'Cancelled / expired', r => r.status === 'CANCELLED' || r.status === 'EXPIRED'],
    ['ALL', 'All', () => true],
];

export function RequestList({ requests, selectedId, onSelect, showHospital, onRefresh, title = 'Blood requests', unread }) {
    const [filter, setFilter] = useState('ACTIVE');
    const matches = FILTERS.find(f => f[0] === filter)[2];
    const shown = requests.filter(matches);

    return (
        <section className="card">
            <div className="card-head">
                <h2>{title}</h2>
                <button className="btn secondary small" onClick={onRefresh}>↻ Refresh</button>
            </div>
            <div className="chips" style={{ marginBottom: '.75rem' }}>
                {FILTERS.map(([key, label]) => (
                    <button key={key} className={`chip ${key === filter ? 'active' : ''}`} onClick={() => setFilter(key)}>
                        {label}{key === filter ? ` (${shown.length})` : ''}
                    </button>
                ))}
            </div>
            <div className="list">
                {shown.length ? shown.map(r => (
                    <RequestRow key={r.id} r={r} selected={r.id === selectedId} showHospital={showHospital}
                                unread={unread ? unread.total(r.id) : 0} onClick={() => onSelect(r.id)} />
                )) : <Empty>No requests here.</Empty>}
            </div>
        </section>
    );
}

function RequestRow({ r, selected, showHospital, unread, onClick }) {
    const active = isActiveRequest(r.status);
    const pct = Math.min(100, Math.round(100 * r.donatedCount / r.unitsNeeded));
    return (
        <div className={`item clickable ${selected ? 'selected' : ''} ${r.urgency === 'CRITICAL' && active ? 'urgent' : ''}`}
             onClick={onClick}>
            <div className="item-top">
                <div className="row">
                    <BloodDrop small label={r.bloodGroupLabel} />
                    <div><b>{r.patientName}</b> <span className="small muted">#{r.id}</span>
                        {r.restock && <span className="badge b-blue" style={{ marginLeft: '.35rem' }}>🔁 Restock</span>}
                        {showHospital && <div className="small muted">{r.hospitalName}</div>}</div>
                </div>
                <div className="row">{unread > 0 && <span className="chat-unread" title="Unread donor messages">💬 {unread}</span>}<Badge value={r.urgency} /> <Badge value={r.status} /></div>
            </div>
            <div className="kv">
                <span>👥 {r.notifiedCount} notified · {r.acceptedCount} accepted · {r.donatedCount}/{r.unitsNeeded} donated</span>
                <span>📡 {r.searchRadiusKm} km</span>
                <span>⏰ {fmtDateTime(r.neededBy)}{active && ` · ${timeLeft(r.neededBy)}`}</span>
            </div>
            <div className="progress" style={{ marginTop: '.5rem' }}><div style={{ width: pct + '%' }} /></div>
        </div>
    );
}

// ---------------------------------------------------------------- detail

export function RequestDetail({ r, hospital, onClose, onChanged, unread }) {
    const toast = useToast();
    const [chatDonor, setChatDonor] = useState(null);
    const active = isActiveRequest(r.status);

    const cancel = async () => {
        if (!window.confirm(`Cancel request #${r.id}? Donors who accepted will be told not to come.`)) return;
        try {
            await requestApi.cancel(r.id);
            toast('Request cancelled');
            onChanged();
        } catch (e) { toast(e.message, 'error'); }
    };

    const markDonated = async (d) => {
        if (!window.confirm(`Confirm that ${d.donorName} has donated blood?`)) return;
        try {
            const res = await requestApi.markDonated(r.id, d.donorId);
            toast(res.status === 'FULFILLED' ? 'Donation recorded. Request fulfilled! 🎉' : 'Donation recorded', 'ok');
            onChanged();
        } catch (e) { toast(e.message, 'error'); }
    };

    return (
        <section className="card">
            <div className="card-head">
                <div className="row">
                    <BloodDrop label={r.bloodGroupLabel} />
                    <div>
                        <h2 style={{ margin: 0 }}>{r.patientName} <span className="muted small">#{r.id}</span></h2>
                        <div className="small muted">{r.hospitalName} · created {fmtDateTime(r.createdAt)}</div>
                    </div>
                </div>
                <div className="row">
                    <Badge value={r.status} />
                    {active && <AsyncButton className="btn secondary small" onClick={cancel}>Cancel request</AsyncButton>}
                    <button className="btn secondary small" onClick={onClose} title="Close">✕</button>
                </div>
            </div>

            <div className="stats">
                <div className="stat"><b>{r.notifiedCount}</b><span>donors notified</span></div>
                <div className="stat"><b>{r.acceptedCount}</b><span>accepted</span></div>
                <div className="stat"><b>{r.donatedCount}/{r.unitsNeeded}</b><span>units donated</span></div>
            </div>
            <p className="small muted">
                Needed by <b>{fmtDateTime(r.neededBy)}</b>{active && ` (${timeLeft(r.neededBy)})`}
                {' · '}search radius <b>{r.searchRadiusKm} km</b>
                {r.closedAt && ` · closed ${fmtDateTime(r.closedAt)}`}
            </p>

            {hospital && <HospitalMap hospitals={[hospital]} radiusKm={r.searchRadiusKm}
                donors={r.donors.filter(d => d.approxLatitude != null).map(d => ({
                    id: d.donorId, lat: d.approxLatitude, lng: d.approxLongitude, name: d.donorName,
                    status: d.status, donated: d.status === 'DONATED', distanceKm: d.distanceKm,
                }))} />}
            {hospital && r.donors.some(d => d.approxLatitude != null) &&
                <p className="hint">🟢 Donors on their way (approximate location, ~1 km). Updates live.</p>}

            <h3 style={{ marginTop: '1rem' }}>Donors</h3>
            {r.donors.length ? (
                <div className="table-wrap">
                    <table>
                        <thead><tr><th>Donor</th><th>Group</th><th>Distance</th><th>Status</th><th>Phone</th><th /><th /></tr></thead>
                        <tbody>
                        {r.donors.map(d => {
                            const revealed = d.status === 'ACCEPTED' || d.status === 'DONATED';
                            return (
                                <tr key={d.donorId}>
                                    <td><b>{d.donorName}</b></td>
                                    <td><BloodDrop small label={GROUP_LABEL[d.bloodGroup] || d.bloodGroup} /></td>
                                    <td>{d.distanceKm} km</td>
                                    <td><Badge value={d.status} /></td>
                                    <td>{revealed ? <a href={`tel:${d.phone}`}>{d.phone}</a> : <span className="muted">{d.phone}</span>}</td>
                                    <td>{d.status === 'ACCEPTED' && active &&
                                        <AsyncButton className="btn green small" onClick={() => markDonated(d)}>Mark donated</AsyncButton>}</td>
                                    <td>{(revealed || (unread && unread.count(r.id, d.donorId) > 0)) &&
                                        <ChatButton unread={unread ? unread.count(r.id, d.donorId) : 0} onClick={() => setChatDonor(d.donorId)} />}</td>
                                </tr>
                            );
                        })}
                        </tbody>
                    </table>
                </div>
            ) : (
                <Empty>No eligible donors found yet within {r.searchRadiusKm} km.
                    {active && ' The search radius widens automatically every 30 minutes.'}</Empty>
            )}
            {chatDonor && <ChatDialog side="hospital" requestId={r.id} donorId={chatDonor}
                                      onClose={() => { setChatDonor(null); unread?.refresh(); }} />}
        </section>
    );
}

// ---------------------------------------------------------------- list + detail together

/** The requests list with the selected request's detail panel underneath. */
export function RequestsPanel({ requests, hospitals, selectedId, setSelectedId, reload, showHospital, title }) {
    const selected = requests.find(r => r.id === selectedId);
    const { session } = useAuth();
    const toast = useToast();
    const unread = useChatUnread('hospital', (d) => {
        // admins see every hospital's chats: no pop-ups for them
        if (session?.role === 'HOSPITAL') toast(`💬 New message from a donor on request #${d.requestId}`, 'ok');
    });
    return (
        <div className="stack">
            <RequestList requests={requests} selectedId={selectedId} onSelect={setSelectedId}
                         showHospital={showHospital} onRefresh={reload} title={title} unread={unread} />
            {selected && (
                <RequestDetail key={selected.id} r={selected}
                               hospital={hospitals.find(h => h.id === selected.hospitalId)}
                               onClose={() => setSelectedId(null)} onChanged={reload} unread={unread} />
            )}
        </div>
    );
}
