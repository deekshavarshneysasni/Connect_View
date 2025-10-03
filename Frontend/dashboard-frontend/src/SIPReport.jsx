import { useEffect, useState, useRef, useMemo } from "react";

export default function SIPReport({ orgId }) {
  const [rows, setRows] = useState([]);
  const [loading, setLoading] = useState(false);
  const [err, setErr] = useState("");
  const [search, setSearch] = useState("");
  const [selectedCols, setSelectedCols] = useState([]);
  const [showDropdown, setShowDropdown] = useState(false);

  const dropdownRef = useRef(null);

  // Close dropdown on outside click
  useEffect(() => {
    const handler = (e) => {
      if (dropdownRef.current && !dropdownRef.current.contains(e.target)) {
        setShowDropdown(false);
      }
    };
    document.addEventListener("mousedown", handler);
    return () => document.removeEventListener("mousedown", handler);
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
          `http://localhost:8080/gdms/sip-report?orgId=${orgId}`,
          { signal: controller.signal }
        );
        if (!res.ok) throw new Error(`Failed (${res.status})`);

        const reader = res.body.getReader();
        const decoder = new TextDecoder();
        let buffer = "";

        while (true) {
          const { value, done } = await reader.read();
          if (done) break;

          buffer += decoder.decode(value, { stream: true });

          try {
            const parsed = JSON.parse(buffer);
            if (Array.isArray(parsed)) {
              setRows(parsed);
            }
          } catch {
            // wait for more chunks
          }
        }
      } catch (e) {
        if (e.name !== "AbortError")
          setErr(e.message || "Failed to load SIP report");
      } finally {
        setLoading(false);
      }
    })();

    return () => controller.abort();
  }, [orgId]);

  // Detect columns dynamically
  const allColumns = useMemo(() => {
    if (!rows.length) return [];
    const fixedCols = [
      { key: "accountName", label: "SIP Account Name" },
      { key: "sipServer", label: "SIP Server" },
      { key: "sipUserId", label: "SIP User ID" },
      { key: "displayName", label: "Display Name" },
      { key: "sipAccountActiveStatus", label: "SIP Account Active Status" },
    ];
    const macCols = Object.keys(rows[0] || {}).filter((k) =>
      k.toLowerCase().includes("mac")
    );
    return [
      ...fixedCols,
      ...macCols.map((col) => ({ key: col, label: col })),
    ];
  }, [rows]);

  // Default select all columns
  useEffect(() => {
    if (allColumns.length && !selectedCols.length) {
      setSelectedCols(allColumns.map((c) => c.key));
    }
  }, [allColumns]);

  // Toggle column
  const toggleColumn = (key) => {
    setSelectedCols((prev) =>
      prev.includes(key) ? prev.filter((c) => c !== key) : [...prev, key]
    );
  };

  // Filter rows by search
  const filteredRows = useMemo(() => {
    if (!search.trim()) return rows;
    const text = search.toLowerCase();
    return rows.filter((r) =>
      Object.values(r).some((val) =>
        String(val || "").toLowerCase().includes(text)
      )
    );
  }, [rows, search]);

  // Download CSV with only selected columns
  const handleDownload = () => {
    if (!filteredRows.length) return;

    const header = allColumns
      .filter((c) => selectedCols.includes(c.key))
      .map((c) => c.label);

    const csvRows = [header.join(",")];
    filteredRows.forEach((r) => {
      csvRows.push(
        allColumns
          .filter((c) => selectedCols.includes(c.key))
          .map((c) => r[c.key] || "—")
          .join(",")
      );
    });

    const csvString = csvRows.join("\n");

    const now = new Date();
    const ts = `${String(now.getDate()).padStart(2, "0")}-${String(
      now.getMonth() + 1
    ).padStart(2, "0")}-${now.getFullYear()} ${String(
      now.getHours()
    ).padStart(2, "0")}.${String(now.getMinutes()).padStart(
      2,
      "0"
    )}.${String(now.getSeconds()).padStart(2, "0")}`;

    const filename = `[GDMS] SIP account list ${ts}.csv`;

    const blob = new Blob([csvString], { type: "text/csv" });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = filename;
    a.click();
    URL.revokeObjectURL(url);
  };

  if (!orgId) return null;
  if (loading && !rows.length) return <div>Loading SIP Report…</div>;
  if (err) return <div style={{ color: "crimson" }}>{err}</div>;
  if (!rows.length)
    return <div>No SIP accounts found for this organization.</div>;

  return (
    <div className="report-container">
      {/* Toolbar */}
      <div className="report-actions" style={{ gap: "10px", display: "flex" }}>
        {/* 🔍 Search */}
        <input
          type="text"
          placeholder="🔍 Search..."
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          style={{ padding: "6px 10px", border: "1px solid #ccc", borderRadius: "6px" }}
        />

        {/* Column selector */}
        <div className="dropdown" ref={dropdownRef} style={{ position: "relative" }}>
          <button className="btn secondary" onClick={() => setShowDropdown(!showDropdown)}>
            Select Columns ▾
          </button>
          {showDropdown && (
            <div className="column-dropdown">
              <div style={{ marginBottom: 6 }}>
                <label>
                  <input
                    type="checkbox"
                    checked={selectedCols.length === allColumns.length}
                    onChange={(e) =>
                      setSelectedCols(
                        e.target.checked ? allColumns.map((c) => c.key) : []
                      )
                    }
                  />{" "}
                  Select All
                </label>
              </div>
              {allColumns.map((col) => (
                <label key={col.key} className="dropdown-item">
                  <input
                    type="checkbox"
                    checked={selectedCols.includes(col.key)}
                    onChange={() => toggleColumn(col.key)}
                  />{" "}
                  {col.label}
                </label>
              ))}
            </div>
          )}
        </div>

        {/* Download */}
        <button className="btn" onClick={handleDownload}>
          ⬇️ Download SIP Report
        </button>
      </div>

      {/* Table */}
      <div className="table-wrap">
        <div className="hscroll-strip">
          <table className="cdr-table">
            <thead>
              <tr>
                {allColumns
                  .filter((c) => selectedCols.includes(c.key))
                  .map((c) => (
                    <th key={c.key}>{c.label}</th>
                  ))}
              </tr>
            </thead>
            <tbody>
              {filteredRows.map((r, i) => (
                <tr key={i}>
                  {allColumns
                    .filter((c) => selectedCols.includes(c.key))
                    .map((c) => (
                      <td key={c.key}>{r[c.key] || "—"}</td>
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
