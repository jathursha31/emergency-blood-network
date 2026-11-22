import { useEffect, useRef, useState } from 'react';
import QrScannerLib from 'qr-scanner';

/**
 * Full-screen camera overlay that reads one QR code and calls onScan(text).
 * Uses the browser's BarcodeDetector when available, a Web Worker decoder otherwise.
 * Camera access needs https (or localhost).
 */
export function QrScannerDialog({ onScan, onClose, title = 'Scan the QR code' }) {
    const videoRef = useRef(null);
    const scannerRef = useRef(null);
    const [error, setError] = useState(null);
    const [cameras, setCameras] = useState([]);
    const [cameraId, setCameraId] = useState('environment');

    useEffect(() => {
        let cancelled = false;
        const scanner = new QrScannerLib(videoRef.current, (result) => {
            if (cancelled) return;
            cancelled = true;
            scanner.stop();
            onScan(result.data.trim());
        }, {
            returnDetailedScanResult: true,
            highlightScanRegion: true,
            highlightCodeOutline: true,
            preferredCamera: 'environment',
            maxScansPerSecond: 8,
        });
        scannerRef.current = scanner;

        scanner.start()
            .then(() => QrScannerLib.listCameras(true))
            .then(list => { if (!cancelled) setCameras(list); })
            .catch(e => {
                if (cancelled) return;
                const msg = String(e?.message || e);
                setError(/permission|denied|NotAllowed/i.test(msg)
                    ? 'Camera permission was denied. Allow the camera for this site, or type the code instead.'
                    : /no camera|NotFound|not found/i.test(msg)
                        ? 'No camera found on this device. Type the code instead.'
                        : 'Could not start the camera (' + msg + '). Type the code instead.');
            });

        return () => { cancelled = true; scanner.destroy(); scannerRef.current = null; };
    }, [onScan]);

    const switchCamera = async (id) => {
        setCameraId(id);
        try { await scannerRef.current?.setCamera(id); } catch { /* keep current camera */ }
    };

    // Esc closes
    useEffect(() => {
        const onKey = (e) => { if (e.key === 'Escape') onClose(); };
        window.addEventListener('keydown', onKey);
        return () => window.removeEventListener('keydown', onKey);
    }, [onClose]);

    return (
        <div className="scan-backdrop" role="dialog" aria-modal="true" aria-label={title}>
            <div className="scan-panel">
                <div className="row" style={{ marginBottom: '.5rem' }}>
                    <b>📷 {title}</b>
                    <span className="spacer" />
                    <button className="btn secondary small" onClick={onClose}>Close</button>
                </div>
                {error ? <div className="status-box wait"><div className="big-num">!</div><div><span className="small">{error}</span></div></div> : (
                    <>
                        <div className="scan-video"><video ref={videoRef} muted playsInline /></div>
                        <p className="hint">Hold the donor's pass in front of the camera. It checks in automatically.</p>
                        {cameras.length > 1 && (
                            <select value={cameraId} onChange={e => switchCamera(e.target.value)} aria-label="Camera">
                                {cameras.map(c => <option key={c.id} value={c.id}>{c.label || 'Camera'}</option>)}
                            </select>
                        )}
                    </>
                )}
            </div>
        </div>
    );
}
