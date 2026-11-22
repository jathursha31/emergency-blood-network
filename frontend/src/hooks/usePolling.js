import { useCallback, useEffect, useRef, useState } from 'react';

/**
 * Loads data with `loader()` now and again every `intervalMs` while the tab is visible.
 * Returns { data, error, loading, reload }.
 */
export function usePolling(loader, intervalMs = 20000) {
    const [data, setData] = useState(null);
    const [error, setError] = useState(null);
    const [loading, setLoading] = useState(true);
    const loaderRef = useRef(loader);
    loaderRef.current = loader;

    const reload = useCallback(async () => {
        try {
            setData(await loaderRef.current());
            setError(null);
        } catch (e) {
            setError(e);
        } finally {
            setLoading(false);
        }
    }, []);

    useEffect(() => {
        reload();
        const id = setInterval(() => { if (!document.hidden) reload(); }, intervalMs);
        return () => clearInterval(id);
    }, [reload, intervalMs]);

    return { data, error, loading, reload };
}
