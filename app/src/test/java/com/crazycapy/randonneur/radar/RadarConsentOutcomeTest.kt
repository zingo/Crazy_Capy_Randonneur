/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.radar

import android.app.Activity
import es.jjrh.bikeradar.ipc.RadarContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class RadarConsentOutcomeTest {

    @Test
    fun okWithReadRefreshesTheGrant() {
        assertEquals(ConsentOutcome.GRANTED, consentOutcome(Activity.RESULT_OK, readGranted = true))
    }

    @Test
    fun okWithoutReadReReadsTheRealState() {
        assertEquals(ConsentOutcome.REFRESH, consentOutcome(Activity.RESULT_OK, readGranted = false))
    }

    @Test
    fun cancelLeavesAPreviousGrantAlone() {
        assertEquals(ConsentOutcome.REFRESH, consentOutcome(Activity.RESULT_CANCELED, readGranted = false))
    }

    @Test
    fun anUnknownResultCodeIssuesNoNewGrant() {
        assertEquals(ConsentOutcome.REFRESH, consentOutcome(42, readGranted = false))
        assertEquals(ConsentOutcome.REFRESH, consentOutcome(Activity.RESULT_FIRST_USER + 99, readGranted = false))
        assertEquals(ConsentOutcome.REFRESH, consentOutcome(Int.MIN_VALUE, readGranted = false))
    }

    @Test
    fun theThreeExplicitRefusalsStayErrors() {
        listOf(
            RadarContract.Consent.RESULT_NOT_STORED,
            RadarContract.Consent.RESULT_RIDE_IN_PROGRESS,
            RadarContract.Consent.RESULT_CALLER_UNKNOWN,
        ).forEach { code ->
            assertEquals(ConsentOutcome.REFUSED, consentOutcome(code, readGranted = false))
            assertNotNull(consentStatus(code))
        }
    }

    @Test
    fun aRefusalWithAnOkCodeDoesNotBecomeAGrant() {
        assertEquals(
            ConsentOutcome.REFUSED,
            consentOutcome(RadarContract.Consent.RESULT_NOT_STORED, readGranted = true),
        )
    }
}
