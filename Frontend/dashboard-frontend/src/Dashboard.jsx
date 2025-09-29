// src/Dashboard.jsx
import { useEffect, useMemo, useRef, useState } from "react";
import * as XLSX from "xlsx";

const API_BASE = "";
const SOURCE_TZ = { kind: "LOCAL" };

/* ===================== Helpers ===================== */

function toTitleCase(key) {
  return key.replace(/_/g, " ")
    .replace(/([a-z])([A-Z])/g, "$1 $2")
    .replace(/\s+/g, " ")
    .trim()
    .replace(/^./, (c) => c.toUpperCase());
}

function flatten(obj, prefix = "", out = {}) {
  Object.entries(obj || {}).forEach(([k, v]) => {
    const nk = prefix ? `${prefix}.${k}` : k;
    if (v && typeof v === "object" && !Array.isArray(v)) flatten(v, nk, out);
    else out[nk] = v;
  });
  return out;
}

const COLUMN_DEFS = [
  { key: "caller", label: "Caller" },
  { key: "callee", label: "Callee" },
  { key: "call_type", label: "Call Type" },
  { key: "start_time", label: "Start Time" },
  { key: "end_time", label: "End Time" },
  { key: "session_time", label: "Session Time" },
  { key: "bridge_time", label: "Bridge Time" },
  { key: "call_status", label: "Call Status" },
  { key: "dtmf", label: "DTMF" },
  { key: "uuid", label: "UUID" },
  { key: "category", label: "Category" },
  { key: "subcategory", label: "Subcategory" },
];

const ALIASES = {
  caller: ["caller"],
  callee: ["callee", "endpoint"],
  call_type: ["call_type", "calltype", "call type"],
  start_time: ["start_time", "StartTime", "date_from", "start", "starttime"],
  end_time: ["end_time", "EndTime", "date_to", "end", "endtime"],
  session_time: ["session_time", "SessionTime", "sessionTime", "sessiontime"],
  bridge_time: ["bridge_time","BridgeTime","BrideTime","Bridgetime","brigetime","bridgetime"],
  call_status: ["call_status","CallStatus","termdescription","status"],
  dtmf: ["dtmf","DTMF","digits","dtmf_code"],
  uuid: ["uuid"],
  category: ["category"],
  subcategory: ["subcategory","Sub Category","SubCategory"],
};

function pickFirst(obj, keys) {
  for (const k of keys) {
    if (k in obj && obj[k] !== undefined && obj[k] !== null && obj[k] !== "")
      return obj[k];
  }
  return undefined;
}

function normalizeRow(raw) {
  const canonical = {};
  for (const [target, candidates] of Object.entries(ALIASES)) {
    canonical[target] = pickFirst(raw, candidates);
  }
  return canonical;
}

/* ---------- date/duration helpers ---------- */

const DATE_FIELDS = new Set(["start_time", "end_time"]);
const DURATION_FIELDS = new Set(["session_time", "bridge_time"]);

function buildDateWithAssumedTZ(year, mon, day, HH, MM, SS) {
  if (SOURCE_TZ.kind === "UTC") return new Date(Date.UTC(year, mon - 1, day, HH, MM, SS));
  return new Date(year, mon - 1, day, HH, MM, SS, 0);
}

