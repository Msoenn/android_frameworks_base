/*
 * Copyright (C) 2026 The Circa Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.circa.shade

import java.net.URLDecoder
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/*
 * The pure parts of theater mode and Bedtime (no Android imports, so they are checked on the host:
 * docs/watch-ui/circa/bedtime.md "Tests").
 */

/**
 * Settings that one or more quiet modes (theater, Bedtime) force while they are on.
 *
 * The first mode that forces a key saves the key's value from before; the value goes back when the
 * last mode that forces it ends - and only if the key still holds what the modes wrote, so a change
 * the user made in between is kept. Modes may end in any order. Serialised into one
 * Settings.Secure string so a SystemUI restart in the middle of a mode still restores.
 */
class ModeOverrideBook {
    /** What a key held before the modes, and what they wrote. null = the key was unset. */
    data class Saved(val original: String?, val applied: String)

    private val modes = LinkedHashMap<String, Map<String, String>>()
    private val saved = LinkedHashMap<String, Saved>()

    fun activeModes(): Set<String> = modes.keys

    fun saved(key: String): Saved? = saved[key]

    fun isEmpty(): Boolean = modes.isEmpty() && saved.isEmpty()

    /**
     * Turns [mode] on with [overrides] (key -> value it needs), or off with null. Returns the
     * writes to make: key -> new value (null = delete the key). [current] reads a key now.
     * Calling it again with the same arguments writes nothing (a SystemUI restart re-applies).
     */
    fun set(
        mode: String,
        overrides: Map<String, String>?,
        current: (String) -> String?,
    ): Map<String, String?> {
        if (overrides == null) modes.remove(mode) else modes[mode] = overrides
        val desired = LinkedHashMap<String, String>()
        for (o in modes.values) desired.putAll(o)
        val writes = LinkedHashMap<String, String?>()
        for ((key, value) in desired) {
            val s = saved[key]
            if (s == null) {
                saved[key] = Saved(current(key), value)
                writes[key] = value
            } else if (s.applied != value) {
                saved[key] = s.copy(applied = value)
                writes[key] = value
            }
        }
        val it = saved.entries.iterator()
        while (it.hasNext()) {
            val (key, s) = it.next()
            if (key in desired) continue
            if (current(key) == s.applied) writes[key] = s.original
            it.remove()
        }
        return writes
    }

    fun encode(): String {
        val sb = StringBuilder()
        for ((mode, o) in modes) {
            sb.append("m:").append(enc(mode))
            for ((k, v) in o) sb.append(',').append(enc(k)).append('=').append(enc(v))
            sb.append(';')
        }
        for ((k, s) in saved) {
            sb.append("k:").append(enc(k)).append('=')
            sb.append(if (s.original == null) "!" else "v" + enc(s.original))
            sb.append('|').append(enc(s.applied)).append(';')
        }
        return sb.toString()
    }

    companion object {
        private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

        private fun dec(s: String): String = URLDecoder.decode(s, "UTF-8")

        /** The book in [text] (from [encode]); an empty book for null or anything unreadable. */
        fun decode(text: String?): ModeOverrideBook {
            val b = ModeOverrideBook()
            if (text.isNullOrEmpty()) return b
            try {
                for (part in text.split(';')) {
                    when {
                        part.startsWith("m:") -> {
                            val f = part.substring(2).split(',')
                            val o = LinkedHashMap<String, String>()
                            for (kv in f.drop(1)) {
                                val i = kv.indexOf('=')
                                if (i > 0) o[dec(kv.substring(0, i))] = dec(kv.substring(i + 1))
                            }
                            b.modes[dec(f[0])] = o
                        }
                        part.startsWith("k:") -> {
                            val body = part.substring(2)
                            val i = body.indexOf('=')
                            val j = body.lastIndexOf('|')
                            if (i <= 0 || j < i) continue
                            val orig = body.substring(i + 1, j)
                            b.saved[dec(body.substring(0, i))] =
                                Saved(
                                    if (orig == "!") null else dec(orig.removePrefix("v")),
                                    dec(body.substring(j + 1)),
                                )
                        }
                    }
                }
            } catch (e: IllegalArgumentException) {
                return ModeOverrideBook()
            }
            return b
        }

        /**
         * The book for theater mode's old store (`circa_theater_saved`: `key=value;...;ringer=n`,
         * value "null" = unset) with [mode] on and every listed key forced to [forced].
         * Unknown keys (the ringer) are left to the caller.
         */
        fun fromLegacyTheater(
            mode: String,
            saved: String,
            keys: List<String>,
            forced: String,
        ): ModeOverrideBook {
            val b = ModeOverrideBook()
            val o = LinkedHashMap<String, String>()
            for (entry in saved.split(';')) {
                val i = entry.indexOf('=')
                if (i <= 0) continue
                val key = entry.substring(0, i)
                if (key !in keys) continue
                val v = entry.substring(i + 1)
                b.saved[key] = Saved(if (v == "null") null else v, forced)
                o[key] = forced
            }
            b.modes[mode] = o
            return b
        }
    }
}

