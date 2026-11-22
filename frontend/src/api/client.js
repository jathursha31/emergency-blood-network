/**
 * Small fetch wrapper for the Spring Boot API.
 * - adds the JWT as "Authorization: Bearer ..."
 * - turns Spring's ProblemDetail errors into readable messages
 * - calls onUnauthorized() when the token has expired
 */

let token = null;
let onUnauthorized = () => {};

export function setToken(t) {
    token = t;
}

export function setUnauthorizedHandler(fn) {
    onUnauthorized = fn;
}

export class ApiError extends Error {
    constructor(message, status) {
        super(message);
        this.status = status;
    }
}

export async function api(method, path, body) {
    const headers = {};
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    if (token) headers.Authorization = 'Bearer ' + token;

    let res;
    try {
        res = await fetch(path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
    } catch {
        throw new ApiError('Cannot reach the server. Is the Spring Boot app running?', 0);
    }

    const text = await res.text();
    let data = null;
    try { data = text ? JSON.parse(text) : null; } catch { data = text; }

    if (res.status === 401 && token && !path.startsWith('/api/auth/')) {
        onUnauthorized();
        throw new ApiError('Your session has expired. Please log in again.', 401);
    }
    if (!res.ok) {
        let msg = (data && (data.detail || data.title)) || `Request failed (${res.status})`;
        if (data && data.errors) {
            msg += ': ' + Object.entries(data.errors)
                .map(([field, err]) => `${field.replace(/^donor\./, '')} ${err}`).join(', ');
        }
        throw new ApiError(msg, res.status);
    }
    return data;
}

// Typed helpers, one per backend endpoint -------------------------------------------

export const authApi = {
    login: (username, password) => api('POST', '/api/auth/login', { username, password }),
    register: (donor, password) => api('POST', '/api/auth/register', { donor, password }),
};

export const donorApi = {
    me: () => api('GET', '/api/me'),
    eligibility: () => api('GET', '/api/me/eligibility'),
    inbox: () => api('GET', '/api/me/requests'),
    donations: () => api('GET', '/api/me/donations'),
    rewards: () => api('GET', '/api/me/rewards'),
    setAvailable: (available) => api('PATCH', '/api/me/availability', { available }),
    accept: (requestId) => api('POST', `/api/me/requests/${requestId}/accept`),
    decline: (requestId) => api('POST', `/api/me/requests/${requestId}/decline`),
};

export const requestApi = {
    list: () => api('GET', '/api/requests'),
    create: (body) => api('POST', '/api/requests', body),
    cancel: (id) => api('POST', `/api/requests/${id}/cancel`),
    markDonated: (id, donorId) => api('POST', `/api/requests/${id}/donors/${donorId}/donated`),
};

export const campApi = {
    upcoming: (lat, lng) => api('GET', '/api/camps' + (lat != null ? `?lat=${lat}&lng=${lng}` : '')),
    get: (id) => api('GET', `/api/camps/${id}/slots`),
    create: (body) => api('POST', '/api/camps', body),
    hosted: () => api('GET', '/api/camps/hosted'),
    bookings: (id) => api('GET', `/api/camps/${id}/bookings`),
    cancel: (id) => api('POST', `/api/camps/${id}/cancel`),
    checkIn: (code) => api('POST', '/api/camps/check-in', { code }),
    myBookings: () => api('GET', '/api/me/camp-bookings'),
    book: (campId, slotTime) => api('POST', '/api/me/camp-bookings', { campId, slotTime }),
    cancelBooking: (id) => api('POST', `/api/me/camp-bookings/${id}/cancel`),
};

const hq = (hospitalId) => (hospitalId ? `?hospitalId=${hospitalId}` : '');

export const inventoryApi = {
    list: (hospitalId) => api('GET', `/api/inventory${hq(hospitalId)}`),
    overview: () => api('GET', '/api/inventory/overview'),
    update: (group, body, hospitalId) => api('PUT', `/api/inventory/${group}${hq(hospitalId)}`, body),
    use: (group, units, hospitalId) => api('POST', `/api/inventory/${group}/use${hq(hospitalId)}`, { units }),
    add: (group, units, hospitalId) => api('POST', `/api/inventory/${group}/add${hq(hospitalId)}`, { units }),
};

export const hospitalApi = {
    list: () => api('GET', '/api/hospitals'),
    get: (id) => api('GET', `/api/hospitals/${id}`),
    create: (body) => api('POST', '/api/hospitals', body),
};

export const publicApi = {
    compatibility: (group) => api('GET', `/api/blood-groups/${group}`),
    leaderboard: (limit = 10) => api('GET', `/api/leaderboard?limit=${limit}`),
};
