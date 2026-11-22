import { createContext, useCallback, useContext, useRef, useState } from 'react';

const ToastContext = createContext(() => {});

/** Shows one short message at the bottom of the screen. kind: '' | 'ok' | 'error' */
export function ToastProvider({ children }) {
    const [toast, setToast] = useState(null);
    const timer = useRef(null);

    const show = useCallback((message, kind = '') => {
        clearTimeout(timer.current);
        setToast({ message, kind });
        timer.current = setTimeout(() => setToast(null), kind === 'error' ? 6000 : 3500);
    }, []);

    return (
        <ToastContext.Provider value={show}>
            {children}
            <div className={`toast ${toast ? 'show ' + toast.kind : ''}`} role="status" aria-live="polite">
                {toast?.message}
            </div>
        </ToastContext.Provider>
    );
}

export const useToast = () => useContext(ToastContext);
