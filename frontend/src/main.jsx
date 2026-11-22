import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import App from './App';
import { AuthProvider } from './auth/AuthContext';
import { ToastProvider } from './components/Toast';
import { LiveProvider } from './hooks/live';
import './index.css';

// Service worker: install to home screen, offline page, push notifications. Not in `npm run dev`,
// where it would cache Vite's dev files.
if ('serviceWorker' in navigator && import.meta.env.PROD) {
    window.addEventListener('load', () => {
        navigator.serviceWorker.register('/sw.js').catch(e => console.warn('Service worker not registered', e));
    });
}

createRoot(document.getElementById('root')).render(
    <StrictMode>
        <BrowserRouter>
            <ToastProvider>
                <AuthProvider>
                    <LiveProvider>
                        <App />
                    </LiveProvider>
                </AuthProvider>
            </ToastProvider>
        </BrowserRouter>
    </StrictMode>,
);
