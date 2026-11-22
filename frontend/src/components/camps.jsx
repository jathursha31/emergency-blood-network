import { useCallback, useEffect, useState } from 'react';
import QRCode from 'qrcode';
import { campApi } from '../api/client';
import { useLiveEvents } from '../hooks/live';
import { usePolling } from '../hooks/usePolling';
import { LocationPicker } from './maps';
import { QrScannerDialog } from './QrScanner';
import { useToast } from './Toast';
import { AsyncButton, Badge, Empty, readForm } from './ui';
import { fmtDate, fmtDateTime } from '../utils/format';

const hhmm = (t) => (t || '').slice(0, 5);
const todayIso = () => {
    const d = new Date();
    d.setMinutes(d.getMinutes() - d.getTimezoneOffset());
    return d.toISOString().slice(0, 10);
};

// ================================================================ donor side

/** Upcoming camps (nearest first when we know the donor's location) with slot booking. */
export function CampsForDonor({ me, onBooked }) {
    const toast = useToast();
    const { data: camps, error, reload } = usePolling(() => campApi.upcoming(me.latitude, me.longitude), 60000);
    const [open, setOpen] = useState(null);

    if (!camps) return <Empty>{error ? error.message : 'Loading…'}</Empty>;
    const sorted = [...camps].sort((a, b) => (a.distanceKm ?? 0) - (b.distanceKm ?? 0));
    if (!sorted.length) return <Empty>No upcoming camps right now. Hospitals near you will announce new ones by SMS.</Empty>;

    const book = async (camp, slot) => {
        try {
            const b = await campApi.book(camp.id, slot.time);
            toast(`Booked ${hhmm(slot.time)} at ${camp.name}. Your code: ${b.code}`, 'ok');
            setOpen(null);
            reload();
            onBooked?.();
        } catch (e) { toast(e.message, 'error'); }
    };

    return (
        <div className="list">
            {sorted.map(c => (
                <div className="item" key={c.id}>
                    <div className="item-top">
                        <div>
                            <b>{c.name}</b>
                            <div className="small muted">{c.hospitalName} · {c.address || c.city}</div>
                        </div>
                        {c.distanceKm != null && <span className="small muted">📍 {c.distanceKm} km</span>}
                    </div>
                    <div className="kv">
                        <span>📅 {fmtDate(c.campDate)}</span>
                        <span>🕘 {hhmm(c.startTime)} – {hhmm(c.endTime)}</span>
                        <span>👥 {c.totalCapacity - c.booked} of {c.totalCapacity} places free</span>
                    </div>
                    {open === c.id ? (
                        <div className="slot-grid">
                            {c.slots.map(s => (
                                <AsyncButton key={s.time} className={`slot ${s.available ? '' : 'full'}`}
                                             disabled={!s.available} onClick={() => book(c, s)}
                                             title={`${s.available} of ${s.capacity} free`}>
                                    {hhmm(s.time)}<small>{s.available ? `${s.available} free` : 'full'}</small>
                                </AsyncButton>
                            ))}
                            <button className="btn secondary small" onClick={() => setOpen(null)}>Close</button>
                        </div>
                    ) : (
                        <div className="row" style={{ marginTop: '.5rem' }}>
                            <span className="spacer" />
                            <button className="btn small" disabled={c.booked >= c.totalCapacity} onClick={() => setOpen(c.id)}>
                                Choose a slot</button>
                        </div>
                    )}
                </div>
            ))}
        </div>
    );
}

/** QR image for a booking code. */
function QrCode({ text, size = 132 }) {
    const [src, setSrc] = useState(null);
    useEffect(() => {
        QRCode.toDataURL(text, { width: size, margin: 1, color: { dark: '#1d2330' } }).then(setSrc).catch(() => setSrc(null));
    }, [text, size]);
    return src ? <img src={src} width={size} height={size} alt={`QR code ${text}`} /> : null;
}

