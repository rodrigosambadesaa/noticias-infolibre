package com.example.muyinteresanteNoTocar;

import java.net.ConnectException;
import java.net.UnknownHostException;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DescargaNoticiasRSSTest {
    @Test
    public void classifiesUnknownHostAsAmbiguousConnectivityFailure() {
        assertTrue(DescargaNoticiasRSS.isConnectivityException(new UnknownHostException("feed")));
    }

    @Test
    public void classifiesNestedConnectExceptionAsAmbiguousConnectivityFailure() {
        assertTrue(DescargaNoticiasRSS.isConnectivityException(
                new RuntimeException(new ConnectException("timeout"))));
    }

    @Test
    public void doesNotClassifyParsingFailureAsConnectivityFailure() {
        assertFalse(DescargaNoticiasRSS.isConnectivityException(new IllegalStateException("bad XML")));
    }
}
