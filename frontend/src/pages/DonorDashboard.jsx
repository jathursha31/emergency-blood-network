import { useState } from 'react';
import { donorApi } from '../api/client';
import { CampsForDonor, MyCampBookings } from '../components/camps';
import { Leaderboard, RewardsCard } from '../components/rewards';
import { useToast } from '../components/Toast';
import { AsyncButton, Badge, BloodDrop, Empty } from '../components/ui';
import { useLiveEvents } from '../hooks/live';
import { usePolling } from '../hooks/usePolling';
import { fmtDate, fmtDateTime, isActiveRequest, timeLeft } from '../utils/format';

async function loadDonorData() {
    const [me, eligibility, inbox, donations, rewards] = await Promise.all([
        donorApi.me(), donorApi.eligibility(), donorApi.inbox(), donorApi.donations(), donorApi.rewards()]);
    return { me, eligibility, inbox, donations, rewards };
}

const TABS = [['requests', '🚨 Requests'], ['camps', '⛺ Camps'], ['leaderboard', '🏆 Leaderboard']];

export default function DonorDashboard() {
    const { data, error, loading, reload } = usePolling(loadDonorData);
    const [tab, setTab] = useState('requests');
    const [bookingsVersion, setBookingsVersion] = useState(0);
    const toast = useToast();

    useLiveEvents((evt) => {
        if (evt.type === 'request.created') toast('🚨 New blood request near you. Can you help?', 'ok');
        if (evt.type === 'camp.created' && evt.data?.message) toast(evt.data.message, 'ok');
        reload();
    }, ['request.', 'camp.']);

    if (loading) return <Empty>Loading…</Empty>;
    if (!data) return <Empty>{error?.message || 'Could not load your data.'}</Empty>;

    const openRequests = data.inbox.filter(i => isActiveRequest(i.requestStatus) && i.myStatus === 'NOTIFIED').length;

    return (
        <div className="grid grid-side">
            <div className="stack">
                <ProfileCard me={data.me} eligibility={data.eligibility} reload={reload} />
                <RewardsCard rewards={data.rewards} />
                <section className="card">
                    <h2>My donations</h2>
                    <Donations list={data.donations} />
                </section>
            </div>
            <div>
                <div className="tabs">
                    {TABS.map(([key, label]) => (
                        <button key={key} className={`tab ${tab === key ? 'active' : ''}`} onClick={() => setTab(key)}>
                            {label}{key === 'requests' && openRequests > 0 && <span className="count">{openRequests}</span>}
                        </button>
                    ))}
                </div>
                {tab === 'requests' && (
                    <section className="card">
                        <div className="card-head">
                            <h2>Requests near me</h2>
                            <button className="btn secondary small" onClick={reload}>↻ Refresh</button>
                        </div>
                        <Inbox items={data.inbox} reload={reload} />
                    </section>
                )}
                {tab === 'camps' && (
                    <section className="card">
                        <MyCampBookings refreshKey={bookingsVersion} />
                        <h2>Upcoming camps</h2>
                        <CampsForDonor me={data.me} onBooked={() => setBookingsVersion(v => v + 1)} />
                    </section>
                )}
                {tab === 'leaderboard' && <Leaderboard limit={20} highlightRank={data.rewards.leaderboardRank} />}
            </div>
        </div>
    );
}

function ProfileCard({ me, eligibility, reload }) {
    const toast = useToast();

    const toggleAvailable = async (e) => {
        const available = e.target.checked;
        try {
            await donorApi.setAvailable(available);
            toast(available ? 'You are available again' : 'You will not get new requests', 'ok');
            reload();
        } catch (err) {
            toast(err.message, 'error');
        }
    };

    return (
        <section className="card">
            <div className="row" style={{ marginBottom: '.75rem' }}>
                <BloodDrop label={me.bloodGroupLabel} />
                <div>
                    <h2 style={{ margin: 0 }}>{me.fullName}</h2>
                    <div className="small muted">{me.city} · {me.phone}</div>
                </div>
            </div>

            {eligibility.eligible ? (
                <div className="status-box ok">
                    <div className="big-num">✓</div>
                    <div><b>You can donate now</b><span className="small">Nearby hospitals can reach you.</span></div>
                </div>
            ) : (
                <div className="status-box wait">
                    <div className="big-num">{eligibility.daysRemaining || '!'}</div>
                    <div>
                        <b>{eligibility.daysRemaining ? 'days until you can donate again' : 'Not available for requests'}</b>
                        {eligibility.reasons.map(r => <span key={r} className="small" style={{ display: 'block' }}>{r}</span>)}
                        {eligibility.daysRemaining > 0 &&
                            <span className="small">Next eligible: <b>{fmtDate(eligibility.nextEligibleDate)}</b></span>}
                    </div>
                </div>
            )}

            <div className="row" style={{ marginTop: '.9rem' }}>
                <label className="switch">
                    <input type="checkbox" checked={me.available} onChange={toggleAvailable} />
                    <span className="track" /><span>Available to donate</span>
                </label>
            </div>
            <p className="hint">Turn this off when you are travelling or unwell. You won't get new requests.</p>
            <p className="small muted" style={{ margin: '.5rem 0 0' }}>Last donation: <b>{fmtDate(me.lastDonationDate)}</b></p>
        </section>
    );
}

