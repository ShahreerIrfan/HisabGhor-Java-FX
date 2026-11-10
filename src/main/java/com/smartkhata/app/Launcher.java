package com.smartkhata.app;

/**
 * Standard un-inherited launch class to bootstrap JavaFX desktop application
 * cleanly across standard Classpath, modular runtime, and native jpackage bundles.
 */
public final class Launcher {

    private Launcher() {}

    public static void main(String[] args) {
        SmartKhataApp.main(args);
    }
}

