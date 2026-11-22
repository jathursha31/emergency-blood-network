import { useEffect, useState } from 'react';
import { authApi } from '../api/client';
import { useToast } from './Toast';

/**
 * "Send code" button + 6-digit code input for a phone number.
 * In local development the server returns the code (SMS are only logged), and we show it.
 */
export function OtpField({ phone, purpose, code, setCode }) {
    const toast = useToast();
    const [sentTo, setSentTo] = useState(null);
    const [devCode, setDevCode] = useState(null);
    const [cooldown, setCooldown] = useState(0);
    const [busy, setBusy] = useState(false);

    useEffect(() => {
        if (cooldown <= 0) return undefined;
        const t = setTimeout(() => setCooldown(c => c - 1), 1000);
        return () => clearTimeout(t);
    }, [cooldown]);

    // a different number needs a new code
    useEffect(() => { setSentTo(null); setDevCode(null); }, [phone]);

    const valid = /^[6-9]\d{9}$/.test(phone || '');

    const send = async () => {
        setBusy(true);
        try {
            const res = await authApi.requestOtp(phone, purpose);
            setSentTo(res.sentTo);
            setDevCode(res.devCode);
            setCooldown(30);
            toast(`Code sent to ${res.sentTo}`, 'ok');
        } catch (e) { toast(e.message, 'error'); }
        finally { setBusy(false); }
    };

    return (
        <div className="otp-box">
            <div className="row">
                <input className="otp-input" value={code} onChange={e => setCode(e.target.value.replace(/\D/g, '').slice(0, 6))}
                       placeholder="6-digit code" inputMode="numeric" autoComplete="one-time-code" required
                       aria-label="One-time code" />
                <button type="button" className="btn secondary" disabled={!valid || busy || cooldown > 0} onClick={send}>
                    {cooldown > 0 ? `Resend in ${cooldown}s` : sentTo ? 'Resend code' : 'Send code'}
                </button>
            </div>
            <div className="hint">
                {!valid ? 'Enter your 10-digit mobile number first.'
                    : sentTo ? <>We sent a code to <b>{sentTo}</b>. It expires in 5 minutes.</>
                        : 'We will send a 6-digit code by SMS.'}
            </div>
            {devCode && (
                <div className="dev-note">
                    🧪 Dev mode (SMS are only logged): your code is <b>{devCode}</b>
                    <button type="button" className="linkbtn" onClick={() => setCode(devCode)}>Fill it</button>
                </div>
            )}
        </div>
    );
}
