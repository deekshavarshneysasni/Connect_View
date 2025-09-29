import { useState, useEffect } from "react";
import "./App.css";
import Dashboard from "./Dashboard";
import OrgDropdown from "./OrgDropdown";

const API_BASE = "";

export default function App() {
  // UI toggles
  const [showCDR, setShowCDR] = useState(false);
  const [showGDMS, setShowGDMS] = useState(false);

  // PBX login state
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [pbxToken, setPbxToken] = useState("");
  const [isPbxLoggedIn, setIsPbxLoggedIn] = useState(false);

  // GDMS state
  const [gdmsToken, setGdmsToken] = useState("");
  const [isGdmsLoggedIn, setIsGdmsLoggedIn] = useState(false);

  // UI helpers
  const [loading, setLoading] = useState(false);
  const [errMsg, setErrMsg] = useState("");

  // Load tokens on mount
  useEffect(() => {
    const storedPbx = localStorage.getItem("pbx_token");
    const storedGdms = localStorage.getItem("gdms_token");
    if (storedPbx) {
      setPbxToken(storedPbx);
      setIsPbxLoggedIn(true);
    }
    if (storedGdms) {
      setGdmsToken(storedGdms);
      setIsGdmsLoggedIn(true);
    }
  }, []);

  // PBX login
  const handleLogin = async (e) => {
    e.preventDefault();
    setErrMsg("");

    if (!username || !password) {
      setErrMsg("Please enter username and password");
      return;
    }

    try {
      setLoading(true);
      const res = await fetch(`${API_BASE}/pbx/auth/login`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        credentials: "include",
        body: JSON.stringify({ username, password }),
      });

      if (!res.ok) {
        const text = await res.text().catch(() => "");
        throw new Error(text || `Login failed (${res.status})`);
      }

      const data = await res.json().catch(() => ({}));
      const token = data?.token;
      if (!token) throw new Error("No token returned by server");

      localStorage.setItem("pbx_token", token);
      sessionStorage.setItem("pbx_username", username);
      sessionStorage.setItem("pbx_password", password);

      setPbxToken(token);
      setIsPbxLoggedIn(true);
      setPassword("");
      setErrMsg("");
    } catch (err) {
      setErrMsg(err.message || "Login failed");
    } finally {
      setLoading(false);
    }
  };

  // PBX logout
  const handlePbxLogout = async () => {
    try {
      await fetch(`${API_BASE}/pbx/auth/logout`, {
        method: "POST",
        credentials: "include",
      }).catch(() => {});
    } catch (e) {}
    localStorage.removeItem("pbx_token");
    sessionStorage.removeItem("pbx_username");
    sessionStorage.removeItem("pbx_password");
    setPbxToken("");
    setIsPbxLoggedIn(false);
    setShowCDR(false);
  };

  // GDMS login/logout
  const handleGDMSLogin = (token) => {
    localStorage.setItem("gdms_token", token);
    setGdmsToken(token);
    setIsGdmsLoggedIn(true);
    setShowGDMS(false);
  };

  const handleGdmsLogout = () => {
    localStorage.removeItem("gdms_token");
    setGdmsToken("");
    setIsGdmsLoggedIn(false);
    setShowGDMS(false);
  };

  // Contextual logout
  const handleContextualLogout = () => {
    if (showCDR) {
      handlePbxLogout();
    } else if (showGDMS) {
      handleGdmsLogout();
    } else {
      handlePbxLogout();
      handleGdmsLogout();
    }
  };

  return (
    <div className="app">
      {/* NAVBAR */}
      <div className="navbar">
        <h2 className="logo">ConnectView</h2>
        <div className="nav-buttons">
          <button
            onClick={() =>
              setShowCDR((prev) => {
                const next = !prev;
                if (next) setShowGDMS(false);
                return next;
              })
            }
            className="nav-btn"
          >
            {showCDR ? "Close PBX" : "Open PBX"}
          </button>

          <button
            onClick={() =>
              setShowGDMS((prev) => {
                const next = !prev;
                if (next) setShowCDR(false);
                return next;
              })
            }
            className="nav-btn"
          >
            {showGDMS ? "Close GDMS" : "Open GDMS"}
          </button>

          {(showCDR && isPbxLoggedIn) || (showGDMS && isGdmsLoggedIn) ? (
            <button onClick={handleContextualLogout} className="nav-btn">
              {showCDR ? "Logout from PBX" : "Logout from GDMS"}
            </button>
          ) : null}
        </div>
      </div>

      {/* MAIN CONTENT */}
      <div className={`content ${showCDR ? "table-mode" : "center-mode"}`}>
        {/* Welcome Page */}
        {!showCDR && !showGDMS && (
          <div className="welcome-screen">
            <h1 className="page-title">Welcome to ConnectView</h1>
          </div>
        )}

      {/* PBX Section */}
{showCDR && (
  <div className="pbx-section">
    {!isPbxLoggedIn ? (
      // SIGN IN CARD
      <div className="cdr-login-card">
       <div className="cdr-header">
  <span className="cdr-icon">📞</span>
  <span className="cdr-logo">ConnectView</span>
</div>

        <h2 className="cdr-title">Sign In</h2>

        <form onSubmit={handleLogin} className="cdr-form">
          <label>Email</label>
          <input
            type="text"
            value={username}
            onChange={(e) => setUsername(e.target.value)}
          />

          <label>Password</label>
          <input
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
          />

          <button type="submit" className="cdr-btn" disabled={loading}>
            {loading ? "Logging in..." : "Sign In"}
          </button>
        </form>

        {errMsg && <p className="cdr-error">{errMsg}</p>}
      </div>
    ) : (
      // CDR DASHBOARD
      <Dashboard
        onAuthError={() => {
          setIsPbxLoggedIn(false);
          localStorage.removeItem("pbx_token");
          setPbxToken("");
        }}
        pbxToken={pbxToken}   // ✅ Pass token into Dashboard
      />
    )}
  </div>
)}


        {/* GDMS Section */}
        {showGDMS && (
          <div className="gdms-section">
            <h2>GDMS Section</h2>
            <p>Please choose one organization:</p>
            <OrgDropdown />
          </div>
        )}
      </div>
    </div>
  );
}
