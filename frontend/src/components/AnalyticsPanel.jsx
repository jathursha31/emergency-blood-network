import { api } from '../api/client';
import { useLiveEvents } from '../hooks/live';
import { usePolling } from '../hooks/usePolling';
import { ColumnChart, HBarChart, SERIES, StatTile } from './charts';
import { Empty } from './ui';

const minutes = (m) => m == null ? '—' : m < 60 ? `${Math.round(m)} min` : `${(m / 60).toFixed(1)} h`;
const shortDay = (iso) => new Date(iso + 'T00:00:00').toLocaleDateString('en-IN', { day: 'numeric', month: 'short' });

export default function AnalyticsPanel() {
    const { data, error, reload } = usePolling(() => api('GET', '/api/admin/analytics'), 60000);
    useLiveEvents(() => reload(), ['request.', 'camp.', 'inventory.']);

    if (!data) return <section className="card"><Empty>{error ? error.message : 'Loading…'}</Empty></section>;
    const { kpis: k, byBloodGroup, last14Days, byHospital, requestStatus } = data;

    // Groups where demand is high but few donors can give right now
    const shortage = byBloodGroup
        .filter(g => g.unitsRequested > 0)
        .map(g => ({ ...g, ratio: g.eligibleDonors / g.unitsRequested }))
        .sort((a, b) => a.ratio - b.ratio)[0];

    return (
        <div className="stack">
            <div className="stat-grid">
                <StatTile label="Blood requests" value={k.totalRequests} sub={`${k.activeRequests} active now`} />
                <StatTile label="Fulfilment rate" value={`${k.fulfillmentRatePct}%`} sub={`${k.fulfilledRequests} fulfilled`} />
                <StatTile label="First donor response" value={minutes(k.avgFirstResponseMinutes)} sub="average" />
                <StatTile label="Time to fulfil" value={minutes(k.avgTimeToFulfilMinutes)} sub="average" />
                <StatTile label="Units donated" value={k.unitsDonated} sub="requests + camps" />
                <StatTile label="Donors" value={k.donorsRegistered} sub={`${k.donorsEligibleNow} can donate today`} />
                <StatTile label="In blood banks" value={k.unitsInBloodBanks} sub="units, all hospitals" />
                <StatTile label="Upcoming camps" value={k.upcomingCamps} />
            </div>

            {shortage && shortage.ratio < 1 && (
                <div className="status-box wait">
                    <div className="big-num">⚠</div>
                    <div><b>{shortage.bloodGroup} is the tightest group</b>
                        <span className="small">{shortage.unitsRequested} units requested so far, only {shortage.eligibleDonors} eligible
                            {' '}donor(s) today. Consider a donation camp.</span></div>
                </div>
            )}

            <section className="card">
                <ColumnChart title="Requests in the last 14 days"
                             series={[{ name: 'Created' }, { name: 'Fulfilled' }]}
                             data={last14Days.map(d => ({ label: shortDay(d.date), fullLabel: shortDay(d.date), values: [d.created, d.fulfilled] }))} />
            </section>

            <div className="grid grid-2">
                <section className="card">
                    <HBarChart title="Demand: units requested by blood group"
                               rows={byBloodGroup.map(g => ({ label: g.bloodGroup, value: g.unitsRequested, note: `${g.requests} requests` }))} />
                </section>
                <section className="card">
                    <HBarChart title="Supply: donors who can give today" color={SERIES[1]}
                               rows={byBloodGroup.map(g => ({ label: g.bloodGroup, value: g.eligibleDonors, note: `${g.donors} registered` }))} />
                </section>
            </div>

            <div className="grid grid-2">
                <section className="card">
                    <h3>Requests by hospital</h3>
                    {byHospital.length ? (
                        <div className="table-wrap"><table>
                            <thead><tr><th>Hospital</th><th>Requests</th><th>Fulfilled</th><th>First response</th></tr></thead>
                            <tbody>{byHospital.map(h => (
                                <tr key={h.hospitalId}><td><b>{h.hospitalName}</b></td><td>{h.requests}</td>
                                    <td>{h.fulfilled}</td><td>{minutes(h.avgFirstResponseMinutes)}</td></tr>
                            ))}</tbody>
                        </table></div>
                    ) : <Empty>No requests yet.</Empty>}
                </section>
                <section className="card">
                    <HBarChart title="Requests by status"
                               rows={Object.entries(requestStatus).map(([s, n]) => ({ label: s.charAt(0) + s.slice(1).toLowerCase(), value: n }))} />
                </section>
            </div>
        </div>
    );
}
