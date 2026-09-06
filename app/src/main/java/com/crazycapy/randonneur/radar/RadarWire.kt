/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
/*
 * RadarWire — the overlay app's wire types mapped onto our own model.
 *
 * The files under es.jjrh.bikeradar.ipc are copied from that app under their
 * own licence and reference nothing here, so the mapping lives on this side
 * rather than inside them.
 */
package com.crazycapy.randonneur.radar

import es.jjrh.bikeradar.ipc.RadarContract
import es.jjrh.bikeradar.ipc.RadarStateParcel
import es.jjrh.bikeradar.ipc.RadarVehicleParcel

/**
 * The size codes this stream can actually report. RADAR_SIZE_BIKE is left out
 * on purpose: the contract reserves it and never emits it, and a consumer that
 * colours by class must not present a bike as something the radar produced.
 */
private val KNOWN_SIZES = setOf(
    RadarContract.RADAR_SIZE_CAR,
    RadarContract.RADAR_SIZE_TRUCK,
)

/**
 * The targets in a snapshot, each carrying how far its numbers can be trusted.
 *
 * A range-only radar still arrives with every field filled in, so dropping the
 * capability bits is what turns a default lateral offset into a dot drawn in
 * the rider's lane as though it had been measured.
 */
fun RadarStateParcel.toDomain(): List<RadarVehicle> {
    val closingKnown = capabilities and RadarContract.HAS_CLOSING_SPEED != 0
    val lateralOnStream = capabilities and RadarContract.HAS_LATERAL != 0
    val classOnStream = capabilities and RadarContract.HAS_VEHICLE_SIZE != 0
    return vehicles.map { it.toDomain(closingKnown, lateralOnStream, classOnStream) }
}

/**
 * A size code this build does not recognise is not a measurement, whatever the
 * stream can do. A later contract version may add one, and the version itself
 * moves only when the layout does.
 */
private fun RadarVehicleParcel.toDomain(
    closingKnown: Boolean,
    lateralOnStream: Boolean,
    classOnStream: Boolean,
): RadarVehicle = RadarVehicle(
    id = id,
    distanceM = distanceM,
    closingKmh = closingKmh,
    size = when {
        !classOnStream -> RadarVehicleSize.CAR
        size == RadarContract.RADAR_SIZE_TRUCK -> RadarVehicleSize.TRUCK
        else -> RadarVehicleSize.CAR
    },
    lateralPos = lateralPos,
    rangeXm = rangeXm,
    isAhead = isAhead,
    closingKnown = closingKnown,
    // The stream capability is deliberately re-checked here. The contract says
    // the per-frame flag already folds it in, so this is a guard against a
    // producer-side bug on a flag the map trusts, not a requirement.
    lateralKnown = lateralOnStream && lateralKnown,
    sizeKnown = classOnStream && size in KNOWN_SIZES,
)