// Robust date parser
function coerceToDate(val) {
  if (val == null || val === "" || val === "None") return null;

  if (typeof val === "number") {
    const ms = val < 1e11 ? val * 1000 : val;
    return new Date(ms);
  }

  if (typeof val === "string") {
    const s = val.trim();

    // epoch
    if (/^\d+$/.test(s)) {
      const n = Number(s);
      const ms = n < 1e11 ? n * 1000 : n;
      return new Date(ms);
    }

    // ISO with Z/offset
    if (/Z$|[+\-]\d{2}:\d{2}$/.test(s)) return new Date(s);

    // YYYY-MM-DD [HH:mm[:ss]]
    let m =
      s.match(/^(\d{4})-(\d{2})-(\d{2})[ T](\d{2}):(\d{2})(?::(\d{2}))?$/) ||
      s.match(/^(\d{4})-(\d{2})-(\d{2})$/);
    if (m) return buildDateWithAssumedTZ(+m[1], +m[2], +m[3], +(m[4]||0), +(m[5]||0), +(m[6]||0));

    // DD-MM-YYYY [HH:mm[:ss]]
    m = s.match(/^(\d{2})-(\d{2})-(\d{4})(?:[ T](\d{2}):(\d{2})(?::(\d{2}))?)?$/);
    if (m) return buildDateWithAssumedTZ(+m[3], +m[2], +m[1], +(m[4]||0), +(m[5]||0), +(m[6]||0));

    // DD/MM/YYYY [HH:mm[:ss]]
    m = s.match(/^(\d{2})\/(\d{2})\/(\d{4})(?:[ T](\d{2}):(\d{2})(?::(\d{2}))?)?$/);
    if (m) return buildDateWithAssumedTZ(+m[3], +m[2], +m[1], +(m[4]||0), +(m[5]||0), +(m[6]||0));

    // last try
    return new Date(s);
  }
  return null;
}

// Parse input type="date" (YYYY-MM-DD) as local midnight
function parseInputYMD(ymd) {
  if (!ymd) return null;
  const [y, m, d] = ymd.split("-").map(Number);
  if (!y || !m || !d) return null;
  return new Date(y, m - 1, d, 0, 0, 0, 0);
}
function startOfDay(d) { return new Date(d.getFullYear(), d.getMonth(), d.getDate(), 0, 0, 0, 0); }
function endOfDay(d)   { return new Date(d.getFullYear(), d.getMonth(), d.getDate(), 23, 59, 59, 999); }

