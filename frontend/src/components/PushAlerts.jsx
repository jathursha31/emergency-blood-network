import { useEffect, useState } from 'react';
import { pushApi } from '../api/client';
import { currentSubscription, disablePush, enablePush, isIos, isStandalone, pushSupported } from '../pwa';
import { useToast } from './Toast';

/** Donor card: turn phone/desktop notifications for new requests and camps on or off for this device. */
export default function PushAlerts() {
    const toast = useToast();
    const [state, setState] = useState('loading'); // loading | unsupported | ios-install | off | on | blocked
    const [busy, setBusy] = useState(false);

    useEffect(() => {
        let alive = true;
        (async () => {
            let next;
            if (!pushSupported()) next = isIos() && !isStandalone() ? 'ios-install' : 'unsupported';
            else if (Notification.permission === 'denied') next = 'blocked';
            else next = (await currentSubscription().catch(() => null)) && Notification.permission === 'granted' ? 'on' : 'off';
            if (alive) setState(next);
        })();
        return () => { alive = false; };
    }, []);

    const turnOn = async () => {
        setBusy(true);
        try {
            await enablePush();
            setState('on');
            toast('🔔 Alerts are on for this device.', 'ok');
        } catch (e) {
            if (Notification.permission === 'denied') setState('blocked');
            toast(e.message, 'error');
        } finally { setBusy(false); }
    };

    const turnOff = async () => {
        setBusy(true);
        try {
            await disablePush();
            setState('off');
            toast('Alerts turned off for this device.', 'ok');
        } catch (e) { toast(e.message, 'error'); } finally { setBusy(false); }
    };

    const test = async () => {
        setBusy(true);
        try {
            const { delivered } = await pushApi.test();
            toast(delivered ? `Test alert sent to ${delivered} device${delivered === 1 ? '' : 's'}.` : 'No device accepted the alert.',
                delivered ? 'ok' : 'error');
        } catch (e) { toast(e.message, 'error'); } finally { setBusy(false); }
    };

    if (state === 'loading' || state === 'unsupported') return null;

    return (
        <section className={`card push-card ${state === 'on' ? 'on' : ''}`}>
            <div className="push-head">
                <span className="push-icon" aria-hidden="true">{state === 'on' ? '🔔' : '🔕'}</span>
                <div>
                    <h2>Emergency alerts</h2>
                    <p className="muted small">
                        {state === 'on' && 'On for this device. You\'ll be alerted when someone near you needs your blood group.'}
                        {state === 'off' && 'Get a notification on this device the moment a nearby hospital needs your blood group, even when the app is closed.'}
                        {state === 'blocked' && 'Notifications are blocked for this site. Allow them in your browser\'s site settings, then reload.'}
                        {state === 'ios-install' && 'On iPhone, tap Share → Add to Home Screen, open the app from there, then turn alerts on.'}
                    </p>
                </div>
            </div>
            {state === 'off' && <button className="btn" disabled={busy} onClick={turnOn}>🔔 Turn on alerts</button>}
            {state === 'on' && (
                <div className="push-actions">
                    <button className="btn secondary small" disabled={busy} onClick={test}>Send test alert</button>
                    <button className="linkbtn small" disabled={busy} onClick={turnOff}>Turn off</button>
                </div>
            )}
        </section>
    );
}
