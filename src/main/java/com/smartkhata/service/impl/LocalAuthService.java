package com.smartkhata.service.impl;

import com.smartkhata.dao.*;
import com.smartkhata.database.DatabaseException;
import com.smartkhata.database.DatabaseManager;
import com.smartkhata.database.TransactionManager;
import com.smartkhata.model.dto.*;
import com.smartkhata.model.entity.Business;
import com.smartkhata.model.entity.User;
import com.smartkhata.model.enums.UserRole;
import com.smartkhata.service.api.AuthService;
import com.smartkhata.service.api.SessionContext;
import com.smartkhata.util.PasswordHasher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class LocalAuthService implements AuthService {

    private static final Logger log = LoggerFactory.getLogger(LocalAuthService.class);

    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final long LOCKOUT_DURATION_MS = 30_000L; // 30 seconds

    private final AtomicInteger failedAttempts = new AtomicInteger(0);
    private final AtomicLong lockoutUntilMillis = new AtomicLong(0L);

    private final BusinessDao businessDao;
    private final UserDao userDao;
    private final SettingsDao settingsDao;
    private final AuditLogDao auditLogDao;

    public LocalAuthService() {
        this(new SqliteBusinessDao(), new SqliteUserDao(), new SqliteSettingsDao(), new SqliteAuditLogDao());
    }

    public LocalAuthService(BusinessDao businessDao, UserDao userDao, SettingsDao settingsDao) {
        this(businessDao, userDao, settingsDao, new SqliteAuditLogDao());
    }

    public LocalAuthService(BusinessDao businessDao, UserDao userDao, SettingsDao settingsDao, AuditLogDao auditLogDao) {
        this.businessDao = businessDao;
        this.userDao = userDao;
        this.settingsDao = settingsDao;
        this.auditLogDao = auditLogDao;
    }

    @Override
    public boolean isFirstRun() {
        try (Connection conn = DatabaseManager.getConnection()) {
            return !userDao.hasAnyOwner(conn);
        } catch (SQLException e) {
            log.error("Failed to check first-run state: {}", e.getMessage(), e);
            throw new DatabaseException("Failed to check first-run status", e);
        }
    }

    @Override
    public String initializeBusiness(OnboardingRequestDto request) {
        if (request.businessName() == null || request.businessName().isBlank()) {
            throw new IllegalArgumentException("Business name is required.");
        }
        if (request.ownerFullName() == null || request.ownerFullName().isBlank()) {
            throw new IllegalArgumentException("Owner full name is required.");
        }
        if (request.username() == null || request.username().isBlank()) {
            throw new IllegalArgumentException("Username is required.");
        }
        if (request.passwordOrPin() == null || request.passwordOrPin().length < 4) {
            throw new IllegalArgumentException("Security PIN or Password must be at least 4 characters.");
        }

        PasswordHasher.HashedCredential cred = PasswordHasher.hashPassword(request.passwordOrPin());
        String recoveryToken = generateRecoveryToken();
        PasswordHasher.HashedCredential recoveryCred = PasswordHasher.hashPassword(recoveryToken.toCharArray());

        return TransactionManager.executeInTransaction(conn -> {
            // 1. Create Business
            Business biz = businessDao.save(conn, new Business(
                    null,
                    request.businessName().trim(),
                    request.businessName().trim(),
                    request.phone(),
                    request.address(),
                    null,
                    request.currencyCode() != null ? request.currencyCode() : "BDT",
                    null, null
            ));

            // 2. Create Owner User
            User owner = userDao.save(conn, new User(
                    null,
                    biz.id(),
                    request.username().trim(),
                    request.ownerFullName().trim(),
                    cred.hashHex(),
                    cred.saltHex(),
                    UserRole.OWNER,
                    true,
                    null, null, null
            ));

            // 3. Store Recovery and Language Settings
            settingsDao.set(conn, biz.id(), "ui_language", request.language() != null ? request.language() : "en");
            settingsDao.set(conn, biz.id(), "owner_recovery_hash", recoveryCred.hashHex());
            settingsDao.set(conn, biz.id(), "owner_recovery_salt", recoveryCred.saltHex());

            SessionContext.setSession(biz, owner);
            log.info("Business [{}] initialized successfully with owner [{}]", biz.name(), owner.username());
            return recoveryToken;
        });
    }

    @Override
    public LoginResult login(String username, char[] passwordOrPin) {
        long now = System.currentTimeMillis();
        long lockoutEnd = lockoutUntilMillis.get();
        if (now < lockoutEnd) {
            long remainingSec = (lockoutEnd - now) / 1000L + 1;
            log.warn("Login throttled for user attempt. Remaining lockout: {}s", remainingSec);
            return LoginResult.throttled(remainingSec);
        }

        if (username == null || username.isBlank() || passwordOrPin == null) {
            return LoginResult.notFound();
        }

        try (Connection conn = DatabaseManager.getConnection()) {
            Optional<User> userOpt = userDao.findByUsername(conn, username.trim());
            if (userOpt.isEmpty()) {
                handleFailedAttempt();
                return LoginResult.notFound();
            }

            User user = userOpt.get();
            if (!user.isActive()) {
                return new LoginResult(LoginResult.Status.INACTIVE, "User account is disabled.", 0, null);
            }

            boolean valid = PasswordHasher.verifyPassword(passwordOrPin, user.passwordHash(), user.salt());
            if (!valid) {
                int attempts = handleFailedAttempt();
                if (attempts >= MAX_FAILED_ATTEMPTS) {
                    return LoginResult.throttled(LOCKOUT_DURATION_MS / 1000L);
                }
                return LoginResult.invalid(MAX_FAILED_ATTEMPTS - attempts);
            }

            // Successful authentication
            failedAttempts.set(0);
            lockoutUntilMillis.set(0L);

            userDao.updateLastLogin(conn, user.id());
            Optional<Business> bizOpt = businessDao.findById(conn, user.businessId());
            Business biz = bizOpt.orElseThrow(() -> new IllegalStateException("Business not found for user: " + user.id()));

            SessionContext.setSession(biz, user);
            return LoginResult.success(user);

        } catch (SQLException e) {
            log.error("Database error during login: {}", e.getMessage(), e);
            throw new DatabaseException("Login failed due to database error", e);
        }
    }

    @Override
    public boolean unlockSession(char[] passwordOrPin) {
        User user = SessionContext.getCurrentUser();
        if (user == null || passwordOrPin == null) {
            return false;
        }

        boolean valid = PasswordHasher.verifyPassword(passwordOrPin, user.passwordHash(), user.salt());
        if (valid) {
            SessionContext.unlock();
            return true;
        }
        return false;
    }

    @Override
    public void logout() {
        SessionContext.clearSession();
    }

    @Override
    public boolean recoverAccount(long businessId, String recoveryToken, char[] newPasswordOrPin) {
        if (recoveryToken == null || newPasswordOrPin == null || newPasswordOrPin.length < 4) {
            return false;
        }

        try (Connection conn = DatabaseManager.getConnection()) {
            Optional<String> hashOpt = settingsDao.get(conn, businessId, "owner_recovery_hash");
            Optional<String> saltOpt = settingsDao.get(conn, businessId, "owner_recovery_salt");

            if (hashOpt.isEmpty() || saltOpt.isEmpty()) {
                log.warn("Recovery credentials not found in settings for business {}", businessId);
                return false;
            }

            boolean valid = PasswordHasher.verifyPassword(recoveryToken.trim().toCharArray(), hashOpt.get(), saltOpt.get());
            if (!valid) {
                log.warn("Invalid recovery token entered for business {}", businessId);
                return false;
            }

            // Recovery verified: Reset owner credential
            PasswordHasher.HashedCredential newCred = PasswordHasher.hashPassword(newPasswordOrPin);
            Optional<User> ownerOpt = userDao.findFirstByRole(conn, UserRole.OWNER);
            if (ownerOpt.isEmpty()) {
                return false;
            }

            User owner = ownerOpt.get();
            User updated = new User(
                    owner.id(), owner.businessId(), owner.username(), owner.fullName(),
                    newCred.hashHex(), newCred.saltHex(), owner.role(), owner.isActive(),
                    owner.lastLoginAt(), owner.createdAt(), null
            );
            userDao.save(conn, updated);

            // Rotate recovery token
            String newRecoveryToken = generateRecoveryToken();
            PasswordHasher.HashedCredential newRecCred = PasswordHasher.hashPassword(newRecoveryToken.toCharArray());
            settingsDao.set(conn, businessId, "owner_recovery_hash", newRecCred.hashHex());
            settingsDao.set(conn, businessId, "owner_recovery_salt", newRecCred.saltHex());

            log.info("Owner password reset successfully via offline recovery token");
            return true;
        } catch (SQLException e) {
            log.error("Failed during account recovery: {}", e.getMessage(), e);
            throw new DatabaseException("Account recovery failed", e);
        }
    }

    @Override
    public Business updateBusinessProfile(long businessId, BusinessProfileUpdateDto dto) {
        SessionContext.requireOwner();

        if (dto.name() == null || dto.name().isBlank()) {
            throw new IllegalArgumentException("Business name cannot be blank.");
        }

        return TransactionManager.executeInTransaction(conn -> {
            Optional<Business> existingOpt = businessDao.findById(conn, businessId);
            if (existingOpt.isEmpty()) {
                throw new IllegalArgumentException("Business not found with id: " + businessId);
            }

            Business b = existingOpt.get();
            Business updated = new Business(
                    b.id(),
                    dto.name().trim(),
                    dto.legalName() != null ? dto.legalName().trim() : b.legalName(),
                    dto.phone(),
                    dto.address(),
                    dto.tradeLicense(),
                    b.currencyCode(),
                    b.createdAt(),
                    null
            );

            Business saved = businessDao.save(conn, updated);
            SessionContext.setSession(saved, SessionContext.getCurrentUser());
            log.info("Business profile updated successfully for [{}]", saved.name());
            return saved;
        });
    }

    @Override
    public java.util.List<User> listUsersForCurrentBusiness() {
        SessionContext.requireAuthenticated();
        long businessId = SessionContext.getRequiredBusinessId();
        try (Connection conn = DatabaseManager.getConnection()) {
            return userDao.findAllByBusiness(conn, businessId);
        } catch (SQLException e) {
            log.error("Failed to list users for business {}: {}", businessId, e.getMessage(), e);
            throw new DatabaseException("Failed to retrieve staff users", e);
        }
    }

    @Override
    public User createStaffUser(StaffCreateDto dto) {
        SessionContext.requireAuthenticated();
        SessionContext.requireOwner();
        long businessId = SessionContext.getRequiredBusinessId();
        User currentUser = SessionContext.getCurrentUser();

        if (dto.username() == null || dto.username().trim().length() < 3) {
            throw new IllegalArgumentException("Username must be at least 3 characters long.");
        }
        if (dto.fullName() == null || dto.fullName().isBlank()) {
            throw new IllegalArgumentException("Full name is required.");
        }
        if (dto.passwordOrPin() == null || dto.passwordOrPin().length < 4) {
            throw new IllegalArgumentException("Security PIN or Password must be at least 4 characters.");
        }

        return TransactionManager.executeInTransaction(conn -> {
            Optional<User> existing = userDao.findByUsername(conn, dto.username().trim());
            if (existing.isPresent()) {
                throw new IllegalArgumentException("Username '" + dto.username().trim() + "' is already taken.");
            }

            PasswordHasher.HashedCredential cred = PasswordHasher.hashPassword(dto.passwordOrPin());
            UserRole role = dto.role() != null ? dto.role() : UserRole.CASHIER;

            User newUser = new User(
                    null,
                    businessId,
                    dto.username().trim().toLowerCase(),
                    dto.fullName().trim(),
                    cred.hashHex(),
                    cred.saltHex(),
                    role,
                    true,
                    null,
                    null,
                    null
            );

            User saved = userDao.save(conn, newUser);

            // Record audit log
            auditLogDao.save(conn, new com.smartkhata.model.entity.AuditLog(
                    null,
                    businessId,
                    currentUser != null ? currentUser.id() : null,
                    "CREATE",
                    "USER",
                    saved.id(),
                    "Created staff account @" + saved.username() + " (" + saved.role() + ")",
                    java.time.LocalDateTime.now()
            ));

            log.info("Staff user [{}] created successfully by owner", saved.username());
            return saved;
        });
    }

    @Override
    public User updateStaffUser(StaffUpdateDto dto) {
        SessionContext.requireAuthenticated();
        SessionContext.requireOwner();
        long businessId = SessionContext.getRequiredBusinessId();
        User currentUser = SessionContext.getCurrentUser();

        if (dto.fullName() == null || dto.fullName().isBlank()) {
            throw new IllegalArgumentException("Full name is required.");
        }

        return TransactionManager.executeInTransaction(conn -> {
            User existing = userDao.findById(conn, dto.userId())
                    .orElseThrow(() -> new IllegalArgumentException("User not found: " + dto.userId()));

            if (existing.businessId() != businessId) {
                throw new SecurityException("Cross-business user access denied");
            }

            // Guard against demoting or deactivating the last active owner
            if (existing.role() == UserRole.OWNER && (dto.role() != UserRole.OWNER || !dto.isActive())) {
                int activeOwners = userDao.countActiveOwners(conn, businessId);
                if (activeOwners <= 1) {
                    throw new IllegalStateException("Cannot demote or deactivate the only active Owner of this store.");
                }
            }

            String passwordHash = existing.passwordHash();
            String salt = existing.salt();
            if (dto.newPasswordOrPinOptional() != null && dto.newPasswordOrPinOptional().length > 0) {
                if (dto.newPasswordOrPinOptional().length < 4) {
                    throw new IllegalArgumentException("New PIN/Password must be at least 4 characters.");
                }
                PasswordHasher.HashedCredential cred = PasswordHasher.hashPassword(dto.newPasswordOrPinOptional());
                passwordHash = cred.hashHex();
                salt = cred.saltHex();
            }

            User updated = new User(
                    existing.id(),
                    existing.businessId(),
                    existing.username(),
                    dto.fullName().trim(),
                    passwordHash,
                    salt,
                    dto.role() != null ? dto.role() : existing.role(),
                    dto.isActive(),
                    existing.lastLoginAt(),
                    existing.createdAt(),
                    java.time.LocalDateTime.now()
            );

            User saved = userDao.save(conn, updated);

            // Record audit log
            auditLogDao.save(conn, new com.smartkhata.model.entity.AuditLog(
                    null,
                    businessId,
                    currentUser != null ? currentUser.id() : null,
                    "UPDATE",
                    "USER",
                    saved.id(),
                    "Updated staff account @" + saved.username() + " (Active=" + saved.isActive() + ", Role=" + saved.role() + ")",
                    java.time.LocalDateTime.now()
            ));

            log.info("Staff user [{}] updated successfully", saved.username());
            return saved;
        });
    }

    @Override
    public void deleteStaffUser(long userId) {
        SessionContext.requireAuthenticated();
        SessionContext.requireOwner();
        long businessId = SessionContext.getRequiredBusinessId();
        User currentUser = SessionContext.getCurrentUser();

        if (currentUser != null && currentUser.id() == userId) {
            throw new IllegalStateException("You cannot delete your own logged-in user account.");
        }

        TransactionManager.runInTransaction(conn -> {
            User existing = userDao.findById(conn, userId)
                    .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));

            if (existing.businessId() != businessId) {
                throw new SecurityException("Cross-business user deletion denied");
            }

            if (existing.role() == UserRole.OWNER) {
                int activeOwners = userDao.countActiveOwners(conn, businessId);
                if (activeOwners <= 1) {
                    throw new IllegalStateException("Cannot delete the only active Owner of this store.");
                }
            }

            if (userDao.hasLinkedTransactions(conn, userId)) {
                throw new IllegalStateException("Cannot delete user @" + existing.username() +
                        " because they have recorded sales, purchases, payments, or ledger transactions. Please deactivate their account instead to maintain financial audit integrity.");
            }

            userDao.delete(conn, userId, businessId);

            auditLogDao.save(conn, new com.smartkhata.model.entity.AuditLog(
                    null,
                    businessId,
                    currentUser != null ? currentUser.id() : null,
                    "DELETE",
                    "USER",
                    userId,
                    "Deleted staff user account @" + existing.username(),
                    java.time.LocalDateTime.now()
            ));

            log.info("Staff user [{}] deleted successfully", existing.username());
        });
    }

    private int handleFailedAttempt() {
        int count = failedAttempts.incrementAndGet();
        if (count >= MAX_FAILED_ATTEMPTS) {
            lockoutUntilMillis.set(System.currentTimeMillis() + LOCKOUT_DURATION_MS);
            log.warn("Max failed login attempts ({}) exceeded. Triggering 30s lockout.", count);
        }
        return count;
    }

    private String generateRecoveryToken() {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder("SK-");
        for (int i = 0; i < 16; i++) {
            if (i > 0 && i % 4 == 0) sb.append("-");
            sb.append(chars.charAt(random.nextInt(chars.length())));
        }
        return sb.toString();
    }
}