function formatDate_DDMMYYYY_HHMM(d) {
  const pad = (n) => String(n).padStart(2, "0");
  return `${pad(d.getDate())}/${pad(d.getMonth()+1)}/${d.getFullYear()} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

function formatSecondsHMS(sec) {
  if (sec == null || isNaN(sec)) return "—";
  const s = Math.max(0, Math.floor(Number(sec)));
  const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), r = s % 60;
  const pad = (n) => String(n).padStart(2, "0");
  return h > 0 ? `${h}:${pad(m)}:${pad(r)}` : `${m}:${pad(r)}`;
}

function fmtCell(key, val) {
  if (val === null || val === undefined || val === "") return "—";
  if (val === "None") return "None";   // keep explicit "None"
  if (val === 0 || val === "0") return "0";
  if (DURATION_FIELDS.has(key)) {
    if (typeof val === "number") return formatSecondsHMS(val);
    if (typeof val === "string" && /^\d+(\.\d+)?$/.test(val.trim()))
      return formatSecondsHMS(Number(val.trim()));
    return String(val);
  }
  if (DATE_FIELDS.has(key)) {
    const d = coerceToDate(val);
    return d ? formatDate_DDMMYYYY_HHMM(d) : String(val);
  }
  return String(val);
}

function fileTimestampParts() {
  const d = new Date();
  const pad = (n) => String(n).padStart(2, "0");
  return {
    dateStr: `${pad(d.getDate())}-${pad(d.getMonth() + 1)}-${d.getFullYear()}`,
    timeStr: `${pad(d.getHours())}.${pad(d.getMinutes())}.${pad(d.getSeconds())}`
  };
}
/* ===================== Component ===================== */

export default function Dashboard({ onAuthError }) {
  const [rows, setRows] = useState([]);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState("");
  const didRun = useRef(false);

  // date range (YYYY-MM-DD from <input type="date">)
  const [fromDate, setFromDate] = useState("");
  const [toDate, setToDate] = useState("");

  // column selector
  const [selectedCols, setSelectedCols] = useState([]);
  const [showDropdown, setShowDropdown] = useState(false);

  // pagination
  const [page, setPage] = useState(1);
  const rowsPerPage = 50;

  const toggleColumn = (key) => {
    setSelectedCols((prev) =>
      prev.includes(key) ? prev.filter((c) => c !== key) : [...prev, key]
    );
  };

  // ---- fetcher (server + client-side fallback date filter) ----
  const fetchReport = async (signal) => {
    const token = localStorage.getItem("pbx_token");
    const uname = sessionStorage.getItem("pbx_username");
    const pwd = sessionStorage.getItem("pbx_password");
    if (!token || !uname || !pwd) throw new Error("Not logged in / missing credentials");

    // Send dates to server (safe if backend ignores)
    const qs = new URLSearchParams();
    if (fromDate) qs.set("from", fromDate);
    if (toDate)   qs.set("to", toDate);
    const url = `${API_BASE}/api/users/filtered-report${qs.toString() ? `?${qs}` : ""}`;

    const res = await fetch(url, {
      method: "PATCH",
      headers: { "Content-Type": "application/json", Authorization: `Bearer ${token}` },
      credentials: "include",
      body: JSON.stringify({ username: uname, password: pwd, from: fromDate, to: toDate }),
      signal,
    });

    if (res.status === 401) {
      localStorage.removeItem("pbx_token");
      onAuthError?.();
      throw new Error("Unauthorized — please login again.");
    }
    if (!res.ok) {
      const text = await res.text().catch(() => "");
      throw new Error(text || `Failed (${res.status})`);
    }

    const data = await res.json();
    const list = Array.isArray(data) ? data : data?.data || [];
    const normalized = list.map((r) => normalizeRow(flatten(r)));

    // ✅ Client-side fallback filter by start_time when both dates are set
    if (fromDate && toDate) {
      const f = parseInputYMD(fromDate);
      const t = parseInputYMD(toDate);
      if (f && t) {
        const winStart = startOfDay(f);
        const winEnd   = endOfDay(t);
        return normalized.filter((r) => {
          const d = coerceToDate(r.start_time);
          return d && d >= winStart && d <= winEnd;
        });
      }
    }
    return normalized;
  };

  // initial load
  useEffect(() => {
    if (didRun.current) return;
    didRun.current = true;
    const controller = new AbortController();
    (async () => {
      try {
        setLoading(true);
        const normalized = await fetchReport(controller.signal);
        setRows(normalized || []);
      } catch (e) { if (e.name !== "AbortError") setErr(e.message); }
      finally { setLoading(false); }
    })();
    return () => controller.abort();
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  const handleSearch = async () => {
    if (fromDate && toDate && new Date(toDate) < new Date(fromDate)) {
      alert("“To” must be on/after “From”.");
      return;
    }
    try {
      setLoading(true); setErr("");
      const controller = new AbortController();
      const normalized = await fetchReport(controller.signal);
      setRows(normalized || []);
      setPage(1);
    } catch (e) { setErr(e.message); }
    finally { setLoading(false); }
  };

  const refresh = handleSearch;

  const columns = useMemo(() => {
    if (!rows.length) return COLUMN_DEFS.slice();
    const presentKeys = new Set();
    rows.forEach((r) => Object.keys(r).forEach((k) => presentKeys.add(k)));
    const extras = Array.from(presentKeys)
      .filter((k) => !COLUMN_DEFS.some((c) => c.key === k))
      .map((k) => ({ key: k, label: toTitleCase(k) }));
    return [...COLUMN_DEFS, ...extras];
  }, [rows]);

  const activeCols = selectedCols.length
    ? columns.filter((c) => selectedCols.includes(c.key))
    : columns;

  const paginatedRows = useMemo(() => {
    const start = (page - 1) * rowsPerPage;
    return rows.slice(start, start + rowsPerPage);
  }, [rows, page]);

  const downloadXLSX = () => {
    if (!rows.length) return;
    const headerLabels = activeCols.map((c) => c.label);
    const records = rows.map((r) => {
      const out = {};
      activeCols.forEach(({ key, label }) => { out[label] = fmtCell(key, r[key]); });
      return out;
    });
    const ws = XLSX.utils.json_to_sheet(records, { header: headerLabels });
    ws["!cols"] = headerLabels.map(() => ({ wch: 18 }));
    const wb = XLSX.utils.book_new();
    XLSX.utils.book_append_sheet(wb, ws, "CDR Report");
    const { dateStr, timeStr } = fileTimestampParts();
    XLSX.utils.book_append_sheet(
      wb,
      XLSX.utils.aoa_to_sheet([
        ["Generated At (Local)", `${dateStr} ${timeStr}`],
        ["Record Count", records.length],
        ["From", fromDate || "(not set)"],
        ["To", toDate || "(not set)"],
        ["Selected Columns", selectedCols.length ? selectedCols.join(", ") : "All"],
      ]),
      "Info"
    );
    XLSX.writeFile(wb, `cdr-report-${dateStr}-${timeStr}.xlsx`);
  };

  if (loading) return <div>Loading CDR…</div>;
  if (err) return <div style={{ color: "crimson" }}>{err}</div>;
  if (!rows.length) return (
    <div>
      No CDR records found.
      <div style={{ marginTop: 8 }}>
        <label>From <input type="date" value={fromDate} onChange={(e)=>setFromDate(e.target.value)} /></label>
        <label style={{ marginLeft: 8 }}>To <input type="date" value={toDate} onChange={(e)=>setToDate(e.target.value)} /></label>
        <button className="btn" style={{ marginLeft: 8 }} onClick={handleSearch}>Search</button>
      </div>
    </div>
  );

  return (
    <div className="table-wrap">
      <div className="hscroll-strip">
        {/* Toolbar */}
        <div className="cdr-header" style={{ display: "flex", gap: 16, flexWrap: "wrap", justifyContent: "space-between" }}>
          {/* date filter */}
          <div style={{ display: "flex", gap: 12 }}>
            <label>From <input type="date" value={fromDate} onChange={(e)=>setFromDate(e.target.value)} /></label>
            <label>To <input type="date" value={toDate} onChange={(e)=>setToDate(e.target.value)} /></label>
            <button className="btn" onClick={handleSearch}>Search</button>
            {fromDate && toDate && new Date(toDate) < new Date(fromDate) && (
              <span style={{ color: "crimson", fontSize: 12 }}>“To” must be on/after “From”.</span>
            )}
          </div>

          {/* actions + pagination + column selector */}
          <div style={{ display: "flex", gap: 12, position: "relative", flexWrap: "wrap" }}>
            <button className="btn" onClick={() => setPage(p=>Math.max(1,p-1))} disabled={page===1}>Prev</button>
            <span style={{ alignSelf: "center" }}>Page {page} of {Math.ceil(rows.length / rowsPerPage)}</span>
            <button className="btn" onClick={() => setPage(p=>p+1)} disabled={page*rowsPerPage>=rows.length}>Next</button>

            <button className="btn" onClick={refresh}>Refresh</button>
            <button className="btn" onClick={downloadXLSX}>Download</button>

            <div>
              <button className="btn" onClick={() => setShowDropdown(s=>!s)}>Select Columns ▾</button>
              {showDropdown && (
                <div style={{ position: "absolute", top: "100%", right: 0, background: "white", border: "1px solid #ccc", borderRadius: 6, padding: 8, zIndex: 1000, maxHeight: 300, overflowY: "auto", minWidth: 180 }}>
                  <div style={{ marginBottom: 6 }}>
                    <label>
                      <input
                        type="checkbox"
                        checked={selectedCols.length === columns.length}
                        onChange={(e) => setSelectedCols(e.target.checked ? columns.map(c=>c.key) : [])}
                      /> Select All
                    </label>
                  </div>
                  {columns.map(({ key, label }) => (
                    <div key={key}>
                      <label>
                        <input type="checkbox" checked={selectedCols.includes(key)} onChange={()=>toggleColumn(key)} /> {label}
                      </label>
                    </div>
                  ))}
                </div>
              )}
            </div>
          </div>
        </div>

        {/* Table */}
        <table className="cdr-table">
          <thead>
            <tr>{activeCols.map(({ key, label }) => <th key={key}>{label}</th>)}</tr>
          </thead>
          <tbody>
            {paginatedRows.map((r, i) => (
              <tr key={i}>
                {activeCols.map(({ key }) => <td key={key}>{fmtCell(key, r[key])}</td>)}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
