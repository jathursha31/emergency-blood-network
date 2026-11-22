import { useEffect } from 'react';
import { Navigate, Route, Routes } from 'react-router-dom';
import { HOME_BY_ROLE, useAuth } from './auth/AuthContext';
import Topbar from './components/Topbar';
import { useToast } from './components/Toast';
import AuthPage from './pages/AuthPage';
import DonorDashboard from './pages/DonorDashboard';
import HospitalDashboard from './pages/HospitalDashboard';
import AdminDashboard from './pages/AdminDashboard';

/** Only lets users with the given role in; everyone else goes to their own home or to login. */
function RequireRole({ role, children }) {
    const { session } = useAuth();
    if (!session) return <Navigate to="/login" replace />;
    if (session.role !== role) return <Navigate to={HOME_BY_ROLE[session.role]} replace />;
    return children;
}

function Home() {
    const { session } = useAuth();
    return <Navigate to={session ? HOME_BY_ROLE[session.role] : '/login'} replace />;
}

export default function App() {
    const { session, expired } = useAuth();
    const toast = useToast();

    useEffect(() => {
        if (expired) toast('You have been logged out (session expired or ended on another device). Please log in again.', 'error');
    }, [expired, toast]);

    return (
        <>
            <Topbar />
            <main className="container">
                <Routes>
                    <Route path="/" element={<Home />} />
                    <Route path="/login" element={session ? <Home /> : <AuthPage tab="login" />} />
                    <Route path="/signup" element={session ? <Home /> : <AuthPage tab="signup" />} />
                    <Route path="/donor" element={<RequireRole role="DONOR"><DonorDashboard /></RequireRole>} />
                    <Route path="/hospital" element={<RequireRole role="HOSPITAL"><HospitalDashboard /></RequireRole>} />
                    <Route path="/admin" element={<RequireRole role="ADMIN"><AdminDashboard /></RequireRole>} />
                    <Route path="*" element={<Home />} />
                </Routes>
            </main>
        </>
    );
}
