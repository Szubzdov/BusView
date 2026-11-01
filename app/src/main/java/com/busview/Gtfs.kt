package com.busview

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.zip.ZipFile
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

const val HOME_TO_WORK = 0
const val WORK_TO_HOME = 1

data class Place(val query: String, val lat: Double, val lon: Double, val radius: Double) {
    fun toJson() = JSONObject().put("q", query).put("lat", lat).put("lon", lon).put("r", radius)

    companion object {
        fun fromJson(o: JSONObject) = Place(o.getString("q"), o.getDouble("lat"), o.getDouble("lon"), o.getDouble("r"))
    }
}

class BuildResult(val homeStops: List<String>, val workStops: List<String>, val toWork: Int, val toHome: Int)

object Geo {
    private val AREA_TYPES = setOf(
        "city", "town", "village", "hamlet", "municipality", "suburb", "quarter", "neighbourhood",
        "borough", "city_district", "isolated_dwelling", "county", "state", "allotments"
    )

    /** Geocodes inside the ZTM Kielce area via OpenStreetMap Nominatim. */
    fun find(query: String): Place? {
        val url = "https://nominatim.openstreetmap.org/search?format=json&limit=1&countrycodes=pl" +
            "&viewbox=19.9,51.2,21.4,50.4&bounded=1&q=" + URLEncoder.encode(query, "UTF-8")
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", "BusView/1.0 (Android)")
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        val body = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        val arr = JSONArray(body)
        if (arr.length() == 0) return null
        val o = arr.getJSONObject(0)
        // A whole town/village gets a wider radius than a street address.
        val radius = if (o.optString("addresstype") in AREA_TYPES) 1500.0 else 600.0
        return Place(query, o.getString("lat").toDouble(), o.getString("lon").toDouble(), radius)
    }

    fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * r * asin(sqrt(a))
    }
}

private class Csv(input: InputStream) {
    private val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8), 1 shl 16)
    private val header: Map<String, Int> =
        split(reader.readLine().removePrefix("﻿")).withIndex().associate { it.value.trim() to it.index }

    fun col(name: String) = header[name] ?: -1

    fun forEach(block: (List<String>) -> Unit) {
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isNotEmpty()) block(split(line))
        }
    }

    private fun split(line: String): List<String> {
        if ('"' !in line) return line.split(',')
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                quoted && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                c == '"' -> quoted = !quoted
                c == ',' && !quoted -> { out.add(sb.toString()); sb.setLength(0) }
                else -> sb.append(c)
            }
            i++
        }
        out.add(sb.toString())
        return out
    }
}

private fun List<String>.at(i: Int) = if (i in indices) this[i] else ""

private fun parseTime(s: String): Int {
    val p = s.trim().split(':')
    return p[0].toInt() * 3600 + p[1].toInt() * 60 + p.getOrElse(2) { "0" }.toInt()
}

object Gtfs {
    private const val FEED_URL = "https://mkuran.pl/gtfs/kielce.zip"
    private const val DAYS_AHEAD = 60L
    private val DATE = DateTimeFormatter.BASIC_ISO_DATE

    fun timetableFile(ctx: Context) = File(ctx.filesDir, "timetable.json")

