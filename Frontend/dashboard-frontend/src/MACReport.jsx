import { useEffect, useState, useRef } from "react";
import * as XLSX from "xlsx";

const pick = (obj, keys, fallback = "—") => {
  for (const k of keys) {
    const v = obj?.[k];
    if (v !== undefined && v !== null && v !== "") return v;
  }
  return fallback;
};

const yesNo = (v) =>
  v === 1 || v === true || v === "1"
    ? "Yes"
    : v === 0 || v === false || v === "0"
    ? "No"
    : "—";

const ALL_COLUMNS = [
  { key: "mac", label: "MAC Address", pick: (r) => pick(r, ["macAddress", "mac"]) },
  { key: "sn", label: "SN", pick: (r) => pick(r, ["sn"]) },
  { key: "deviceName", label: "Device Name", pick: (r) => pick(r, ["deviceName", "devicename"]) },
  { key: "siteName", label: "Site Name", pick: (r) => pick(r, ["siteName"]) },
  { key: "deviceModel", label: "Device Model", pick: (r) => pick(r, ["deviceModel", "deviceType"]) },
  { key: "firmwareVersion", label: "Firmware Version", pick: (r) => pick(r, ["firmwareVersion"]) },
  {
    key: "deviceStatus",
    label: "Device Status",
    pick: (r) => {
      const s = pick(r, ["deviceStatus", "status"], undefined);
      return s === 1 ? "Online" : s === 0 ? "Offline" : s === -1 ? "Abnormal" : "—";
    },
  },
  {
    key: "pushConfiguration",
    label: "Push Configuration",
    pick: (r) => yesNo(pick(r, ["pushConfiguration", "isSynchronized"], null)),
  },
  { key: "lastConfigTime", label: "Last Config Time", pick: (r) => pick(r, ["lastConfigTime", "lastTime"]) },
  { key: "account1UserId", label: "Account 1 User ID", pick: (r) => pick(r, ["account1UserId", "sipUserId"]) },
  { key: "account1SipServer", label: "Account 1 SIP Server", pick: (r) => pick(r, ["account1SipServer", "sipServer"]) },
];

export default function MACReport({ orgId }) {
  const [rows, setRows] = useState([]);
  const [loading, setLoading] = useState(false);
  const [err, setErr] = useState("");
  const [search, setSearch] = useState("");
  const [selectedCols, setSelectedCols] = useState(ALL_COLUMNS.map((c) => c.key));
  const [showDropdown, setShowDropdown] = useState(false);

  const dropdownRef = useRef(null);

  // Close dropdown when clicking outside
  useEffect(() => {
    const handleClickOutside = (event) => {
      if (dropdownRef.current && !dropdownRef.current.contains(event.target)) {
        setShowDropdown(false);
      }
    };
    document.addEventListener("mousedown", handleClickOutside);
    return () => {
      document.removeEventListener("mousedown", handleClickOutside);
    };
  }, []);

  useEffect(() => {
    if (!orgId) return;
    const controller = new AbortController();

    (async () => {
      try {
        setLoading(true);
        setErr("");
        setRows([]);

        const res = await fetch(
          `http://localhost:8080/gdms/report?orgId=${orgId}`,
          { signal: controller.signal }
        );
        if (!res.ok) throw new Error(`Failed (${res.status})`);

        const data = await res.json();
        setRows(data || []);
      } catch (e) {
        if (e.name !== "AbortError") setErr(e.message || "Failed to load report");
      } finally {
        setLoading(false);
      }
    })();

    return () => controller.abort();
  }, [orgId]);

// Filter rows by search text across ALL columns
// Filter rows by search text across raw + picked values
const filteredRows = rows.filter((r) => {
  const text = search.toLowerCase();

  // 1. Raw row values
  const rawMatch = Object.values(r).some((val) =>
    String(val || "").toLowerCase().includes(text)
  );

  // 2. Processed column values
  const colMatch = ALL_COLUMNS.some((col) =>
    String(col.pick(r) || "").toLowerCase().includes(text)
  );

  return rawMatch || colMatch;
});
{/* Search Bar with Clear Button */}
<div style={{ position: "relative", display: "inline-block" }}>
  <input
    type="text"
    placeholder="🔍 Search..."
    value={search}
    onChange={(e) => setSearch(e.target.value)}
    style={{ paddingRight: "25px" }} // give space for the ❌
  />
  {search && (
    <button
      onClick={() => setSearch("")}
      style={{
        position: "absolute",
        right: "5px",
        top: "50%",
        transform: "translateY(-50%)",
        border: "none",
        background: "transparent",
        cursor: "pointer",
        fontSize: "14px",
      }}
    >
      ❌
    </button>
  )}
</div>

  const toggleColumn = (key) => {
    setSelectedCols((prev) =>
      prev.includes(key) ? prev.filter((k) => k !== key) : [...prev, key]
    );
  };

  const downloadReport = () => {
    if (!filteredRows.length) return;

    const records = filteredRows.map((r) => {
      const obj = {};
      ALL_COLUMNS.forEach((col) => {
        if (selectedCols.includes(col.key)) {
          obj[col.label] = col.pick(r);
        }
      });
      return obj;
    });

    const ws = XLSX.utils.json_to_sheet(records);
    const wb = XLSX.utils.book_new();
    XLSX.utils.book_append_sheet(wb, ws, "MAC Report");

    const now = new Date();
    const pad = (n) => String(n).padStart(2, "0");
    const ts = `${pad(now.getDate())}-${pad(now.getMonth() + 1)}-${now.getFullYear()} ${pad(
      now.getHours()
    )}.${pad(now.getMinutes())}.${pad(now.getSeconds())}`;

    XLSX.writeFile(wb, `[GDMS] VOIP Device status ${ts}.xlsx`);
  };

  if (!orgId) return null;
  if (loading && !rows.length) return <div>Loading MAC Report…</div>;
  if (err) return <div style={{ color: "crimson" }}>{err}</div>;
  if (!rows.length) return <div>No data found for this organization.</div>;

  return (
    <div className="report-container">
      <div className="report-actions" style={{ display: "flex", gap: "10px" }}>
        {/* Search Bar */}
        <input
          type="text"
          placeholder="🔍 Search..."
          value={search}
          onChange={(e) => setSearch(e.target.value)}
        />

        {/* Column Selector Dropdown */}
       <div className="dropdown" ref={dropdownRef}>
  <button className="dropdown-btn" onClick={() => setShowDropdown(!showDropdown)}>
    Select Columns ⬇️
  </button>

  {showDropdown && (
    <div className="dropdown-menu">
      {ALL_COLUMNS.map((col) => (
        <label key={col.key} className="dropdown-item">
          <input
            type="checkbox"
            checked={selectedCols.includes(col.key)}
            onChange={() => toggleColumn(col.key)}
          />
          {col.label}
        </label>
      ))}
    </div>
  )}
</div>


        <button className="btn" onClick={downloadReport}>
          ⬇️ Download Report
        </button>
      </div>

      <div className="table-wrap">
        <div className="hscroll-strip">
          <table className="cdr-table">
            <thead>
              <tr>
                {ALL_COLUMNS.filter((c) => selectedCols.includes(c.key)).map((col) => (
                  <th key={col.key}>{col.label}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {filteredRows.map((r, i) => (
                <tr key={i}>
                  {ALL_COLUMNS.filter((c) => selectedCols.includes(c.key)).map((col) => (
                    <td key={col.key}>{col.pick(r)}</td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  );
}