function Inbox({ items, reload }) {
    if (!items.length) {
        return <Empty>No requests yet. When a nearby hospital needs your blood group, it will show up here.</Empty>;
    }
    const isActive = i => isActiveRequest(i.requestStatus) && (i.myStatus === 'NOTIFIED' || i.myStatus === 'ACCEPTED');
    const active = items.filter(isActive);
    const past = items.filter(i => !isActive(i));

    return (
        <div className="list">
            {active.length
                ? active.map(i => <InboxItem key={i.requestId} item={i} active reload={reload} />)
                : <Empty>No open requests for you right now.</Empty>}
            {past.length > 0 && (
                <details style={{ marginTop: '.5rem' }}>
                    <summary className="small muted">Past requests ({past.length})</summary>
                    <div className="list" style={{ marginTop: '.5rem' }}>
                        {past.map(i => <InboxItem key={i.requestId} item={i} reload={reload} />)}
                    </div>
                </details>
            )}
        </div>
    );
}

function InboxItem({ item: i, active, reload }) {
    const toast = useToast();
    const canAccept = active && i.myStatus === 'NOTIFIED' && i.requestStatus === 'OPEN';
    const alreadyFilled = active && i.myStatus === 'NOTIFIED' && i.requestStatus === 'MATCHED';

    const accept = async () => {
        try {
            await donorApi.accept(i.requestId);
            toast('Thank you! The hospital has your number now.', 'ok');
            reload();
        } catch (e) { toast(e.message, 'error'); }
    };
    const decline = async () => {
        if (!window.confirm('Decline this request?')) return;
        try {
            await donorApi.decline(i.requestId);
            toast('Declined');
            reload();
        } catch (e) { toast(e.message, 'error'); }
    };

    return (
        <div className={`item ${i.urgency === 'CRITICAL' && active ? 'urgent' : ''}`}>
            <div className="item-top">
                <div>
                    <div className="row"><b>{i.hospitalName}</b> <Badge value={i.urgency} /></div>
                    <div className="small muted">{i.hospitalAddress}</div>
                </div>
                <BloodDrop small label={i.bloodGroupNeeded} />
            </div>
            <div className="kv">
                <span>📍 {i.distanceKm} km away</span>
                <span>⏰ Needed by {fmtDateTime(i.neededBy)}{active && ` (${timeLeft(i.neededBy)})`}</span>
                <span>Request #{i.requestId}</span>
            </div>
            <div className="row" style={{ marginTop: '.6rem' }}>
                <Badge value={i.myStatus} />
                <span className="small muted">request {i.requestStatus.toLowerCase()}</span>
                <span className="spacer" />
                {i.hospitalPhone && <a className="btn secondary small" href={`tel:${i.hospitalPhone}`}>📞 {i.hospitalPhone}</a>}
                {canAccept && <AsyncButton className="btn green small" onClick={accept}>I can donate</AsyncButton>}
                {active && <AsyncButton className="btn secondary small" onClick={decline}>
                    {i.myStatus === 'ACCEPTED' ? "I can't come" : 'Decline'}</AsyncButton>}
            </div>
            {alreadyFilled && <p className="hint">Enough donors have already accepted. Thank you!</p>}
            {active && i.myStatus === 'ACCEPTED' &&
                <p className="hint">Please call the hospital and go there before the deadline. They will mark your donation once done.</p>}
        </div>
    );
}

function Donations({ list }) {
    if (!list.length) return <Empty>No donations recorded yet.</Empty>;
    return (
        <div className="list">
            {list.map((d, i) => (
                <div className="item" key={(d.requestId ?? 'camp') + '-' + i}>
                    <div className="row"><b>{d.hospitalName}</b><span className="spacer" />
                        <span className="small muted">{fmtDate(d.donatedAt)}</span></div>
                    <div className="small muted">{d.requestId
                        ? <>For a {d.patientBloodGroup} patient · request #{d.requestId}</>
                        : <>⛺ Donation camp · blood went to the hospital's blood bank</>}</div>
                </div>
            ))}
            <p className="small muted" style={{ margin: '.25rem 0 0' }}>
                🎉 {list.length} life-saving donation{list.length > 1 ? 's' : ''} through this network</p>
        </div>
    );
}
