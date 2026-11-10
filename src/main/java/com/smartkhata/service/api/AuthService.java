package com.smartkhata.service.api;

import com.smartkhata.model.dto.*;
import com.smartkhata.model.entity.Business;

public interface AuthService {

    boolean isFirstRun();

    String initializeBusiness(OnboardingRequestDto request);

    LoginResult login(String username, char[] passwordOrPin);

    boolean unlockSession(char[] passwordOrPin);

    void logout();

    boolean recoverAccount(long businessId, String recoveryToken, char[] newPasswordOrPin);

    Business updateBusinessProfile(long businessId, BusinessProfileUpdateDto dto);

    java.util.List<com.smartkhata.model.entity.User> listUsersForCurrentBusiness();

    com.smartkhata.model.entity.User createStaffUser(com.smartkhata.model.dto.StaffCreateDto dto);

    com.smartkhata.model.entity.User updateStaffUser(com.smartkhata.model.dto.StaffUpdateDto dto);

    void deleteStaffUser(long userId);
}

