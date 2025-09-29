import { useState, useEffect } from "react";
import MACReport from "./MacReport";
import SIPReport from "./SIPReport";

export default function OrgDropdown() { 
  const [orgs, setOrgs] = useState([]);
  const [selectedOrgId, setSelectedOrgId] = useState("");
  const [selectedOrgName, setSelectedOrgName] = useState("");
  const [showMac, setShowMac] = useState(false);
  const [showSip, setShowSip] = useState(false);

  useEffect(() => {
    fetch("http://localhost:8080/gdms/org-names")
      .then((res) => res.json())
      .then((data) => setOrgs(data))
      .catch((err) => console.error("Error fetching orgs:", err));
  }, []);

  const handleOrgChange = (e) => {
    const id = e.target.value;
    setSelectedOrgId(id);
    const org = orgs.find((o) => String(o.id) === id);
    setSelectedOrgName(org ? org.organization : "");
    setShowMac(false);
    setShowSip(false);
  };

  return (
    <div>
      <label className="block text-lg font-medium mb-2">
        Choose one Organization:
      </label>
      <select
        value={selectedOrgId}
        onChange={handleOrgChange}
        className="border rounded p-2"
      >
        <option value="">-- Select --</option>
        {orgs.map((org) => (
          <option key={org.id} value={org.id}>
            {org.organization}
          </option>
        ))}
      </select>

      {selectedOrgId && (
        <div className="mt-4">
          <p className="mb-2 text-green-700">
            ✅ You selected: <b>{selectedOrgName}</b>
          </p>

          <div style={{ marginBottom: "12px" }}>
            <button className="btn" onClick={() => { setShowMac(true); setShowSip(false); }}>
              MAC Report
            </button>
            <button className="btn" style={{ marginLeft: "8px" }} onClick={() => { setShowSip(true); setShowMac(false); }}>
              SIP Report
            </button>
          </div>

          {showMac && <MACReport orgId={selectedOrgId} />}
          {showSip && <SIPReport orgId={selectedOrgId} />}
        </div>
      )}
    </div>
  );
}
