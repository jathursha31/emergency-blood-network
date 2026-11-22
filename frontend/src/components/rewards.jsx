import { useEffect, useState } from 'react';
import { publicApi } from '../api/client';
import { Empty } from './ui';

/** The donor's own badge, progress to the next one, and all badges (earned ones in colour). */
export function RewardsCard({ rewards }) {
    if (!rewards) return null;
    const { badge, nextBadge, donations, livesHelped, donationsToNext, leaderboardRank, allBadges } = rewards;
    const from = badge.minDonations;
    const to = nextBadge ? nextBadge.minDonations : from;
    const pct = nextBadge ? Math.round(100 * (donations - from) / (to - from)) : 100;

    return (
        <section className="card">
            <div className="row" style={{ marginBottom: '.6rem' }}>
                <span className="badge-icon">{badge.icon || '🌱'}</span>
                <div>
                    <h2 style={{ margin: 0 }}>{badge.title}</h2>
                    <div className="small muted">
                        {donations} donation{donations === 1 ? '' : 's'} · helped up to <b>{livesHelped}</b> people
                        {leaderboardRank > 0 && <> · <b>#{leaderboardRank}</b> on the leaderboard</>}
                    </div>
                </div>
            </div>
            {nextBadge ? (
                <>
                    <div className="progress"><div style={{ width: pct + '%' }} /></div>
                    <p className="hint">{donationsToNext} more donation{donationsToNext === 1 ? '' : 's'} to
                        reach {nextBadge.icon} <b>{nextBadge.title}</b></p>
                </>
            ) : <p className="hint">You reached the highest level. Thank you! 🙏</p>}
            <div className="badge-row">
                {allBadges.map(b => (
                    <div key={b.badge} className={`badge-tile ${donations >= b.minDonations ? 'earned' : ''}`}
                         title={`${b.title}: ${b.minDonations} donation${b.minDonations > 1 ? 's' : ''}`}>
                        <span>{b.icon}</span><small>{b.minDonations}</small>
                    </div>
                ))}
            </div>
        </section>
    );
}

/** Public top donors (short names only). */
export function Leaderboard({ limit = 10, highlightRank }) {
    const [rows, setRows] = useState(null);

    useEffect(() => {
        publicApi.leaderboard(limit).then(setRows).catch(() => setRows([]));
    }, [limit]);

    return (
        <section className="card">
            <h2>🏆 Top donors</h2>
            {rows === null ? <Empty>Loading…</Empty> : rows.length === 0 ? <Empty>No donations yet. Be the first!</Empty> : (
                <table>
                    <thead><tr><th>#</th><th>Donor</th><th>Group</th><th>Donations</th></tr></thead>
                    <tbody>
                    {rows.map(r => (
                        <tr key={r.rank + r.displayName} className={r.rank === highlightRank ? 'highlight' : ''}>
                            <td><b>{r.rank}</b></td>
                            <td>{r.badge.icon} {r.displayName}<div className="small muted">{r.city}</div></td>
                            <td><span className="blood sm">{r.bloodGroup}</span></td>
                            <td><b>{r.donations}</b></td>
                        </tr>
                    ))}
                    </tbody>
                </table>
            )}
        </section>
    );
}
