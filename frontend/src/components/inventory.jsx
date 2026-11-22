import { useState } from 'react';
import { inventoryApi } from '../api/client';
import { useLiveEvents } from '../hooks/live';
import { usePolling } from '../hooks/usePolling';
import { useToast } from './Toast';
import { Empty } from './ui';
import { fmtDateTime } from '../utils/format';

const LEVEL = {
    OK: { cls: 'b-green', text: 'OK' },
    LOW: { cls: 'b-amber', text: 'LOW' },
    EMPTY: { cls: 'b-red', text: 'EMPTY' },
};

/** One hospital's blood bank: 8 cards, one per blood group. hospitalId only needed for admins. */
export function BloodBank({ hospitalId, onRequestCreated }) {
    const { data: stock, error, reload } = usePolling(() => inventoryApi.list(hospitalId), 30000);
    const [editing, setEditing] = useState(null);
    useLiveEvents(() => reload(), ['inventory.']);

    if (!stock) return <section className="card"><Empty>{error ? error.message : 'Loading…'}</Empty></section>;

    const total = stock.reduce((n, s) => n + s.units, 0);
    const low = stock.filter(s => s.minUnits > 0 && s.level !== 'OK');

    return (
        <section className="card">
            <div className="card-head">
                <div>
                    <h2 style={{ margin: 0 }}>🏦 Blood bank</h2>
                    <div className="small muted">{total} units in stock
                        {low.length > 0 && <> · <b style={{ color: 'var(--amber)' }}>{low.length} group(s) below minimum</b></>}</div>
                </div>
                <button className="btn secondary small" onClick={reload}>↻ Refresh</button>
            </div>
            <div className="stock-grid">
                {stock.map(s => (
                    <StockCard key={s.bloodGroup} s={s} hospitalId={hospitalId}
                               editing={editing === s.bloodGroup} setEditing={setEditing}
                               onChanged={(updated, before) => {
                                   reload();
                                   if (updated.restockRequestId && updated.restockRequestId !== before.restockRequestId) {
                                       onRequestCreated?.(updated.restockRequestId);
                                   }
                               }} />
                ))}
            </div>
            <p className="hint">When a group falls below its minimum and <b>auto-request</b> is on, a restock request is sent
                to nearby donors automatically. Blood donated for it is added to stock.</p>
        </section>
    );
}

function StockCard({ s, hospitalId, editing, setEditing, onChanged }) {
    const toast = useToast();
    const [units, setUnits] = useState(1);
    const pct = s.minUnits > 0 ? Math.min(100, Math.round(100 * s.units / (s.minUnits * 2))) : (s.units > 0 ? 100 : 0);

    const run = async (fn, okMsg) => {
        try {
            const updated = await fn();
            if (updated.restockRequestId && updated.restockRequestId !== s.restockRequestId) {
                toast(`Stock of ${s.label} is low. Restock request #${updated.restockRequestId} sent to nearby donors.`, 'ok');
            } else if (okMsg) toast(okMsg, 'ok');
            onChanged(updated, s);
        } catch (e) { toast(e.message, 'error'); }
    };

    const saveSettings = (e) => {
        e.preventDefault();
        const f = new FormData(e.currentTarget);
        run(() => inventoryApi.update(s.bloodGroup, {
            units: parseInt(f.get('units'), 10),
            minUnits: parseInt(f.get('minUnits'), 10),
            autoRequest: f.get('autoRequest') === 'on',
        }, hospitalId), `${s.label} updated`).then(() => setEditing(null));
    };

    return (
        <div className={`stock-card level-${s.level.toLowerCase()} ${s.minUnits > 0 && s.level !== 'OK' ? 'alert' : ''}`}>
            <div className="row">
                <span className="blood sm">{s.label}</span>
                <span className="spacer" />
                {s.minUnits > 0 && <span className={`badge ${LEVEL[s.level].cls}`}>{LEVEL[s.level].text}</span>}
            </div>
            <div className="stock-units"><b>{s.units}</b> <span className="small muted">units</span></div>
            <div className={`progress ${s.level !== 'OK' ? 'warn' : ''}`}><div style={{ width: pct + '%' }} /></div>
            <div className="small muted" style={{ marginTop: '.3rem' }}>
                Min {s.minUnits || '—'} · auto-request {s.autoRequest ? 'on' : 'off'}
            </div>
            {s.restockRequestId && <div className="small" style={{ marginTop: '.2rem' }}>🔁 Restock request #{s.restockRequestId} open</div>}

            {editing ? (
                <form onSubmit={saveSettings} className="stock-edit">
                    <label>Units<input name="units" type="number" min={0} defaultValue={s.units} required /></label>
                    <label>Minimum<input name="minUnits" type="number" min={0} defaultValue={s.minUnits} required /></label>
                    <label className="row small" style={{ fontWeight: 600 }}>
                        <input name="autoRequest" type="checkbox" defaultChecked={s.autoRequest} style={{ width: 'auto' }} /> Auto-request
                    </label>
                    <div className="row">
                        <button className="btn small" type="submit">Save</button>
                        <button className="btn secondary small" type="button" onClick={() => setEditing(null)}>Cancel</button>
                    </div>
                </form>
            ) : (
                <div className="row stock-actions">
                    <input type="number" min={1} value={units} onChange={e => setUnits(Math.max(1, parseInt(e.target.value, 10) || 1))}
                           aria-label={`Units of ${s.label}`} />
                    <button className="btn secondary small" disabled={units > s.units}
                            onClick={() => run(() => inventoryApi.use(s.bloodGroup, units, hospitalId), `Issued ${units} unit(s) of ${s.label}`)}>
                        − Use</button>
                    <button className="btn secondary small"
                            onClick={() => run(() => inventoryApi.add(s.bloodGroup, units, hospitalId), `Added ${units} unit(s) of ${s.label}`)}>
                        + Add</button>
                    <button className="linkbtn small" onClick={() => setEditing(s.bloodGroup)}>Settings</button>
                </div>
            )}
            {s.updatedAt && <div className="small muted" style={{ marginTop: '.3rem', fontSize: '.72rem' }}>Updated {fmtDateTime(s.updatedAt)}</div>}
        </div>
    );
}

/** Admin: every hospital's totals and low groups. */
export function StockOverview() {
    const { data, error } = usePolling(inventoryApi.overview, 60000);
    if (!data) return <section className="card"><Empty>{error ? error.message : 'Loading…'}</Empty></section>;
    return (
        <section className="card">
            <h2>🏦 Blood banks</h2>
            <div className="table-wrap">
                <table>
                    <thead><tr><th>Hospital</th><th>Total</th>
                        {data[0]?.stock.map(s => <th key={s.bloodGroup}>{s.label}</th>)}</tr></thead>
                    <tbody>
                    {data.map(h => (
                        <tr key={h.hospitalId}>
                            <td><b>{h.hospitalName}</b><div className="small muted">{h.city}</div></td>
                            <td><b>{h.totalUnits}</b></td>
                            {h.stock.map(s => (
                                <td key={s.bloodGroup} className={s.minUnits > 0 && s.level !== 'OK' ? 'cell-low' : ''}>{s.units}</td>
                            ))}
                        </tr>
                    ))}
                    </tbody>
                </table>
            </div>
            <p className="hint">Highlighted cells are below their minimum.</p>
        </section>
    );
}