/** The donor's passes: active ones with a QR code, past ones collapsed. */
export function MyCampBookings({ refreshKey }) {
    const toast = useToast();
    const [list, setList] = useState(null);
    const load = () => campApi.myBookings().then(setList).catch(e => toast(e.message, 'error'));
    useEffect(() => { load(); }, [refreshKey]); // eslint-disable-line react-hooks/exhaustive-deps

    if (!list) return null;
    const upcoming = list.filter(b => b.status === 'BOOKED' && b.campStatus === 'ACTIVE' && b.campDate >= todayIso());
    const past = list.filter(b => !upcoming.includes(b));

    const cancel = async (b) => {
        if (!window.confirm(`Cancel your ${hhmm(b.slotTime)} slot at ${b.campName}?`)) return;
        try { await campApi.cancelBooking(b.id); toast('Booking cancelled'); load(); }
        catch (e) { toast(e.message, 'error'); }
    };

    if (!list.length) return null;
    return (
        <div className="list" style={{ marginBottom: '1rem' }}>
            {upcoming.map(b => (
                <div className="pass" key={b.id}>
                    <QrCode text={b.code} />
                    <div>
                        <div className="small muted">Your camp pass</div>
                        <b>{b.campName}</b>
                        <div className="small">{fmtDate(b.campDate)} at <b>{hhmm(b.slotTime)}</b></div>
                        <div className="small muted">{b.hospitalName} · {b.address}</div>
                        <div className="pass-code">{b.code}</div>
                        <AsyncButton className="linkbtn small" onClick={() => cancel(b)}>Cancel booking</AsyncButton>
                    </div>
                </div>
            ))}
            {past.length > 0 && (
                <details>
                    <summary className="small muted">Past camp bookings ({past.length})</summary>
                    <div className="list" style={{ marginTop: '.4rem' }}>
                        {past.map(b => (
                            <div className="item row" key={b.id}>
                                <b>{b.campName}</b><span className="small muted">{fmtDate(b.campDate)} {hhmm(b.slotTime)}</span>
                                <span className="spacer" /><Badge value={b.campStatus === 'CANCELLED' ? 'CANCELLED' : b.status} />
                            </div>
                        ))}
                    </div>
                </details>
            )}
        </div>
    );
}

// ================================================================ hospital side

export function CampsForHospital({ hospital }) {
    const { data: camps, error, reload } = usePolling(campApi.hosted, 30000);
    const [selected, setSelected] = useState(null);
    const toast = useToast();
    useLiveEvents((evt) => {
        if (evt.data?.message && !evt.data.message.startsWith('✅')) toast(evt.data.message, 'ok');
        reload();
    }, ['camp.']);
    const current = camps?.find(c => c.id === selected);

    return (
        <div className="grid grid-side">
            <div className="stack">
                <CheckInCard onDone={reload} />
                {hospital && <NewCampForm hospital={hospital} onCreated={(c) => { setSelected(c.id); reload(); }} />}
            </div>
            <div className="stack">
                <section className="card">
                    <div className="card-head"><h2>⛺ Our camps</h2>
                        <button className="btn secondary small" onClick={reload}>↻ Refresh</button></div>
                    {!camps ? <Empty>{error ? error.message : 'Loading…'}</Empty>
                        : !camps.length ? <Empty>No camps yet. Create one to invite nearby donors.</Empty> : (
                            <div className="list">
                                {camps.map(c => {
                                    const pct = c.totalCapacity ? Math.round(100 * c.booked / c.totalCapacity) : 0;
                                    return (
                                        <div key={c.id} className={`item clickable ${c.id === selected ? 'selected' : ''}`}
                                             onClick={() => setSelected(c.id)}>
                                            <div className="item-top">
                                                <div><b>{c.name}</b> <span className="small muted">#{c.id}</span>
                                                    <div className="small muted">{c.address || c.city}</div></div>
                                                <Badge value={c.status === 'CANCELLED' ? 'CANCELLED'
                                                    : c.campDate < todayIso() ? 'FULFILLED' : 'OPEN'} />
                                            </div>
                                            <div className="kv">
                                                <span>📅 {fmtDate(c.campDate)} · {hhmm(c.startTime)}–{hhmm(c.endTime)}</span>
                                                <span>🎟️ {c.booked}/{c.totalCapacity} booked</span>
                                                <span>✅ {c.attended} donated</span>
                                            </div>
                                            <div className="progress" style={{ marginTop: '.5rem' }}><div style={{ width: pct + '%' }} /></div>
                                        </div>
                                    );
                                })}
                            </div>
                        )}
                </section>
                {current && <CampBookings camp={current} onChanged={reload} onClose={() => setSelected(null)} />}
            </div>
        </div>
    );
}

