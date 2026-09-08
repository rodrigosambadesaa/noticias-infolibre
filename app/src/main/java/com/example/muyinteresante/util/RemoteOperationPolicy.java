package com.example.muyinteresante.util;

/** Centraliza las decisiones baratas que preceden a operaciones remotas. */
public final class RemoteOperationPolicy {
    private RemoteOperationPolicy() { }

    public static boolean canStartRemoteRequest(boolean connected) {
        return connected;
    }

    public static boolean canStartRemoteRequest(boolean connected, boolean hasPhysicalNetwork) {
        return connected && hasPhysicalNetwork;
    }

    public static boolean shouldDiagnoseAfterFailure(boolean serverResponded,
                                                       boolean ambiguousConnectivityFailure) {
        return !serverResponded && ambiguousConnectivityFailure;
    }
}
