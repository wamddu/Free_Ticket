import { createContext, startTransition, useCallback, useContext, useEffect, useState, type ReactNode } from "react";
import axios from "axios";
import { useNavigate } from "react-router-dom";
import { getMyInfoApi, loginApi, logoutApi, refreshApi } from "../api/auth";
import { getAuthGeneration } from "../api/token";
import type { LoginRequest, User } from "../types/Auth";

type AuthContextType = {
  user: User | null;
  loading: boolean;
  restoreError: string | null;
  retryRestore: () => Promise<void>;
  login: (data: LoginRequest) => Promise<void>;
  logout: () => Promise<void>;
  setUser: React.Dispatch<React.SetStateAction<User | null>>;
};

const AuthContext = createContext<AuthContextType | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(null);
  const [loading, setLoading] = useState(true);
  const [restoreError, setRestoreError] = useState<string | null>(null);
  const navigate = useNavigate();

  const restoreAuth = useCallback(async () => {
    const generation = getAuthGeneration();
    try {
      await refreshApi();
      if (generation !== getAuthGeneration()) return;
      const response = await getMyInfoApi();
      if (generation === getAuthGeneration()) setUser(response.user);
    } catch (error) {
      if (generation === getAuthGeneration() && !axios.isCancel(error)) {
        setRestoreError(error instanceof Error && !axios.isAxiosError(error) ? error.message
          : "로그인 상태를 확인하지 못했습니다. 잠시 후 다시 시도해 주세요.");
      }
    } finally {
      if (generation === getAuthGeneration()) setLoading(false);
    }
  }, []);

  const retryRestore = () => {
    setLoading(true);
    setRestoreError(null);
    return restoreAuth();
  };

  const login = async (data: LoginRequest) => {
    const pending = loginApi(data);
    const generation = getAuthGeneration();
    try {
      const response = await pending;
      if (generation + 1 === getAuthGeneration()) {
        setUser(response.user);
        setRestoreError(null);
      }
    } finally {
      if (generation === getAuthGeneration() || generation + 1 === getAuthGeneration()) setLoading(false);
    }
  };

  const logout = async () => {
    const pending = logoutApi();
    const generation = getAuthGeneration();
    await pending;
    if (generation + 1 === getAuthGeneration()) {
      alert("로그아웃 되었습니다.");
      startTransition(() => {
        setUser(null);
        setRestoreError(null);
        setLoading(false);
        navigate("/", { replace: true });
      });
    }
  };

  useEffect(() => { void Promise.resolve().then(restoreAuth); }, [restoreAuth]);

  useEffect(() => {
    const handleAuthLogout = () => {
      setUser(null);
      setRestoreError(null);
      setLoading(false);
      if (user) navigate("/", { replace: true });
    };
    window.addEventListener("auth:logout", handleAuthLogout);
    return () => window.removeEventListener("auth:logout", handleAuthLogout);
  }, [navigate, user]);

  return <AuthContext.Provider value={{ user, loading, restoreError, retryRestore, login, logout, setUser }}>{children}</AuthContext.Provider>;
}

// The hook lives with its provider; moving it would change every import and test mock of this module.
// eslint-disable-next-line react-refresh/only-export-components
export function useAuth() {
  const context = useContext(AuthContext);
  if (!context) throw new Error("useAuth는 AuthProvider 내부에서만 사용할 수 있습니다.");
  return context;
}
