/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.state

/*
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │  RideMode — what is driving the ride right now.                        │
 * │                                                                         │
 * │    IDLE  ── no ride active                                              │
 * │    GPS   ── real GPS navigation                                         │
 * │    GHOST ── simulated ride (RouteSimulator feeding synthetic fixes)     │
 * │                                                                         │
 * │  Transitions:                                                           │
 * │    IDLE → (startGps / startGhost) → GPS / GHOST → (stop) → IDLE        │
 * └─────────────────────────────────────────────────────────────────────────┘
 */
enum class RideMode { IDLE, GPS, GHOST }
