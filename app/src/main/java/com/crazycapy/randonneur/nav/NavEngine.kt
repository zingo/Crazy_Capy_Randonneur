/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.nav

import com.crazycapy.randonneur.gpx.Track
import com.crazycapy.randonneur.gpx.TrackPoint
import kotlin.math.cos

/** Track-following navigation engine. Pure Kotlin (no Android deps). */
class NavEngine(initialTrack: Track? = null) {

    var track: Track? = initialTrack
        private set

    private var turns = emptyList<Turn>()
        private set

    /** Direction-resolved track the cursor lives on (reversed when [reverse]). */
    private var activeTrack: Track? = null

    /** True when the route is being ridden from the end to the start. */
    var reverse: Boolean = false
        private set

    /** Monotonic cursor along the active route, meters. */
    var distanceAlongM = 0.0
        private set

    private var segmentIndex = 0
    private var turnCursor = 0
    private var nextTurnPos = -1
    private val announcedLeads = HashMap<Int, MutableSet<Int>>()
    private var nearAnnounced = HashSet<Int>()
    private var nowAnnounced = HashSet<Int>()
    private var arrived = false
    private var wasOffRoute = false
    private var offRouteFixCount = 0
    private var lastGoStraightAtM = 0.0

    /** Distance beyond which we consider ourselves off the route. */
    var offRouteThresholdM = 45.0

    /** Every N fixes while off the route, emit an [NavEvent.OffRouteStill] reminder. */
    var offRouteRepeatEveryFixes = 8

    /** Time-based advance notices (seconds before the turn), speed dependent. */
    var approachLeadsS = listOf(50, 20)

    /** Meters before a turn that trigger the near-turn notice (with distance to the next). */
    var nearWindowM = 200.0

    /** Meters before a turn that trigger TurnNow. */
    var nowWindowM = 60.0

    /** Beyond this distance the engine emits periodic "go on for x.x km" notices. */
    var farWindowM = 500.0

    /** Every N meters of progress, while the next turn is far, emit a GoStraight notice. */
    var goStraightEveryM = 1000.0

    private val listeners = ArrayList<(NavEvent) -> Unit>()

    init {
        initialTrack?.let { setTrack(it) }
    }

    fun setTrack(track: Track) {
        this.track = track
        reverse = false
        activeTrack = track
        resetForTrack(track)
    }

    /** Flip the riding direction mid-ride. The next fix re-snaps to the route,
     *  so the cursor carries on from the rider's current position. */
    fun setReverse(enabled: Boolean) {
        val t = track ?: return
        if (enabled == reverse) return
        reverse = enabled
        activeTrack = if (enabled) t.reversed() else t
        resetForTrack(activeTrack!!)
    }

    private fun resetForTrack(track: Track) {
        this.turns = TurnFinder.find(track)
        this.segmentIndex = 0
        this.turnCursor = 0
        this.nextTurnPos = -1
        this.distanceAlongM = 0.0
        this.announcedLeads.clear()
        this.nearAnnounced.clear()
        this.nowAnnounced.clear()
        this.lastGoStraightAtM = -goStraightEveryM
        this.arrived = false
        this.wasOffRoute = false
        this.offRouteFixCount = 0
    }

    fun addListener(listener: (NavEvent) -> Unit) {
        listeners.add(listener)
    }