    /**
     * Downloads the ZTM Kielce GTFS feed and keeps only direct trips between the
     * home and work stops, saved as a small JSON file that the widget reads.
     */
    fun build(ctx: Context, home: Place, work: Place): BuildResult {
        val zipFile = File(ctx.cacheDir, "kielce.zip")
        download(zipFile)
        ZipFile(zipFile).use { zip ->
            fun open(name: String): InputStream? = zip.getEntry(name)?.let { zip.getInputStream(it) }

            // stop_id -> (name, lat, lon)
            val stops = HashMap<String, Triple<String, Double, Double>>()
            Csv(open("stops.txt")!!).run {
                val id = col("stop_id"); val nm = col("stop_name"); val la = col("stop_lat"); val lo = col("stop_lon")
                forEach { r ->
                    val lat = r.at(la).toDoubleOrNull() ?: return@forEach
                    val lon = r.at(lo).toDoubleOrNull() ?: return@forEach
                    stops[r.at(id)] = Triple(r.at(nm), lat, lon)
                }
            }
            val homeStops = nearStops(stops, home)
            val workStops = nearStops(stops, work)

            val routes = HashMap<String, String>()
            Csv(open("routes.txt")!!).run {
                val id = col("route_id"); val sn = col("route_short_name"); val ln = col("route_long_name")
                forEach { r -> routes[r.at(id)] = r.at(sn).ifEmpty { r.at(ln) } }
            }

            // trip_id -> (line, service_id)
            val trips = HashMap<String, Pair<String, String>>()
            Csv(open("trips.txt")!!).run {
                val id = col("trip_id"); val rt = col("route_id"); val sv = col("service_id")
                forEach { r -> trips[r.at(id)] = Pair(routes[r.at(rt)] ?: r.at(rt), r.at(sv)) }
            }

            class Visit(val seq: Int, val stop: String, val arr: Int, val dep: Int, val pickup: Boolean, val dropOff: Boolean)

            val visits = HashMap<String, MutableList<Visit>>()
            Csv(open("stop_times.txt")!!).run {
                val tr = col("trip_id"); val sq = col("stop_sequence"); val st = col("stop_id")
                val ar = col("arrival_time"); val dp = col("departure_time")
                val pu = col("pickup_type"); val dr = col("drop_off_type")
                forEach { r ->
                    val stop = r.at(st)
                    if (stop !in homeStops && stop !in workStops) return@forEach
                    val dep = r.at(dp).ifEmpty { r.at(ar) }
                    if (dep.isEmpty()) return@forEach
                    val arr = r.at(ar).ifEmpty { dep }
                    visits.getOrPut(r.at(tr)) { ArrayList() }.add(
                        Visit(r.at(sq).toInt(), stop, parseTime(arr), parseTime(dep), r.at(pu) != "1", r.at(dr) != "1")
                    )
                }
            }

            val conns = JSONArray()
            val usedServices = HashSet<String>()
            val counts = IntArray(2)
            for ((tripId, list) in visits) {
                val trip = trips[tripId] ?: continue
                list.sortBy { it.seq }
                for (dir in 0..1) {
                    val from = if (dir == HOME_TO_WORK) homeStops else workStops
                    val to = if (dir == HOME_TO_WORK) workStops else homeStops
                    // Board at the "from" stop closest to the origin that is still followed by a "to" stop.
                    var best: Int = -1
                    for (i in list.indices) {
                        val v = list[i]
                        if (v.stop !in from || !v.pickup) continue
                        if ((i + 1 until list.size).none { list[it].stop in to && list[it].dropOff }) continue
                        if (best < 0 || from[v.stop]!! < from[list[best].stop]!!) best = i
                    }
                    if (best < 0) continue
                    val board = list[best]
                    val alight = (best + 1 until list.size).map { list[it] }.first { it.stop in to && it.dropOff }
                    conns.put(
                        JSONArray().put(dir).put(board.dep).put(alight.arr).put(trip.first)
                            .put(stops[board.stop]!!.first).put(trip.second)
                    )
                    usedServices.add(trip.second)
                    counts[dir]++
                }
            }

            val services = serviceDates(open("calendar.txt"), open("calendar_dates.txt"), usedServices)
            val json = JSONObject()
                .put("conns", conns)
                .put("services", JSONObject().apply { services.forEach { (k, v) -> put(k, JSONArray(v.sorted())) } })
                .put("built", System.currentTimeMillis())
            val tmp = File(ctx.filesDir, "timetable.json.tmp")
            tmp.writeText(json.toString())
            tmp.renameTo(timetableFile(ctx))
            Schedule.invalidate()

            return BuildResult(
                homeStops.keys.map { stops[it]!!.first }.distinct(),
                workStops.keys.map { stops[it]!!.first }.distinct(),
                counts[HOME_TO_WORK], counts[WORK_TO_HOME]
            )
        }
    }

    /** stop_id -> distance in metres. Falls back to the 3 nearest stops within 3 km. */
    private fun nearStops(stops: Map<String, Triple<String, Double, Double>>, p: Place): Map<String, Double> {
        val all = stops.mapValues { (_, s) -> Geo.distance(p.lat, p.lon, s.second, s.third) }
        val near = all.filterValues { it <= p.radius }
        if (near.isNotEmpty()) return near
        return all.filterValues { it <= 3000 }.entries.sortedBy { it.value }.take(3).associate { it.key to it.value }
    }

    private fun serviceDates(calendar: InputStream?, calendarDates: InputStream?, used: Set<String>): Map<String, MutableSet<Int>> {
        val from = LocalDate.now().minusDays(1)
        val until = LocalDate.now().plusDays(DAYS_AHEAD)
        val out = HashMap<String, MutableSet<Int>>()
        calendar?.let {
            Csv(it).run {
                val id = col("service_id"); val s = col("start_date"); val e = col("end_date")
                val days = DayOfWeek.values().map { d -> col(d.name.lowercase()) }
                forEach { r ->
                    val sid = r.at(id)
                    if (sid !in used) return@forEach
                    var d = maxOf(LocalDate.parse(r.at(s), DATE), from)
                    val end = minOf(LocalDate.parse(r.at(e), DATE), until)
                    while (!d.isAfter(end)) {
                        if (r.at(days[d.dayOfWeek.ordinal]) == "1") out.getOrPut(sid) { HashSet() }.add(d.format(DATE).toInt())
                        d = d.plusDays(1)
                    }
                }
            }
        }
        calendarDates?.let {
            Csv(it).run {
                val id = col("service_id"); val dt = col("date"); val ex = col("exception_type")
                forEach { r ->
                    val sid = r.at(id)
                    if (sid !in used) return@forEach
                    val date = r.at(dt).toInt()
                    if (r.at(ex) == "1") out.getOrPut(sid) { HashSet() }.add(date) else out[sid]?.remove(date)
                }
            }
        }
        return out
    }

    private fun download(target: File) {
        val conn = URL(FEED_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 20000
        conn.readTimeout = 60000
        val tmp = File(target.path + ".part")
        conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
        target.delete()
        tmp.renameTo(target)
    }
}
