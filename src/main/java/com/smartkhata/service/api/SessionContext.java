package com.smartkhata.service.api;

import com.smartkhata.model.entity.Business;
import com.smartkhata.model.entity.User;
import com.smartkhata.model.enums.UserRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Thread-safe local desktop session context providing authenticated user state
 * and role-based authorization helpers without cloud server dependencies.
 */
public final class SessionContext {

    private static final Logger log = LoggerFactory.getLogger(SessionContext.class);

    private static volatile User currentUser = null;
    private static volatile Business currentBusiness = null;
    private static volatile boolean isLocked = false;

    private SessionContext() {}

    public static synchronized void setSession(Business business, User user) {
        currentBusiness = business;
        currentUser = user;
        isLocked = false;
        log.info("Session established for user [{}] (Role: {}) in business [{}]",
                user.username(), user.role(), business.name());
    }

    public static synchronized void clearSession() {
        if (currentUser != null) {
            log.info("Clearing session for user [{}]", currentUser.username());
        }
        currentUser = null;
        currentBusiness = null;
        isLocked = false;
    }

    public static synchronized void lock() {
        if (currentUser != null) {
            isLocked = true;
            log.info("Session locked for user [{}]", currentUser.username());
        }
    }

    public static synchronized void unlock() {
        if (currentUser != null) {
            isLocked = false;
            log.info("Session unlocked for user [{}]", currentUser.username());
        }
    }

    public static boolean isAuthenticated() {
        return currentUser != null && !isLocked;
    }

    public static boolean isLocked() {
        return isLocked;
    }

    public static User getCurrentUser() {
        return currentUser;
    }

    public static Business getCurrentBusiness() {
        return currentBusiness;
    }

    public static long getRequiredBusinessId() {
        requireAuthenticated();
        return currentBusiness.id();
    }

    public static void requireAuthenticated() {
        if (currentUser == null) {
            throw new SecurityException("Access denied: User is not authenticated.");
        }
        if (isLocked) {
            throw new SecurityException("Access denied: Application session is locked.");
        }
    }

    public static void requireOwner() {
        requireAuthenticated();
        if (currentUser.role() != UserRole.OWNER) {
            log.warn("Unauthorized action attempted by non-owner user [{}]", currentUser.username());
            throw new SecurityException("Access denied: Action requires OWNER role permissions.");
        }
    }

    public static boolean isOwner() {
        return currentUser != null && currentUser.role() == UserRole.OWNER;
    }

    public static synchronized void updateCurrentBusiness(Business business) {
        if (business != null) {
            currentBusiness = business;
            log.info("Current business updated in session to [{}]", business.name());
        }
    }
}