/**
 * The Bedtime schedule: from [startMin] to [endMin] (minutes of the local day; an end at or before
 * the start is on the next day) on the days in [days] (bit 0 = Monday ... bit 6 = Sunday; the day
 * the window starts on). Wall-clock times, so 23:00 stays 23:00 across a DST change.
 */
data class BedtimeSchedule(
    val enabled: Boolean = false,
    val startMin: Int = 23 * 60,
    val endMin: Int = 7 * 60,
    val days: Int = ALL_DAYS,
) {
    /** One schedule boundary: at [atMillis] Bedtime starts ([start] = true) or ends. */
    data class Edge(val atMillis: Long, val start: Boolean)

    fun encode(): String = "${if (enabled) 1 else 0};$startMin;$endMin;$days"

    private fun durationMin(): Int {
        val d = Math.floorMod(endMin - startMin, DAY_MIN)
        return if (d == 0) DAY_MIN else d
    }

    private fun windowsAround(nowMillis: Long, zone: ZoneId): List<Pair<Long, Long>> {
        if (!enabled || days and ALL_DAYS == 0) return emptyList()
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        val out = ArrayList<Pair<Long, Long>>()
        for (offset in -2L..8L) {
            val date: LocalDate = today.plusDays(offset)
            if (days and (1 shl (date.dayOfWeek.value - 1)) == 0) continue
            val start = at(date, startMin, zone)
            val endDate = if (startMin + durationMin() >= DAY_MIN) date.plusDays(1) else date
            val end = at(endDate, (startMin + durationMin()) % DAY_MIN, zone)
            if (end > start) out.add(start to end)
        }
        return out
    }

    /** True while [nowMillis] is inside a Bedtime window. */
    fun inWindow(nowMillis: Long, zone: ZoneId): Boolean =
        windowsAround(nowMillis, zone).any { nowMillis >= it.first && nowMillis < it.second }

    /** The first boundary strictly after [nowMillis], or null when the schedule is off. */
    fun nextEdge(nowMillis: Long, zone: ZoneId): Edge? =
        edges(nowMillis, zone).filter { it.atMillis > nowMillis }.minByOrNull { it.atMillis }

    /** The last boundary at or before [nowMillis] (at most two days back), or null. */
    fun lastEdge(nowMillis: Long, zone: ZoneId): Edge? =
        edges(nowMillis, zone).filter { it.atMillis <= nowMillis }.maxByOrNull { it.atMillis }

    private fun edges(nowMillis: Long, zone: ZoneId): List<Edge> {
        val w = windowsAround(nowMillis, zone)
        // Back-to-back windows (an every-day 24 h schedule): the shared boundary is no edge.
        val starts = w.map { it.first }.toSet()
        val ends = w.map { it.second }.toSet()
        return w.flatMap { (s, e) ->
            listOfNotNull(
                Edge(s, true).takeIf { s !in ends },
                Edge(e, false).takeIf { e !in starts },
            )
        }
    }

    companion object {
        const val ALL_DAYS = 0b1111111
        const val DAY_MIN = 24 * 60

        /** The default: 23:00 - 07:00 every day, off. */
        val DEFAULT = BedtimeSchedule()

        private fun at(date: LocalDate, minute: Int, zone: ZoneId): Long =
            ZonedDateTime.of(LocalDateTime.of(date, LocalTime.of(minute / 60, minute % 60)), zone)
                .toInstant()
                .toEpochMilli()

        /** `enabled;startMin;endMin;days`; [DEFAULT] for null or anything unreadable. */
        fun decode(text: String?): BedtimeSchedule {
            val f = text?.trim()?.split(';') ?: return DEFAULT
            if (f.size < 4) return DEFAULT
            val start = f[1].toIntOrNull() ?: return DEFAULT
            val end = f[2].toIntOrNull() ?: return DEFAULT
            val days = f[3].toIntOrNull() ?: return DEFAULT
            if (start !in 0 until DAY_MIN || end !in 0 until DAY_MIN) return DEFAULT
            return BedtimeSchedule(f[0] == "1", start, end, days and ALL_DAYS)
        }

        /** "Start when asleep" applies only from 20:00 to 10:00 local time. */
        const val SLEEP_START_FROM_MIN = 20 * 60
        const val SLEEP_START_UNTIL_MIN = 10 * 60

        fun sleepStartAllowed(minuteOfDay: Int): Boolean =
            minuteOfDay >= SLEEP_START_FROM_MIN || minuteOfDay < SLEEP_START_UNTIL_MIN

        /**
         * What a schedule boundary [edge] does to Bedtime, given the last one already handled
         * ([handledMillis]): true = turn on, false = turn off, null = nothing (already handled).
         */
        fun edgeAction(edge: Edge?, handledMillis: Long): Boolean? =
            if (edge == null || edge.atMillis <= handledMillis) null else edge.start
    }
}
