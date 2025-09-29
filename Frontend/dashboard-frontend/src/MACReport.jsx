import { useEffect, useState } from "react";
import * as XLSX from "xlsx";

// helper to tolerate either DTO keys or raw keys
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

export default function MACReport({ orgId }) {
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
        setRows([]); // reset previous rows

        const res = await fetch(
          `http://localhost:8080/gdms/report?orgId=${orgId}`,
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
            // partial JSON, wait for more chunks
          }
        }
      } catch (e) {
        if (e.name !== "AbortError")
          setErr(e.message || "Failed to load report");
      } finally {
        setLoading(false);
      }
    })();

    return () => controller.abort();
  }, [orgId]);

  const downloadReport = () => {
    if (!rows.length) return;

    const records = rows.map((r) => ({
      "MAC Address": pick(r, ["macAddress", "mac"]),
      SN: pick(r, ["sn"]),
      "Device Name": pick(r, ["deviceName", "devicename"]),
      "Site Name": pick(r, ["siteName"]),
      "Device Model": pick(r, ["deviceModel", "deviceType"]),
      "Firmware Version": pick(r, ["firmwareVersion"]),
      "Device Status": (() => {
        const s = pick(r, ["deviceStatus", "status"], undefined);
        return s === 1
          ? "Online"
          : s === 0
          ? "Offline"
          : s === -1
          ? "Abnormal"
          : "—";
      })(),
      "Push Configuration": yesNo(
        pick(r, ["pushConfiguration", "isSynchronized"], null)
      ),
      "Last Config Time": pick(r, ["lastConfigTime", "lastTime"]),
      "Account 1 User ID": pick(r, ["account1UserId", "sipUserId"]),
      "Account 1 SIP Server": pick(r, ["account1SipServer", "sipServer"]),
    }));

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
      {/* Fixed button outside table */}
      <div className="report-actions">
        <button className="btn" onClick={downloadReport}>
          ⬇️ Download Report
        </button>
      </div>

      <div className="table-wrap">
        <div className="hscroll-strip">
          <table className="cdr-table">
            <thead>
              <tr>
                <th>MAC Address</th>
                <th>SN</th>
                <th>Device Name</th>
                <th>Site Name</th>
                <th>Device Model</th>
                <th>Firmware Version</th>
                <th>Device Status</th>
                <th>Push Configuration</th>
                <th>Last Config Time</th>
                <th>Account 1 User ID</th>
                <th>Account 1 SIP Server</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r, i) => (
                <tr key={i}>
                  <td>{pick(r, ["macAddress", "mac"])}</td>
                  <td>{pick(r, ["sn"])}</td>
                  <td>{pick(r, ["deviceName", "devicename"])}</td>
                  <td>{pick(r, ["siteName"])}</td>
                  <td>{pick(r, ["deviceModel", "deviceType"])}</td>
                  <td>{pick(r, ["firmwareVersion"])}</td>
                  <td>
                    {(() => {
                      const s = pick(r, ["deviceStatus", "status"], undefined);
                      return s === 1
                        ? "Online"
                        : s === 0
                        ? "Offline"
                        : s === -1
                        ? "Abnormal"
                        : "—";
                    })()}
                  </td>
                  <td>{yesNo(pick(r, ["pushConfiguration", "isSynchronized"], null))}</td>
                  <td>{pick(r, ["lastConfigTime", "lastTime"])}</td>
                  <td>{pick(r, ["account1UserId", "sipUserId"])}</td>
                  <td>{pick(r, ["account1SipServer", "sipServer"])}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  );
}
