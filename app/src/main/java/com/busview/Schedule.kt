package com.busview

import android.content.Context
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

class Departure(val time: ZonedDateTime, val line: String, val stop: String)

object Prefs {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("busview", Context.MODE_PRIVATE)

    fun home(ctx: Context) = sp(ctx).getString("home", null)?.let { Place.fromJson(JSONObject(it)) }
    fun work(ctx: Context) = sp(ctx).getString("work", null)?.let { Place.fromJson(JSONObject(it)) }
    fun priority(ctx: Context): List<String> =
        sp(ctx).getString("priority", "")!!.split(',', ' ', ';').map { it.trim() }.filter { it.isNotEmpty() }
    fun priorityText(ctx: Context) = sp(ctx).getString("priority", "")!!

    fun save(ctx: Context, home: Place, work: Place, priority: String) {
        sp(ctx).edit()
            .putString("home", home.toJson().toString())
            .putString("work", work.toJson().toString())
            .putString("priority", priority)
            .apply()
    }

    /** Direction chosen with the widget arrow; valid only until the next 00:00 / 12:00 switch. */
    fun override(ctx: Context): Pair<Int, Long> =
        Pair(sp(ctx).getInt("ovDir", -1), sp(ctx).getLong("ovUntil", 0))

    fun setOverride(ctx: Context, dir: Int, until: Long) {
        sp(ctx).edit().putInt("ovDir", dir).putLong("ovUntil", until).apply()
    }
}

object Schedule {
    val ZONE: ZoneId = ZoneId.of("Europe/Warsaw")
    private const val PRIORITY_WINDOW_SEC = 2 * 3600L
    private val DATE = DateTimeFormatter.BASIC_ISO_DATE

    private class Conn(val dir: Int, val dep: Int, val line: String, val stop: String, val service: String)
    private class Table(val conns: List<Conn>, val services: Map<String, Set<Int>>, val built: Long)

    private var cache: Table? = null
    private var cacheStamp = 0L

    fun invalidate() { cache = null }

    private fun load(ctx: Context): Table? {
        val f = Gtfs.timetableFile(ctx)
        if (!f.exists()) return null
        if (cache != null && cacheStamp == f.lastModified()) return cache
        val o = JSONObject(f.readText())
        val arr = o.getJSONArray("conns")
        val conns = (0 until arr.length()).map {
            val c = arr.getJSONArray(it)
            Conn(c.getInt(0), c.getInt(1), c.getString(3), c.getString(4), c.getString(5))
        }
        val so = o.getJSONObject("services")
        val services = so.keys().asSequence().associateWith { k ->
            val a = so.getJSONArray(k)
            (0 until a.length()).map { a.getInt(it) }.toHashSet()
        }
        return Table(conns, services, o.getLong("built")).also { cache = it; cacheStamp = f.lastModified() }
    }

    fun hasData(ctx: Context) = load(ctx) != null

    fun now(): ZonedDateTime = ZonedDateTime.now(ZONE)

    fun autoDirection(now: ZonedDateTime) = if (now.hour < 12) HOME_TO_WORK else WORK_TO_HOME

    fun direction(ctx: Context, now: ZonedDateTime): Int {
        val (dir, until) = Prefs.override(ctx)
        return if (dir >= 0 && now.toInstant().toEpochMilli() < until) dir else autoDirection(now)
    }

    /** Next 00:00 or 12:00, whichever comes first. */
    fun nextSwitch(now: ZonedDateTime): ZonedDateTime {
        val noon = now.toLocalDate().atTime(12, 0).atZone(ZONE)
        return if (now.isBefore(noon)) noon else now.toLocalDate().plusDays(1).atStartOfDay(ZONE)
    }

    fun toggle(ctx: Context) {
        val now = now()
        Prefs.setOverride(ctx, 1 - direction(ctx, now), nextSwitch(now).toInstant().toEpochMilli())
    }

    private fun upcoming(t: Table, dir: Int, now: ZonedDateTime): List<Departure> {
        val out = ArrayList<Departure>()
        for (d in -1L..2L) {
            val date: LocalDate = now.toLocalDate().plusDays(d)
            val key = date.format(DATE).toInt()
            // GTFS times count from "noon minus 12h", which stays correct on DST change days.
            val base = date.atTime(12, 0).atZone(ZONE).minusHours(12)
            for (c in t.conns) {
                if (c.dir != dir || t.services[c.service]?.contains(key) != true) continue
                val time = base.plusSeconds(c.dep.toLong())
                if (!time.isBefore(now.withSecond(0).withNano(0))) out.add(Departure(time, c.line, c.stop))
            }
        }
        return out.sortedBy { it.time }.distinctBy { Triple(it.time, it.line, it.stop) }
    }

    /**
     * Up to 2 departures. Priority lines leaving within the next 2 hours go first;
     * remaining slots are filled with the earliest other buses.
     */
    fun next(ctx: Context, dir: Int, now: ZonedDateTime, count: Int = 2): List<Departure> {
        val t = load(ctx) ?: return emptyList()
        val all = upcoming(t, dir, now)
        val prio = Prefs.priority(ctx).map { it.uppercase() }.toSet()
        val limit = now.plusSeconds(PRIORITY_WINDOW_SEC)
        val picked = all.filter { it.line.uppercase() in prio && !it.time.isAfter(limit) }.take(count).toMutableList()
        for (d in all) {
            if (picked.size >= count) break
            if (d !in picked) picked.add(d)
        }
        return picked.sortedBy { it.time }
    }
}
