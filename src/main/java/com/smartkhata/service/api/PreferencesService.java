package com.smartkhata.service.api;

import com.smartkhata.model.dto.AppPreferencesDto;
import com.smartkhata.model.dto.BusinessProfileUpdateDto;
import com.smartkhata.model.dto.InAppNotificationDto;
import com.smartkhata.model.entity.Business;

import java.util.List;

/**
 * Service managing user preferences, store metadata, and local proactive in-app notifications.
 */
public interface PreferencesService {

    /**
     * Retrieves the stored application preferences for the active business session.
     */
    AppPreferencesDto getPreferences();

    /**
     * Validates and updates user preferences in SQLite.
     */
    AppPreferencesDto updatePreferences(AppPreferencesDto preferences);

    /**
     * Updates business profile attributes along with invoice branding.
     */
    Business updateBusinessProfile(BusinessProfileUpdateDto dto, String invoiceHeader, String invoiceFooter, String logoPath);

    /**
     * Calculates active in-app notifications (overdue balances, low-stock items).
     */
    List<InAppNotificationDto> getActiveNotifications();

    /**
     * Lists non-secret application settings key-value pairs for the current business.
     */
    List<com.smartkhata.model.dto.SettingEntryDto> listCustomSettings();

    /**
     * Creates or updates a custom setting key-value pair.
     */
    void upsertSetting(String key, String value);

    /**
     * Deletes a custom setting key.
     */
    void deleteSetting(String key);

    /**
     * Queries recent audit logs for the current store.
     */
    List<com.smartkhata.model.dto.AuditLogListItemDto> searchAuditLogs(String query, int limit);

    /**
     * Records a manual or system audit trail entry.
     */
    void recordAuditLog(String actionType, String entityName, Long entityId, String changeSummary);

    /**
     * Deletes a specific audit log entry (Owner only).
     */
    void deleteAuditLog(long id);

    /**
     * Clears all audit logs for the active store (Owner only).
     */
    void clearAuditLogs();
}