function CheckInCard({ onDone }) {
    const toast = useToast();
    const [code, setCode] = useState('');
    const [last, setLast] = useState(null);
    const [scanning, setScanning] = useState(false);

    const checkIn = async (value) => {
        const clean = value.trim().toUpperCase();
        if (!/^[A-Z0-9]{8}$/.test(clean)) {
            toast(`"${value.slice(0, 20)}" is not a camp pass code`, 'error');
            return;
        }
        try {
            const row = await campApi.checkIn(clean);
            setLast(row);
            setCode('');
            toast(`${row.donorName} (${row.bloodGroup}) checked in. Donation recorded, +1 unit in stock.`, 'ok');
            onDone();
        } catch (err) { toast(err.message, 'error'); }
    };

    const onScan = useCallback((text) => { setScanning(false); checkIn(text); }, []); // eslint-disable-line react-hooks/exhaustive-deps
    const closeScanner = useCallback(() => setScanning(false), []);

    return (
        <section className="card">
            <h2>✅ Camp check-in</h2>
            <button className="btn block" type="button" onClick={() => setScanning(true)} style={{ marginBottom: '.6rem' }}>
                📷 Scan donor's QR pass</button>
            <form onSubmit={(e) => { e.preventDefault(); checkIn(code); }} className="row">
                <input value={code} onChange={e => setCode(e.target.value.toUpperCase())} placeholder="or type the code"
                       maxLength={12} required style={{ flex: 1, letterSpacing: '.15em', fontWeight: 700 }} />
                <button className="btn secondary" type="submit">Check in</button>
            </form>
            <p className="hint">The 8-character code is printed under the donor's QR code.</p>
            {last && <p className="small">Last: <b>{last.donorName}</b> · {last.bloodGroup} · {hhmm(last.slotTime)} slot</p>}
            {scanning && <QrScannerDialog title="Scan the donor's camp pass" onScan={onScan} onClose={closeScanner} />}
        </section>
    );
}

function NewCampForm({ hospital, onCreated }) {
    const toast = useToast();
    const [location, setLocation] = useState({
        latitude: hospital.latitude.toFixed(5), longitude: hospital.longitude.toFixed(5) });
    const [busy, setBusy] = useState(false);

    const submit = async (e) => {
        e.preventDefault();
        const form = e.currentTarget;
        const f = readForm(form);
        setBusy(true);
        try {
            const camp = await campApi.create({
                name: f.name, address: f.address || null, city: f.city || null,
                latitude: parseFloat(location.latitude), longitude: parseFloat(location.longitude),
                campDate: f.campDate, startTime: f.startTime, endTime: f.endTime,
                slotMinutes: parseInt(f.slotMinutes, 10), capacityPerSlot: parseInt(f.capacityPerSlot, 10),
            });
            toast(`Camp created with ${camp.slots.length} slots. Nearby donors have been invited.`, 'ok');
            form.reset();
            onCreated(camp);
        } catch (err) { toast(err.message, 'error'); }
        finally { setBusy(false); }
    };

    return (
        <section className="card">
            <h2>⛺ New donation camp</h2>
            <form onSubmit={submit}>
                <div className="field"><label>Camp name</label>
                    <input name="name" required maxLength={150} placeholder="e.g. College blood donation drive" /></div>
                <div className="fields-2">
                    <div className="field"><label>Date</label><input name="campDate" type="date" min={todayIso()} required /></div>
                    <div className="field"><label>Area</label><input name="city" maxLength={60} defaultValue={hospital.city} /></div>
                    <div className="field"><label>Starts</label><input name="startTime" type="time" defaultValue="09:00" required /></div>
                    <div className="field"><label>Ends</label><input name="endTime" type="time" defaultValue="13:00" required /></div>
                    <div className="field"><label>Slot length (min)</label>
                        <input name="slotMinutes" type="number" min={10} max={120} defaultValue={30} required /></div>
                    <div className="field"><label>Donors per slot</label>
                        <input name="capacityPerSlot" type="number" min={1} max={50} defaultValue={4} required /></div>
                </div>
                <div className="field"><label>Address</label><input name="address" maxLength={250} /></div>
                <div className="field"><label>Venue: click on the map</label>
                    <LocationPicker value={location} onChange={setLocation} onError={m => toast(m, 'error')} /></div>
                <button className="btn block" type="submit" disabled={busy}>{busy ? 'Creating…' : 'Create camp & invite donors'}</button>
                <p className="hint">Eligible donors within 15 km get an SMS invitation.</p>
            </form>
        </section>
    );
}

