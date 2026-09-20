import { useAuth0 } from '@auth0/auth0-react';
import {
  LayoutDashboard,
  Calendar,
  MessageSquare,
  DollarSign,
  FileText,
  Image,
  Settings,
} from 'lucide-react';
import { useEffect } from 'react';
import { Route, Routes, useLocation, useNavigate } from 'react-router-dom';

import {
  AuthCallback,
  ProtectedRoute,
  OnboardingGuard,
  IdleTimeoutWarning,
} from './components/auth';
import { UpdateNotification, OfflineIndicator, InstallPrompt } from './components/pwa';
import type { NavigationItem } from './components/shell';
import { AppShell } from './components/shell';
import { useApiClient } from './hooks/api';
import { useIdleTimeout } from './hooks/useIdleTimeout';
import { initDB, initSync } from './lib/pwa';
import { clearIdleActivity } from './lib/auth/idleActivity';
import AcceptInvitePage from './pages/AcceptInvitePage';
import CalendarPage from './pages/CalendarPage';
import DashboardPage from './pages/DashboardPage';
import DocumentsPage from './pages/DocumentsPage';
import ExpensesPage from './pages/ExpensesPage';
import FamilySetupPage from './pages/FamilySetupPage';
import LoginPage from './pages/LoginPage';
import MessagesPage from './pages/MessagesPage';
import OnboardingPage from './pages/OnboardingPage';
import SettingsPage from './pages/SettingsPage';
import TimelinePage from './pages/TimelinePage';

const App = () => {
  const location = useLocation();
  const navigate = useNavigate();

  // Initialize PWA features
  useEffect(() => {
    // Initialize IndexedDB
    initDB().catch((error) => {
      console.error('Failed to initialize IndexedDB:', error);
    });

    // Initialize background sync
    initSync();
  }, []);

  const { user: authUser, logout, isAuthenticated } = useAuth0();
  useApiClient();

  const user = authUser
    ? {
        name: authUser.name || authUser.email || 'Parent',
        avatarUrl: authUser.picture,
      }
    : undefined;

  const handleLogout = () => {
    clearIdleActivity();
    logout({ logoutParams: { returnTo: window.location.origin } });
  };

  const configuredTimeoutMinutes = Number(import.meta.env.VITE_IDLE_TIMEOUT_MINUTES);
  const idleTimeoutMinutes = Number.isFinite(configuredTimeoutMinutes)
    ? Math.max(1, configuredTimeoutMinutes)
    : import.meta.env.DEV
      ? 3
      : 30;
  const idleTimeoutMs = idleTimeoutMinutes * 60 * 1000;
  const warningTimeMs = 60 * 1000;
  const showIdleCountdown =
    import.meta.env.DEV || import.meta.env.VITE_IDLE_TIMEOUT_SHOW_COUNTDOWN === 'true';

  const { showWarning, remainingSeconds, resetTimer } = useIdleTimeout({
    timeout: idleTimeoutMs,
    warningTime: warningTimeMs,
    onTimeout: handleLogout,
    enabled: isAuthenticated,
  });

  const handleNavigate = (href: string) => {
    navigate(href);
  };

  const navigationItems: NavigationItem[] = [
    {
      label: 'Dashboard',
      href: '/dashboard',
      icon: <LayoutDashboard className="h-5 w-5" />,
      isActive: location.pathname === '/dashboard' || location.pathname === '/',
    },
    {
      label: 'Calendar',
      href: '/calendar',
      icon: <Calendar className="h-5 w-5" />,
      isActive: location.pathname === '/calendar',
    },
    {
      label: 'Messages',
      href: '/messages',
      icon: <MessageSquare className="h-5 w-5" />,
      isActive: location.pathname === '/messages',
    },
    {
      label: 'Expenses',
      href: '/expenses',
      icon: <DollarSign className="h-5 w-5" />,
      isActive: location.pathname === '/expenses',
    },
    {
      label: 'Documents',
      href: '/documents',
      icon: <FileText className="h-5 w-5" />,
      isActive: location.pathname === '/documents',
    },
    {
      label: 'Timeline',
      href: '/timeline',
      icon: <Image className="h-5 w-5" />,
      isActive: location.pathname === '/timeline',
    },
    {
      label: 'Settings',
      href: '/settings',
      icon: <Settings className="h-5 w-5" />,
      isActive: location.pathname === '/settings',
    },
  ];

  return (
    <>
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route path="/auth/callback" element={<AuthCallback />} />
        <Route path="/invitations/accept" element={<AcceptInvitePage />} />
        <Route
          path="/*"
          element={
            <ProtectedRoute>
              <OnboardingGuard>
                <AppShell
                  navigationItems={navigationItems}
                  user={user}
                  onNavigate={handleNavigate}
                  onLogout={handleLogout}
                  showIdleCountdown={showIdleCountdown}
                  idleCountdownSeconds={remainingSeconds}
                >
                  <Routes>
                    <Route path="/" element={<DashboardPage />} />
                    <Route path="/dashboard" element={<DashboardPage />} />
                    <Route path="/calendar" element={<CalendarPage />} />
                    <Route path="/messages" element={<MessagesPage />} />
                    <Route path="/expenses" element={<ExpensesPage />} />
                    <Route path="/documents" element={<DocumentsPage />} />
                    <Route path="/timeline" element={<TimelinePage />} />
                    <Route path="/settings" element={<SettingsPage />} />
                    <Route path="/onboarding" element={<OnboardingPage />} />
                    <Route path="/family-setup" element={<FamilySetupPage />} />
                  </Routes>
                </AppShell>
              </OnboardingGuard>
            </ProtectedRoute>
          }
        />
      </Routes>

      {isAuthenticated && (
        <>
          <UpdateNotification />
          <OfflineIndicator />
          <InstallPrompt />
          <IdleTimeoutWarning
            isOpen={showWarning}
            remainingSeconds={remainingSeconds}
            onStayLoggedIn={resetTimer}
            onLogout={handleLogout}
          />
        </>
      )}
    </>
  );
};

export default App;