    /** Snap and advance the engine with a raw GPS fix. [speedKmh] estimated current speed (0 if unknown). */
    fun update(lat: Double, lon: Double, speedKmh: Double = 0.0) {
        val track = activeTrack ?: return
        if (track.points.size < 2) return

        val snap = snapToRoute(track, lat, lon)
        val offTrack = snap.distM > offRouteThresholdM

        if (offTrack) {
            if (!wasOffRoute) {
                wasOffRoute = true
                offRouteFixCount = 0
                listeners.forEach { it(NavEvent.OffRoute(lat, lon, snap.nearest, snap.distM)) }
            } else {
                offRouteFixCount++
                if (offRouteFixCount >= offRouteRepeatEveryFixes) {
                    offRouteFixCount = 0
                    listeners.forEach { it(NavEvent.OffRouteStill(lat, lon, snap.nearest, snap.distM)) }
                }
            }
            return
        }

        if (wasOffRoute) {
            wasOffRoute = false
            offRouteFixCount = 0
            listeners.forEach { it(NavEvent.BackOnRoute(lat, lon)) }
        }

        if (snap.alongM > distanceAlongM) distanceAlongM = snap.alongM

        if (!arrived && distanceAlongM >= track.lengthMeters - 10.0) {
            arrived = true
            listeners.forEach { it(NavEvent.Arrived(lat, lon)) }
        }

        val next = peekNextTurn()
        if (next != null) {
            val dist = next.distAlongM - distanceAlongM

            // Time-based advance notices: when within N seconds (at current speed)
            // of the turn, announce the approach. Speed gates the distance.
            if (speedKmh > 0.0) {
                val announced = announcedLeads.getOrPut(next.index) { HashSet() }
                for (leadS in approachLeadsS) {
                    if (announced.contains(leadS)) continue
                    val leadM = speedKmh * leadS / 3.6
                    if (dist > nowWindowM && dist <= leadM + 1.0) {
                        announced.add(leadS)
                        listeners.forEach { it(NavEvent.TurnApproachAt(next, leadS, dist.coerceAtLeast(0.0))) }
                    }
                }
            }

            // Near-turn notice: within a fixed window, plus the gap to the next turn.
            if (dist <= nearWindowM && nearAnnounced.add(next.index)) {
                val after = peekTurnAfter(next.position)
                val gap = after?.let { it.distAlongM - next.distAlongM }
                listeners.forEach { it(NavEvent.TurnNear(next, dist.coerceAtLeast(0.0), after, gap)) }
            }

            if (dist <= nowWindowM && nowAnnounced.add(next.index)) {
                listeners.forEach { it(NavEvent.TurnNow(next)) }
            }

            // While the next turn is far ahead, periodically say "go on for x.x km".
            if (dist > farWindowM) {
                val sinceLast = distanceAlongM - lastGoStraightAtM
                if (sinceLast < 0.0 || sinceLast >= goStraightEveryM) {
                    lastGoStraightAtM = distanceAlongM
                    listeners.forEach { it(NavEvent.GoStraight(dist)) }
                }
            }
        }

        // Fire TurnPassed for any turn we've now left behind, even if it wasn't
        // picked up by peekNextTurn above.
        while (turnCursor < turns.size && distanceAlongM - turns[turnCursor].distAlongM >= 10.0) {
            val passed = turns[turnCursor]
            turnCursor++
            listeners.forEach { it(NavEvent.TurnPassed(passed)) }
        }

        listeners.forEach {
            it(
                NavEvent.OnTrack(
                    lat = lat,
                    lon = lon,
                    distanceAlongM = distanceAlongM,
                    distanceByRouteM = remainingM,
                    nextTurn = peekNextTurn(),
                    distanceToNextTurnM = distanceToNextTurn,
                )
            )
        }
    }

    /** Jump the cursor to [distanceM] along the active route (mid-route resume),
     *  skipping turns already behind and pre-firing bookkeeping so no old
     *  announcements repeat. The next [update] snaps to the rider's real position. */
    fun seedAlong(distanceM: Double) {
        val t = activeTrack ?: return
        val d = distanceM.coerceIn(0.0, t.lengthMeters)
        distanceAlongM = d
        var i = 0
        while (i < t.points.size - 1 && t.distanceAt(i + 1) < d) i++
        segmentIndex = i
        val firstAhead = turns.indexOfFirst { it.distAlongM > d + 5.0 }
        turnCursor = if (firstAhead < 0) turns.size else firstAhead
        for (j in turnCursor - 1 downTo 0) {
            val tr = turns[j]
            nearAnnounced.add(tr.index)
            nowAnnounced.add(tr.index)
            announcedLeads[tr.index] = HashSet(approachLeadsS)
        }
        // Baseline the go-straight cadence so no boilerplate fires right after
        // the resume; the next "go on for x.x km" comes a full window later.
        lastGoStraightAtM = d
        arrived = false
        wasOffRoute = false
        offRouteFixCount = 0
    }