function CampBookings({ camp, onChanged, onClose }) {
    const toast = useToast();
    const [rows, setRows] = useState(null);
    const load = () => campApi.bookings(camp.id).then(setRows).catch(e => toast(e.message, 'error'));
    useEffect(() => { load(); }, [camp.id, camp.booked, camp.attended]); // eslint-disable-line react-hooks/exhaustive-deps

    const cancelCamp = async () => {
        if (!window.confirm(`Cancel "${camp.name}"? Everyone who booked gets an SMS.`)) return;
        try { await campApi.cancel(camp.id); toast('Camp cancelled'); onChanged(); }
        catch (e) { toast(e.message, 'error'); }
    };

    return (
        <section className="card">
            <div className="card-head">
                <div><h2 style={{ margin: 0 }}>{camp.name}</h2>
                    <div className="small muted">{fmtDate(camp.campDate)} · {hhmm(camp.startTime)}–{hhmm(camp.endTime)} ·
                        {' '}{camp.slotMinutes}-min slots × {camp.capacityPerSlot}</div></div>
                <div className="row">
                    {camp.status === 'ACTIVE' && camp.campDate >= todayIso() &&
                        <AsyncButton className="btn secondary small" onClick={cancelCamp}>Cancel camp</AsyncButton>}
                    <button className="btn secondary small" onClick={onClose}>✕</button>
                </div>
            </div>
            <div className="slot-bars">
                {camp.slots.map(s => (
                    <div key={s.time} title={`${hhmm(s.time)}: ${s.booked}/${s.capacity}`}>
                        <div className="slot-bar"><div style={{ height: `${100 * s.booked / s.capacity}%` }} /></div>
                        <small>{hhmm(s.time)}</small>
                    </div>
                ))}
            </div>
            {!rows ? <Empty>Loading…</Empty> : !rows.length ? <Empty>No bookings yet.</Empty> : (
                <div className="table-wrap">
                    <table>
                        <thead><tr><th>Slot</th><th>Donor</th><th>Group</th><th>Phone</th><th>Code</th><th>Status</th></tr></thead>
                        <tbody>{rows.map(r => (
                            <tr key={r.id}>
                                <td>{hhmm(r.slotTime)}</td><td><b>{r.donorName}</b></td>
                                <td><span className="blood sm">{r.bloodGroup}</span></td>
                                <td><a href={`tel:${r.phone}`}>{r.phone}</a></td>
                                <td><code>{r.code}</code></td>
                                <td><Badge value={r.status === 'ATTENDED' ? 'DONATED' : r.status === 'BOOKED' ? 'ACCEPTED' : 'CANCELLED'} />
                                    {r.checkedInAt && <div className="small muted">{fmtDateTime(r.checkedInAt)}</div>}</td>
                            </tr>
                        ))}</tbody>
                    </table>
                </div>
            )}
        </section>
    );
}
