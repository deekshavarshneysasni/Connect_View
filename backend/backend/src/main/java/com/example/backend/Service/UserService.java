// src/main/java/com/example/backend/Service/UserService.java
package com.example.backend.Service;

import com.example.backend.model.ApiResponse;
import com.example.backend.model.User;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class UserService {

    private final ExternalApiService externalApiService;

    public UserService(ExternalApiService externalApiService) {
        this.externalApiService = externalApiService;
    }

    /**
     * BROADCAST when the numeric caller's LAST 4 digits are between 8000 and 8010 (inclusive).
     * Examples that become BROADCAST:
     *  - 8000, 8001, ..., 8010
     *  - +91-98765-**8005**
     * Non-digits are stripped before checking.
     */
    String resolveCallType(String caller) {
        if (caller == null) return "DEFAULT";
        String digits = caller.replaceAll("\\D", ""); // keep digits only

        if (digits.length() < 4) return "DEFAULT";    // not enough digits to match

        String last4 = digits.substring(digits.length() - 4);
        try {
            int last4Num = Integer.parseInt(last4);
            boolean inRange = last4Num >= 8000 && last4Num <= 8010;
            return inRange ? "BROADCAST" : "DEFAULT";
        } catch (NumberFormatException ignore) {
            return "DEFAULT";
        }
    }

    public List<User> getFilteredUsers(String token, String username, String password) {
        ApiResponse apiResponse = externalApiService.getUsersFromApi(token, username, password);

        return apiResponse.getData().stream()
                .map(user -> {
                    User filteredUser = new User();
                    filteredUser.setCaller(user.getCaller());
                    filteredUser.setCallee(user.getCallee());

                    // Apply your rule (override upstream callType)
                    filteredUser.setCallType(resolveCallType(user.getCaller()));

                    filteredUser.setStartTime(user.getStartTime());
                    filteredUser.setEndTime(user.getEndTime());
                    filteredUser.setSessionTime(user.getSessionTime());
                    filteredUser.setBridgeTime(user.getBridgeTime());
                    filteredUser.setCallStatus(user.getCallStatus());
                    filteredUser.setDisposition(user.getDisposition());
                    filteredUser.setDtmf(user.getDtmf());
                    filteredUser.setCategory(user.getCategory());
                    filteredUser.setSubCategory(user.getSubCategory());
                    filteredUser.setUuid(user.getUuid());
                    return filteredUser;
                })
                .collect(Collectors.toList());
    }
}
