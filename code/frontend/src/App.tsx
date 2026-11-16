import { Routes, Route, Navigate } from 'react-router-dom';

//// Pages

// 기본 페이지
import Homepage from './pages/Homepage';
import SearchPage from './pages/SearchPage';
import Loginpage from './pages/Loginpage';
import TicketDetailPage from './pages/TicketDetailPage';
import QueuePage from './pages/QueuePage';
import PaymentCheckoutPage from './pages/payment/PaymentCheckoutPage';
import PaymentSuccessPage from './pages/payment/PaymentSuccessPage';
import PaymentFailPage from './pages/payment/PaymentFailPage';

// 마이페이지
import Mypage from './pages/mypage/Mypage';
import ReservedTickets from './pages/mypage/ReservedTickets';
import Profile from './pages/mypage/Profile';
import SellingTickets from './pages/mypage/SellingTickets';
import ProfileModify from './pages/mypage/ProfileModify';
import PasswordReset from './pages/mypage/PasswordReset';
import AdminDashboard from './pages/mypage/AdminDashboard';

// 장르별 티켓 페이지
import ConcertPage from './pages/eventpage/ConcertPage';
import MusicalPage from './pages/eventpage/MusicalPage';
import PlayPage from './pages/eventpage/PlayPage';
import ClassicPage from './pages/eventpage/ClassicPage';
import ExhibitionPage from './pages/eventpage/ExhibitionPage';
import BuskingPage from './pages/eventpage/BuskingPage';

//// Components
import Header from './components/Header';
import ProtectedRoute from './components/ProtectedRoute';
import TicketChat from './components/TicketChat';


export default function App() {
  return (
    <div>
      <Header />
      <Routes>
        <Route path="/" element={<Homepage />} />
        <Route path="/search" element={<SearchPage />} />
        <Route path="/concert" element={<ConcertPage />} />
        <Route path="/musical" element={<MusicalPage />} />
        <Route path="/play" element={<PlayPage />} />
        <Route path="/classic" element={<ClassicPage />} />
        <Route path="/exhibition" element={<ExhibitionPage />} />
        <Route path="/busking" element={<BuskingPage />} />

        <Route path="/login" element={<Loginpage />} />
        <Route path="/ticket/:id" element={<TicketDetailPage />} />
        <Route
          path="/queue/:eventId"
          element={
            <ProtectedRoute>
              <QueuePage />
            </ProtectedRoute>
          }
        />
        <Route path="/payment/fail" element={<PaymentFailPage />} />
        <Route
          path="/payment/checkout/:orderId"
          element={
            <ProtectedRoute>
              <PaymentCheckoutPage />
            </ProtectedRoute>
          }
        />
        <Route
          path="/payment/success"
          element={
            <ProtectedRoute>
              <PaymentSuccessPage />
            </ProtectedRoute>
          }
        />
        <Route
          path="/mypage"
          element={
            <ProtectedRoute>
              <Mypage />
            </ProtectedRoute>
          }>
          <Route index element={<Navigate to="tickets" replace />} />
          <Route path="tickets" element={<ReservedTickets />} />
          <Route path="profile" element={<Profile />} />
          <Route path="selling" element={<SellingTickets />} />
          <Route path="profileModify" element={<ProfileModify />} />
          <Route path="passwordreset" element={<PasswordReset />} />
          <Route path="admin" element={<AdminDashboard />} />
        </Route>
      </Routes>
      <TicketChat />
    </div>
  );
}
