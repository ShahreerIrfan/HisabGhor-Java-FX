package com.smartkhata.ui;

import atlantafx.base.theme.PrimerDark;
import atlantafx.base.theme.PrimerLight;
import javafx.application.Application;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages desktop application visual themes using AtlantaFX.
 */
public final class ThemeManager {

    private static final Logger log = LoggerFactory.getLogger(ThemeManager.class);
    private static boolean isDarkMode = false;

    private ThemeManager() {}

    /**
     * Applies the default Primer Light theme matching docs/dashboard-reference.png.
     */
    public static void applyDefaultTheme() {
        applyTheme(false);
    }

    /**
     * Applies either Light or Dark AtlantaFX theme.
     */
    public static void applyTheme(boolean darkMode) {
        isDarkMode = darkMode;
        if (darkMode) {
            Application.setUserAgentStylesheet(new PrimerDark().getUserAgentStylesheet());
            log.info("Applied AtlantaFX Primer Dark theme");
        } else {
            Application.setUserAgentStylesheet(new PrimerLight().getUserAgentStylesheet());
            log.info("Applied AtlantaFX Primer Light theme");
        }
    }

    public static boolean isDarkMode() {
        return isDarkMode;
    }
}

