package com.example.backend.Service;

import com.example.backend.gdms.GDMSAPI;
import com.example.backend.model.DeviceReport;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class GDMSService {

    private final GDMSAPI client;

    public GDMSService() {
        this.client = new GDMSAPI(
                "www.gdms.cloud",
                "product",
                "admin@1234",
                "102993",
                "snp2mDxueVyTC6k6bUC67TqtVeNx9MLg",
                null,
                120,
                20,
                true
        );
        client.startRefreshLoop(20, 120);
    }


    /** ✅ Only organization id + name */
    @Cacheable("orgNames")
    public List<Map<String, Object>> getOrgNames() {
        List<Map<String, Object>> orgs = client.listOrgsAll(1000);
        return orgs.stream()
                .map(o -> Map.of(
                        "id", o.get("id"),
                        "organization", o.get("organization")
                ))
                .collect(Collectors.toList());
    }

    /**
     * ✅ Device report (ALL rows, NO pagination, NO cache)
     * Uses a BULK status refresh for the org to avoid N+1 calls.
     */
    @Cacheable(value = "deviceReports", key = "#orgId")
    public List<DeviceReport> getDeviceReportByOrg(int orgId) {
        // 1) Fetch devices for org
        List<Map<String, Object>> devices = client.fetchDevicesForOrg(orgId, 5000);

        // 2) Bulk refresh account status once for this org
        List<Map<String, Object>> selected = List.of(
                Map.of("id", orgId, "organization", "N/A")
        );
        client.fetchDeviceAccountStatusForSelectedOrgs(devices, selected);

        // 3) Build a lookup: mac -> status row (from the refreshed payload)
        Map<String, Map<String, Object>> statusRowByMac = new HashMap<>();
        Map<String, Object> payload = client.getStatusPayload();

        if (payload != null && payload.get("success") instanceof List) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> successList = (List<Map<String, Object>>) payload.get("success");

            for (Map<String, Object> row : successList) {
                String mac = str(row.get("mac"));
                if (!mac.isEmpty()) {
                    statusRowByMac.put(mac, row);
                }
            }
        }

        // 4) Build report list without per-device API calls
        List<DeviceReport> reports = new ArrayList<>(devices.size());

        for (Map<String, Object> device : devices) {
            String mac = str(device.get("mac"));
            Map<String, Object> statusRow = statusRowByMac.get(mac);

            DeviceReport report = new DeviceReport();
            report.setMacAddress(mac);
            report.setSn(str(device.get("sn")));
            report.setDeviceName(str(device.get("deviceName")));
            report.setSiteName(str(device.get("siteName")));
            report.setDeviceModel(str(device.get("deviceType")));
            report.setFirmwareVersion(str(device.get("firmwareVersion")));

            Integer devStatus = asInt(device.get("status"));
            Integer acctStatus = statusRow != null ? asInt(statusRow.get("accountStatus")) : null;
            report.setStatus(devStatus != null ? devStatus : (acctStatus != null ? acctStatus : -1));

            Integer isSync = coalesceInt(device.get("isSynchronized"), device.get("is_synchronized"));
            report.setPushConfiguration(isSync != null ? isSync : 0);

            report.setLastConfigTime(str(device.get("lastTime")));

            // ✅ Take SIP accounts if present
            if (statusRow != null && statusRow.get("sipAccountInfoList") instanceof List) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> sipAccounts =
                        (List<Map<String, Object>>) statusRow.get("sipAccountInfoList");

                if (!sipAccounts.isEmpty()) {
                    Map<String, Object> acc1 = sipAccounts.get(0);
                    report.setAccount1UserId(str(acc1.get("sipUserId")));
                    report.setAccount1SipServer(str(acc1.get("sipServer")));
                }
            }

            reports.add(report);
        }

        return reports;
    }

    /**
     * ✅ SIP report:
     * - One row per SIP account (grouped by sipUserId)
     * - Status normalized (Active/Inactive/Abnormal)
     * - Multiple devices per SIP account collected as MAC1/MAC2/...
     * - MAC normalized (Unallocated if missing)
     */
    @Cacheable(value = "sipReports", key = "#orgId")
    public List<Map<String, String>> getSipReportByOrg(int orgId) {
        // Step 1: fetch all SIP accounts
        List<Map<String, Object>> sipAccounts = client.fetchSIPAccountsForOrg(orgId, 5000);

        // Step 2: fetch all devices + status
        List<Map<String, Object>> devices = client.fetchDevicesForOrg(orgId, 5000);
        List<Map<String, Object>> selected = List.of(Map.of("id", orgId, "organization", "N/A"));
        client.fetchDeviceAccountStatusForSelectedOrgs(devices, selected);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> enrichedDevices =
                (List<Map<String, Object>>) client.getStatusPayload().get("success");

        // Build lookup: sipUserId -> list of device SIP entries
        Map<String, List<Map<String, Object>>> sipToDevices = new HashMap<>();
        if (enrichedDevices != null) {
            for (Map<String, Object> device : enrichedDevices) {
                String mac = normalizeMac(device.get("mac"));

                @SuppressWarnings("unchecked")
                List<Map<String, Object>> sipList =
                        (List<Map<String, Object>>) device.get("sipAccountInfoList");

                if (sipList == null) continue;

                for (Map<String, Object> sipAcc : sipList) {
                    String sipUserId = str(sipAcc.get("sipUserId"));
                    if (sipUserId.equals("—")) continue;

                    Map<String, Object> entry = new HashMap<>();
                    entry.put("account", sipAcc.get("account"));
                    entry.put("mac", mac);
                    entry.put("status", sipAcc.get("accountStatus"));
                    sipToDevices.computeIfAbsent(sipUserId, k -> new ArrayList<>()).add(entry);
                }
            }
        }

        // Step 3: build report rows for each SIP account
        List<Map<String, String>> sipReports = new ArrayList<>();

        for (Map<String, Object> acc : sipAccounts) {
            Map<String, String> row = new LinkedHashMap<>();
            String sipUserId = str(acc.get("sipUserId"));

            row.put("accountName", str(acc.get("accountName")));
            row.put("displayName", str(acc.get("displayName")));
            row.put("sipServer", str(acc.get("sipServer")));
            row.put("sipUserId", sipUserId);
            row.put("sipAccountActiveStatus", normalizeStatus(acc.get("status")));
            row.put("MAC1 Address", "—");
            row.put("MAC2 Address", "—");

            // Enrich with device MACs if available
            List<Map<String, Object>> deviceEntries = sipToDevices.getOrDefault(sipUserId, List.of());
            for (Map<String, Object> d : deviceEntries) {
                Integer accNum = asInt(d.get("account"));
                String mac = normalizeMac(d.get("mac"));
                if (accNum != null && accNum == 1) {
                    row.put("MAC1 Address", mac);
                } else if (accNum != null && accNum == 2) {
                    row.put("MAC2 Address", mac);
                }
            }

            sipReports.add(row);
        }

        return sipReports;
    }





    // ----------------- helpers -----------------

    private static String safeKey(Object o) {
        // raw key for grouping (no "—" substitution)
        return (o == null) ? "" : String.valueOf(o).trim();
    }

    private static String str(Object o) {
        String s = (o == null) ? "" : String.valueOf(o).trim();
        return s.isEmpty() ? "—" : s;
    }

    private static Integer asInt(Object o) {
        if (o == null) return null;
        try { return Integer.parseInt(String.valueOf(o).trim()); }
        catch (Exception e) { return null; }
    }

    private static Integer coalesceInt(Object... vals) {
        for (Object v : vals) {
            Integer i = asInt(v);
            if (i != null) return i;
        }
        return null;
    }

    private String normalizeStatus(Object statusVal) {
        if ("1".equals(String.valueOf(statusVal)) || "Up".equalsIgnoreCase(String.valueOf(statusVal))) return "Active";
        if ("0".equals(String.valueOf(statusVal)) || "Down".equalsIgnoreCase(String.valueOf(statusVal))) return "Inactive";
        return "Abnormal";
    }

    private String normalizeMac(Object macVal) {
        if (macVal == null) return "Unallocated";
        String s = String.valueOf(macVal).trim();
        if (s.isEmpty() || "null".equalsIgnoreCase(s)) return "Unallocated";
        return s;
    }
}
