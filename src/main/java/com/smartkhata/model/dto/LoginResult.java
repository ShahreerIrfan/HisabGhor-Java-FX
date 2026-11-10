package com.smartkhata.model.dto;

import com.smartkhata.model.entity.User;

public record LoginResult(
        Status status,
        String message,
        long lockoutRemainingSeconds,
        User user
) {
    public enum Status {
        SUCCESS,
        INVALID_CREDENTIALS,
        THROTTLED,
        USER_NOT_FOUND,
        INACTIVE
    }

    public static LoginResult success(User user) {
        return new LoginResult(Status.SUCCESS, "Login successful", 0, user);
    }

    public static LoginResult invalid(int remainingAttempts) {
        return new LoginResult(Status.INVALID_CREDENTIALS,
                "Invalid credentials. " + remainingAttempts + " attempts remaining.", 0, null);
    }

    public static LoginResult throttled(long seconds) {
        return new LoginResult(Status.THROTTLED,
                "Too many failed attempts. Login locked for " + seconds + " seconds.", seconds, null);
    }

    public static LoginResult notFound() {
        return new LoginResult(Status.USER_NOT_FOUND, "User account not found.", 0, null);
    }

    public boolean isSuccess() {
        return status == Status.SUCCESS;
    }
}

