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

  // UI helpers
  const [loading, setLoading] = useState(false);
  const [errMsg, setErrMsg] = useState("");

  // Load PBX token on mount
  useEffect(() => {
    const storedPbx = localStorage.getItem("pbx_token");
    if (storedPbx) {
      setPbxToken(storedPbx);
      setIsPbxLoggedIn(true);
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

  return (
    <div className="app">
      {/* Navbar */}
      <div className="navbar">
        <h2 className="logo">ConnectView</h2>
        <div className="nav-buttons">
          {/* PBX buttons */}
          {!isPbxLoggedIn ? (
            <button
              onClick={() => {
                setShowCDR(true);
                setShowGDMS(false);
              }}
              className="nav-btn"
            >
              Open VoIP Report
            </button>
          ) : (
            <>
              <button
                onClick={() => setShowCDR((prev) => !prev)}
                className="nav-btn"
              >
                {showCDR ? "Close VoIP Report" : "Open VoIP Report"}
              </button>
              <button onClick={handlePbxLogout} className="nav-btn">
                Logout PBX
              </button>
            </>
          )}

          {/* GDMS buttons → only toggle, no logout */}
          <button
            onClick={() => {
              setShowGDMS((prev) => !prev);
              setShowCDR(false);
            }}
            className="nav-btn"
          >
            {showGDMS ? "Close GDMS" : "Open GDMS"}
          </button>
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
              <div className="cdr-login-card">
                <div className="cdr-header">
                  <span className="cdr-logo">ConnectView</span>
                </div>

                <h2 className="cdr-title">Sign In</h2>

                <form onSubmit={handleLogin} className="cdr-form">
                  <label>Username</label>
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
              <Dashboard
                onAuthError={() => {
                  setIsPbxLoggedIn(false);
                  localStorage.removeItem("pbx_token");
                  setPbxToken("");
                }}
                pbxToken={pbxToken}
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
