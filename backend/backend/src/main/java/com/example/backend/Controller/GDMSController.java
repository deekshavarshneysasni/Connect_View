package com.example.backend.Controller;

import com.example.backend.Service.GDMSService;
import com.example.backend.model.DeviceReport;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/gdms")
public class GDMSController {

    private final GDMSService gdmsService;

    public GDMSController(GDMSService gdmsService) {
        this.gdmsService = gdmsService;
    }

    /** ✅ Org list (ID + name) */
    @GetMapping("/org-names")
    public List<Map<String, Object>> getOrgNames() {
        return gdmsService.getOrgNames();
    }

    /** ✅ Device report (ALL rows) */
    @GetMapping("/report")
    public List<DeviceReport> getDeviceReport(@RequestParam int orgId) {
        return gdmsService.getDeviceReportByOrg(orgId);
    }

    /** ✅ SIP report (ALL rows) */
    @GetMapping("/sip-report")
    public List<Map<String, String>> getSipReport(@RequestParam int orgId) {
        return gdmsService.getSipReportByOrg(orgId);
    }
}