    fun peekNextTurn(): Turn? {
        val t = activeTrack ?: return null
        for (i in turnCursor until turns.size) {
            val tr = turns[i]
            if (tr.distAlongM > distanceAlongM + 5.0) {
                nextTurnPos = i
                return tr
            }
        }
        nextTurnPos = -1
        return null
    }

    /** The turn after [turn] in the turns list, if any. */
    private fun peekTurnAfter(position: Int): Turn? {
        val n = position + 1
        return if (n < turns.size) turns[n] else null
    }

    val distanceToNextTurn: Double?
        get() = peekNextTurn()?.let { it.distAlongM - distanceAlongM }

    val remainingM: Double
        get() = (activeTrack?.lengthMeters ?: 0.0) - distanceAlongM

    /** Up to [maxPoints] points from the active route starting [metersAhead] ahead of the
     *  cursor, sampled every [stepM], used to draw the popup's zoomed-in preview. */
    fun upcomingRoute(metersAhead: Double = 300.0, stepM: Double = 30.0, maxPoints: Int = 12): List<TrackPoint> {
        val t = activeTrack ?: return emptyList()
        if (t.points.isEmpty()) return emptyList()
        val pts = ArrayList<TrackPoint>(maxPoints)
        var d = distanceAlongM
        var guard = 0
        while (d <= distanceAlongM + metersAhead && guard < 100 && pts.size < maxPoints) {
            val p = t.pointAtDistance(d) ?: break
            pts.add(p)
            d += stepM
            guard++
        }
        return pts
    }

    /** Whether the rider is currently outside the route corridor. */
    val isOffRoute: Boolean
        get() = wasOffRoute

    private class SnapResult(val seg: Int, val alongM: Double, val distM: Double, val nearest: Pair<Double, Double>)

    private fun snapToRoute(track: Track, lat: Double, lon: Double): SnapResult {
        val n = track.points.size
        val window = 120
        var lo = (segmentIndex - window).coerceAtLeast(0)
        var hi = (segmentIndex + window + 1).coerceAtMost(n - 1)
        if (distanceAlongM == 0.0) {
            lo = 0
            hi = n - 1
        }

        var best = SnapResult(0, 0.0, Double.MAX_VALUE, track.points[0].toPair())
        for (i in lo until hi) {
            val a = track.points[i]
            val b = track.points[i + 1]
            val d = Geo.pointSegmentDistance(lat, lon, a.lat, a.lon, b.lat, b.lon)
            if (d < best.distM) {
                val xScale = 111_320.0 * cos(Math.toRadians(a.lat))
                val yScale = 111_320.0
                val ax = (lon - a.lon) * xScale
                val ay = (lat - a.lat) * yScale
                val abx = (b.lon - a.lon) * xScale
                val aby = (b.lat - a.lat) * yScale
                val len2 = abx * abx + aby * aby
                val t = if (len2 < 1e-12) 0.0 else ((ax * abx + ay * aby) / len2).coerceIn(0.0, 1.0)
                val along = track.distanceAt(i) + t * (track.distanceAt(i + 1) - track.distanceAt(i))
                val projLat = a.lat + (b.lat - a.lat) * t
                val projLon = a.lon + (b.lon - a.lon) * t
                best = SnapResult(i, along, d, projLat to projLon)
            }
        }
        segmentIndex = best.seg
        return best
    }
}

private fun TrackPoint.toPair(): Pair<Double, Double> = lat to lon