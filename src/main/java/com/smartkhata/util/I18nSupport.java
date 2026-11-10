package com.smartkhata.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;
import java.util.function.Consumer;

/**
 * Thread-safe Internationalization (i18n) provider for SmartKhata.
 * Backed by UTF-8 resource bundles for English ('en') and Bengali ('bn').
 */
public final class I18nSupport {

    private static final Logger log = LoggerFactory.getLogger(I18nSupport.class);
    private static final String BUNDLE_BASE = "com.smartkhata.i18n.messages";

    private static Locale currentLocale = Locale.ENGLISH;
    private static ResourceBundle bundle;
    private static final List<Consumer<Locale>> listeners = new ArrayList<>();

    static {
        loadBundle();
    }

    private I18nSupport() {}

    private static synchronized void loadBundle() {
        try {
            bundle = ResourceBundle.getBundle(BUNDLE_BASE, currentLocale);
            log.info("Loaded resource bundle for locale: {}", currentLocale);
        } catch (MissingResourceException e) {
            log.warn("Could not find bundle for locale {}, falling back to English root: {}", currentLocale, e.getMessage());
            bundle = ResourceBundle.getBundle(BUNDLE_BASE, Locale.ROOT);
        }
    }

    public static synchronized Locale getCurrentLocale() {
        return currentLocale;
    }

    public static synchronized void setLocale(Locale newLocale) {
        if (newLocale == null) {
            newLocale = Locale.ENGLISH;
        }
        if (!currentLocale.getLanguage().equalsIgnoreCase(newLocale.getLanguage())) {
            currentLocale = newLocale;
            loadBundle();
            notifyListeners();
        }
    }

    public static synchronized void setLanguage(String langCode) {
        if ("bn".equalsIgnoreCase(langCode)) {
            setLocale(Locale.forLanguageTag("bn"));
        } else {
            setLocale(Locale.ENGLISH);
        }
    }

    public static synchronized String get(String key) {
        if (key == null || key.isBlank()) return "";
        try {
            return bundle.getString(key);
        } catch (MissingResourceException e) {
            return key;
        }
    }

    public static synchronized String get(String key, Object... args) {
        String pattern = get(key);
        if (args == null || args.length == 0) return pattern;
        try {
            return MessageFormat.format(pattern, args);
        } catch (Exception e) {
            return pattern;
        }
    }

    public static synchronized void addLocaleChangeListener(Consumer<Locale> listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public static synchronized void removeLocaleChangeListener(Consumer<Locale> listener) {
        listeners.remove(listener);
    }

    private static synchronized void notifyListeners() {
        for (Consumer<Locale> listener : listeners) {
            try {
                listener.accept(currentLocale);
            } catch (Exception e) {
                log.error("Error executing locale change listener: {}", e.getMessage(), e);
            }
        }
    }
}

