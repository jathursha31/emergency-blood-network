import { donorApi } from '../api/client';
import { useToast } from '../components/Toast';
import { AsyncButton, Badge, BloodDrop, Empty } from '../components/ui';
import { usePolling } from '../hooks/usePolling';
import { fmtDate, fmtDateTime, isActiveRequest, timeLeft } from '../utils/format';

async function loadDonorData() {
    const [me, eligibility, inbox, donations] = await Promise.all([
        donorApi.me(), donorApi.eligibility(), donorApi.inbox(), donorApi.donations()]);
    return { me, eligibility, inbox, donations };
}

export default function DonorDashboard() {
    const { data, error, loading, reload } = usePolling(loadDonorData);

    if (loading) return <Empty>Loading…</Empty>;
    if (!data) return <Empty>{error?.message || 'Could not load your data.'}</Empty>;

    return (
        <div className="grid grid-side">
            <div className="stack">
                <ProfileCard me={data.me} eligibility={data.eligibility} reload={reload} />
                <section className="card">
                    <h2>My donations</h2>
                    <Donations list={data.donations} />
                </section>
            </div>
            <section className="card">
                <div className="card-head">
                    <h2>Requests near me</h2>
                    <button className="btn secondary small" onClick={reload}>↻ Refresh</button>
                </div>
                <Inbox items={data.inbox} reload={reload} />
            </section>
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
            {list.map(d => (
                <div className="item" key={d.requestId}>
                    <div className="row"><b>{d.hospitalName}</b><span className="spacer" />
                        <span className="small muted">{fmtDate(d.donatedAt)}</span></div>
                    <div className="small muted">For a {d.patientBloodGroup} patient · request #{d.requestId}</div>
                </div>
            ))}
            <p className="small muted" style={{ margin: '.25rem 0 0' }}>
                🎉 {list.length} life-saving donation{list.length > 1 ? 's' : ''} through this network</p>
        </div>
    );
}
