import { useEffect, useState } from "react";

export default function SIPReport({ orgId }) {
  const [rows, setRows] = useState([]);
  const [loading, setLoading] = useState(false);
  const [err, setErr] = useState("");

  useEffect(() => {
    if (!orgId) return;
    const controller = new AbortController();

    (async () => {
      try {
        setLoading(true);
        setErr("");
        setRows([]); // reset before new fetch

        const res = await fetch(`http://localhost:8080/gdms/sip-report?orgId=${orgId}`, {
          signal: controller.signal,
        });
        if (!res.ok) throw new Error(`Failed (${res.status})`);

        // ✅ STREAMING FETCH
        const reader = res.body.getReader();
        const decoder = new TextDecoder();
        let buffer = "";

        while (true) {
          const { value, done } = await reader.read();
          if (done) break;

          buffer += decoder.decode(value, { stream: true });

          // try partial parse
          try {
            const parsed = JSON.parse(buffer);
            if (Array.isArray(parsed)) {
              setRows(parsed); // progressively update UI
            }
          } catch {
            // not valid JSON yet, keep buffering
          }
        }
      } catch (e) {
        if (e.name !== "AbortError") setErr(e.message || "Failed to load SIP report");
      } finally {
        setLoading(false);
      }
    })();

    return () => controller.abort();
  }, [orgId]);

  // 🟢 Extract dynamic MAC columns
  const macColumns = rows.length
    ? Object.keys(rows[0]).filter((k) => k.toLowerCase().includes("mac"))
    : [];

  // 🟢 Download function
  const handleDownload = () => {
    if (!rows.length) return;

    const header = [
      "SIP Account Name",
      "SIP Server",
      "SIP User ID",
      "Display Name",
      "SIP Account Active Status",
      ...macColumns, // dynamic MAC headers
    ];

    const csvRows = [header.join(",")];
    rows.forEach((r) => {
      csvRows.push([
        r.accountName || "—",
        r.sipServer || "—",
        r.sipUserId || "—",
        r.displayName || "—",
        r.sipAccountActiveStatus || "—",
        ...macColumns.map((col) => r[col] || "—"),
      ].join(","));
    });

    const csvString = csvRows.join("\n");

    // Generate timestamp for filename
    const now = new Date();
    const timestamp = `${String(now.getDate()).padStart(2, "0")}-${String(
      now.getMonth() + 1
    ).padStart(2, "0")}-${now.getFullYear()} ${String(now.getHours()).padStart(
      2,
      "0"
    )}.${String(now.getMinutes()).padStart(2, "0")}.${String(
      now.getSeconds()
    ).padStart(2, "0")}`;

    const filename = `[GDMS] SIP account list ${timestamp}.csv`;

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
  if (!rows.length) return <div>No SIP accounts found for this organization.</div>;

  return (
    <div className="table-wrap">
      {/* 🟢 Download Button */}
      <div style={{ marginBottom: "10px" }}>
        <button className="btn" onClick={handleDownload}>
          ⬇️ Download SIP Report
        </button>
      </div>

      <div className="hscroll-strip">
        <table className="cdr-table">
          <thead>
            <tr>
              <th>SIP Account Name</th>
              <th>SIP Server</th>
              <th>SIP User ID</th>
              <th>Display Name</th>
              <th>SIP Account Active Status</th>
              {macColumns.map((col) => (
                <th key={col}>{col}</th>
              ))}
            </tr>
          </thead>
          <tbody>
            {rows.map((r, i) => (
              <tr key={i}>
                <td>{r.accountName || "—"}</td>
                <td>{r.sipServer || "—"}</td>
                <td>{r.sipUserId || "—"}</td>
                <td>{r.displayName || "—"}</td>
                <td>{r.sipAccountActiveStatus || "—"}</td>
                {macColumns.map((col) => (
                  <td key={col}>{r[col] || "—"}</td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
