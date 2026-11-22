import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import { authApi, donorApi, hospitalApi, setToken, setUnauthorizedHandler } from '../api/client';

const STORAGE_KEY = 'bdn.session';
const AuthContext = createContext(null);

function loadSession() {
    try {
        const s = JSON.parse(localStorage.getItem(STORAGE_KEY));
        if (s && s.expiresAt && new Date(s.expiresAt) > new Date()) return s;
    } catch { /* storage unavailable */ }
    return null;
}

function persist(session) {
    try {
        if (session) localStorage.setItem(STORAGE_KEY, JSON.stringify(session));
        else localStorage.removeItem(STORAGE_KEY);
    } catch { /* storage unavailable: session lives in memory only */ }
}

/** Where each role lands after logging in. */
export const HOME_BY_ROLE = { DONOR: '/donor', HOSPITAL: '/hospital', ADMIN: '/admin' };

export function AuthProvider({ children }) {
    const [session, setSession] = useState(() => {
        const s = loadSession();
        setToken(s?.accessToken ?? null);
        return s;
    });
    const [expired, setExpired] = useState(false);

    const logout = useCallback(() => {
        setToken(null);
        persist(null);
        setSession(null);
    }, []);

    useEffect(() => {
        setUnauthorizedHandler(() => { setExpired(true); logout(); });
    }, [logout]);

    /** Store the token from /login or /register, then fetch a friendly display name. */
    const startSession = useCallback(async (t) => {
        setToken(t.accessToken);
        const s = {
            accessToken: t.accessToken, username: t.username, role: t.role,
            donorId: t.donorId, hospitalId: t.hospitalId, expiresAt: t.expiresAt,
            displayName: t.username,
        };
        try {
            if (t.role === 'DONOR') s.displayName = (await donorApi.me()).fullName;
            if (t.role === 'HOSPITAL') s.displayName = (await hospitalApi.get(t.hospitalId)).name;
        } catch { /* name is cosmetic */ }
        persist(s);
        setExpired(false);
        setSession(s);
        return s;
    }, []);

    const login = useCallback(async (username, password) =>
        startSession(await authApi.login(username, password)), [startSession]);

    const register = useCallback(async (donor, password, otpCode) =>
        startSession(await authApi.register(donor, password, otpCode)), [startSession]);

    const loginWithOtp = useCallback(async (phone, code) =>
        startSession(await authApi.otpLogin(phone, code)), [startSession]);

    const value = useMemo(() => ({ session, login, loginWithOtp, register, logout, expired }),
        [session, login, loginWithOtp, register, logout, expired]);

    return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
    const ctx = useContext(AuthContext);
    if (!ctx) throw new Error('useAuth must be used inside <AuthProvider>');
    return ctx;
}
