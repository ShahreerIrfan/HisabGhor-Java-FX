package com.smartkhata.service;

import com.smartkhata.database.DatabaseManager;
import com.smartkhata.model.dto.*;
import com.smartkhata.model.entity.Business;
import com.smartkhata.model.entity.User;
import com.smartkhata.model.enums.UserRole;
import com.smartkhata.service.api.AuthService;
import com.smartkhata.service.api.SessionContext;
import com.smartkhata.service.impl.LocalAuthService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthServiceIntegrationTest {

    private Path tempDbPath;
    private AuthService authService;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        tempDbPath = tempDir.resolve("auth_test.db");
        DatabaseManager.setCustomJdbcUrl("jdbc:sqlite:" + tempDbPath.toAbsolutePath().toString());
        DatabaseManager.initializeDatabase();
        SessionContext.clearSession();
        authService = new LocalAuthService();
    }

    @AfterEach
    void tearDown() {
        SessionContext.clearSession();
        DatabaseManager.resetCustomJdbcUrl();
    }

    @Test
    void shouldDetectFirstRunWhenNoOwnerExists() {
        assertThat(authService.isFirstRun()).isTrue();
    }

    @Test
    void shouldInitializeBusinessAndOwnerAccountSuccessfully() {
        OnboardingRequestDto request = new OnboardingRequestDto(
                "Mizan Stores",
                "Mizanur Rahman",
                "mizan",
                "01711223344",
                "Dhaka, Bangladesh",
                "BDT",
                "en",
                "secretPin123".toCharArray()
        );

        String recoveryToken = authService.initializeBusiness(request);

        assertThat(recoveryToken).isNotBlank();
        assertThat(recoveryToken).startsWith("SK-");
        assertThat(authService.isFirstRun()).isFalse();

        assertThat(SessionContext.isAuthenticated()).isTrue();
        assertThat(SessionContext.getCurrentUser().username()).isEqualTo("mizan");
        assertThat(SessionContext.getCurrentUser().role()).isEqualTo(UserRole.OWNER);
        assertThat(SessionContext.getCurrentBusiness().name()).isEqualTo("Mizan Stores");
    }

    @Test
    void shouldAuthenticateWithCorrectCredentialsAndRejectInvalid() {
        OnboardingRequestDto request = new OnboardingRequestDto(
                "Karim Enterprise", "Abdul Karim", "karim",
                null, null, "BDT", "en", "pass4321".toCharArray()
        );
        authService.initializeBusiness(request);
        SessionContext.clearSession();

        // 1. Successful Login
        LoginResult successRes = authService.login("karim", "pass4321".toCharArray());
        assertThat(successRes.isSuccess()).isTrue();
        assertThat(successRes.status()).isEqualTo(LoginResult.Status.SUCCESS);
        assertThat(SessionContext.isAuthenticated()).isTrue();

        // 2. Failed Login with wrong password
        SessionContext.clearSession();
        LoginResult failRes = authService.login("karim", "wrongPass".toCharArray());
        assertThat(failRes.isSuccess()).isFalse();
        assertThat(failRes.status()).isEqualTo(LoginResult.Status.INVALID_CREDENTIALS);
        assertThat(SessionContext.isAuthenticated()).isFalse();

        // 3. User not found
        LoginResult notFoundRes = authService.login("unknown_user", "any".toCharArray());
        assertThat(notFoundRes.status()).isEqualTo(LoginResult.Status.USER_NOT_FOUND);
    }

    @Test
    void shouldThrottleLoginAttemptsAfterRepeatedFailures() {
        OnboardingRequestDto request = new OnboardingRequestDto(
                "Shuvo Store", "Shuvo", "shuvo",
                null, null, "BDT", "en", "pin1234".toCharArray()
        );
        authService.initializeBusiness(request);
        SessionContext.clearSession();

        // Attempt 1 through 4
        for (int i = 1; i <= 4; i++) {
            LoginResult res = authService.login("shuvo", "badPin".toCharArray());
            assertThat(res.status()).isEqualTo(LoginResult.Status.INVALID_CREDENTIALS);
        }

        // 5th attempt triggers throttling
        LoginResult throttledRes = authService.login("shuvo", "badPin".toCharArray());
        assertThat(throttledRes.status()).isEqualTo(LoginResult.Status.THROTTLED);
        assertThat(throttledRes.lockoutRemainingSeconds()).isGreaterThan(0);

        // Subsequent attempt while locked remains throttled
        LoginResult lockedRes = authService.login("shuvo", "pin1234".toCharArray());
        assertThat(lockedRes.status()).isEqualTo(LoginResult.Status.THROTTLED);
    }

    @Test
    void shouldRecoverPasswordUsingOfflineRecoveryToken() {
        OnboardingRequestDto request = new OnboardingRequestDto(
                "Recovery Test Store", "Owner", "owner",
                null, null, "BDT", "en", "initialPass".toCharArray()
        );
        String recoveryToken = authService.initializeBusiness(request);
        SessionContext.clearSession();

        long bizId = SessionContext.getCurrentBusiness() != null ? SessionContext.getCurrentBusiness().id() : 1L;

        // Reset password using the recovery token
        boolean recovered = authService.recoverAccount(bizId, recoveryToken, "newSecretPass99".toCharArray());
        assertThat(recovered).isTrue();

        // Old password no longer works
        LoginResult oldRes = authService.login("owner", "initialPass".toCharArray());
        assertThat(oldRes.isSuccess()).isFalse();

        // New password works cleanly
        LoginResult newRes = authService.login("owner", "newSecretPass99".toCharArray());
        assertThat(newRes.isSuccess()).isTrue();
    }

    @Test
    void shouldEnforceRoleAuthorizationChecks() {
        // Unauthenticated access must be denied
        SessionContext.clearSession();
        assertThatThrownBy(SessionContext::requireAuthenticated)
                .isInstanceOf(SecurityException.class);

        // Create Cashier session
        Business biz = new Business(1L, "Store", null, null, null, null, "BDT", null, null);
        User cashier = new User(2L, 1L, "cashier", "Cashier Staff", "hash", "salt",
                UserRole.CASHIER, true, null, null, null);
        SessionContext.setSession(biz, cashier);

        // Cashier can satisfy requireAuthenticated
        SessionContext.requireAuthenticated();

        // But Cashier is blocked from requireOwner
        assertThatThrownBy(SessionContext::requireOwner)
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("OWNER role permissions");
    }

    @Test
    void shouldUpdateBusinessProfileSuccessfully() {
        OnboardingRequestDto request = new OnboardingRequestDto(
                "Old Name Store", "Owner", "owner1",
                null, null, "BDT", "en", "pass1234".toCharArray()
        );
        authService.initializeBusiness(request);

        BusinessProfileUpdateDto updateDto = new BusinessProfileUpdateDto(
                "Updated Super Shop",
                "Updated Super Shop Ltd.",
                "01799887766",
                "Gulshan 1, Dhaka",
                "TRAD-998877"
        );

        Business updated = authService.updateBusinessProfile(SessionContext.getCurrentBusiness().id(), updateDto);
        assertThat(updated.name()).isEqualTo("Updated Super Shop");
        assertThat(updated.phone()).isEqualTo("01799887766");
        assertThat(SessionContext.getCurrentBusiness().name()).isEqualTo("Updated Super Shop");
    }

    @Test
    void shouldCreateListUpdateAndDeleteStaffUser() {
        OnboardingRequestDto request = new OnboardingRequestDto(
                "Staff Store", "Master Owner", "owner_boss",
                null, null, "BDT", "en", "pass1234".toCharArray()
        );
        authService.initializeBusiness(request);

        // 1. Create cashier staff
        User cashier = authService.createStaffUser(new StaffCreateDto(
                SessionContext.getCurrentBusiness().id(),
                "cashier_salam",
                "Salam Mia",
                UserRole.CASHIER,
                "salamPin".toCharArray()
        ));
        assertThat(cashier.id()).isNotNull();
        assertThat(cashier.role()).isEqualTo(UserRole.CASHIER);

        // 2. List users
        java.util.List<User> users = authService.listUsersForCurrentBusiness();
        assertThat(users).hasSize(2);
        assertThat(users).anyMatch(u -> u.username().equals("cashier_salam"));

        // 3. Update staff user
        User updated = authService.updateStaffUser(new StaffUpdateDto(
                cashier.id(),
                "Abdur Salam",
                UserRole.CASHIER,
                true,
                "newPin99".toCharArray()
        ));
        assertThat(updated.fullName()).isEqualTo("Abdur Salam");

        // Verify login with new PIN
        SessionContext.clearSession();
        LoginResult loginRes = authService.login("cashier_salam", "newPin99".toCharArray());
        assertThat(loginRes.isSuccess()).isTrue();

        // 4. Owner logs back in and deletes unused staff
        LoginResult ownerLogin = authService.login("owner_boss", "pass1234".toCharArray());
        assertThat(ownerLogin.isSuccess()).isTrue();

        authService.deleteStaffUser(cashier.id());
        java.util.List<User> remaining = authService.listUsersForCurrentBusiness();
        assertThat(remaining).hasSize(1);
        assertThat(remaining.get(0).username()).isEqualTo("owner_boss");
    }

    @Test
    void shouldPreventDeletingOwnerSelfOrLastActiveOwner() {
        OnboardingRequestDto request = new OnboardingRequestDto(
                "Solo Store", "Solo Boss", "solo_boss",
                null, null, "BDT", "en", "pass1234".toCharArray()
        );
        authService.initializeBusiness(request);
        long ownerId = SessionContext.getCurrentUser().id();

        // Cannot delete self
        assertThatThrownBy(() -> authService.deleteStaffUser(ownerId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot delete your own");
    }
}

