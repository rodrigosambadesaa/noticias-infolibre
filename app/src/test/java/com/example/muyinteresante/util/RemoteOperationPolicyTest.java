package com.example.muyinteresante.util;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RemoteOperationPolicyTest {
    @Test
    public void offlineDoesNotStartRemoteRequest() {
        assertFalse(RemoteOperationPolicy.canStartRemoteRequest(false));
    }

    @Test
    public void connectedRequestIsAllowed() {
        assertTrue(RemoteOperationPolicy.canStartRemoteRequest(true));
    }

    @Test
    public void successfulServerResponseDoesNotTriggerGeneralDiagnostic() {
        assertFalse(RemoteOperationPolicy.shouldDiagnoseAfterFailure(true, true));
    }

    @Test
    public void validHttpFailureDoesNotTriggerGeneralDiagnostic() {
        assertFalse(RemoteOperationPolicy.shouldDiagnoseAfterFailure(true, false));
    }

    @Test
    public void ambiguousFailureTriggersGeneralDiagnostic() {
        assertTrue(RemoteOperationPolicy.shouldDiagnoseAfterFailure(false, true));
    }
}
