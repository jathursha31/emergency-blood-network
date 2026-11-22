export const BLOOD_GROUPS = [
    ['O_NEG', 'O-'], ['O_POS', 'O+'], ['A_NEG', 'A-'], ['A_POS', 'A+'],
    ['B_NEG', 'B-'], ['B_POS', 'B+'], ['AB_NEG', 'AB-'], ['AB_POS', 'AB+'],
];

export const GROUP_LABEL = Object.fromEntries(BLOOD_GROUPS);

export const CHENNAI = [13.0827, 80.2707];

export function fmtDateTime(s) {
    if (!s) return '-';
    const d = new Date(s);
    const opts = { day: 'numeric', month: 'short', hour: 'numeric', minute: '2-digit' };
    if (d.getFullYear() !== new Date().getFullYear()) opts.year = 'numeric';
    return d.toLocaleString('en-IN', opts);
}

export function fmtDate(s) {
    if (!s) return '-';
    return new Date(s.length === 10 ? s + 'T00:00:00' : s)
        .toLocaleDateString('en-IN', { day: 'numeric', month: 'short', year: 'numeric' });
}

export function timeLeft(s) {
    const ms = new Date(s) - Date.now();
    if (ms <= 0) return 'overdue';
    const hrs = Math.floor(ms / 3.6e6);
    if (hrs >= 48) return Math.floor(hrs / 24) + ' days left';
    if (hrs >= 1) return hrs + ' h left';
    return Math.max(1, Math.round(ms / 6e4)) + ' min left';
}

/** Value for an <input type="datetime-local"> some hours from now, in local time. */
export function localDateTimeInput(hoursAhead) {
    const d = new Date(Date.now() + hoursAhead * 3.6e6);
    d.setMinutes(d.getMinutes() - d.getTimezoneOffset());
    return d.toISOString().slice(0, 16);
}

export const isActiveRequest = (status) => status === 'OPEN' || status === 'MATCHED';
