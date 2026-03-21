package com.marinenavigator.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import timber.log.Timber
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Enrutador marino que evita tierra firme.
 * Obtiene líneas de costa de Overpass API y ejecuta A* sobre una cuadrícula mar/tierra.
 */
object MarineRouter {

    private const val GRID_N = 80        // resolución de la cuadrícula (80x80)
    private const val PADDING = 0.15     // grados de margen alrededor de la ruta

    data class LatLon(val lat: Double, val lon: Double)
    data class Cell(val row: Int, val col: Int)

    // ─────────────────────────────────────────────────────────
    // Punto de entrada principal
    // ─────────────────────────────────────────────────────────
    suspend fun route(
        fromLat: Double, fromLon: Double,
        toLat: Double, toLon: Double
    ): List<GeoPoint> = withContext(Dispatchers.IO) {
        try {
            val south = minOf(fromLat, toLat) - PADDING
            val north = maxOf(fromLat, toLat) + PADDING
            val west  = minOf(fromLon, toLon) - PADDING
            val east  = maxOf(fromLon, toLon) + PADDING

            Timber.d("MarineRouter: bbox $south,$west → $north,$east")

            val segments = fetchCoastlineSegments(south, west, north, east)
            Timber.d("MarineRouter: ${segments.size} segmentos de costa")

            if (segments.isEmpty()) {
                // Sin costa detectada → línea directa (zona abierta)
                return@withContext listOf(GeoPoint(fromLat, fromLon), GeoPoint(toLat, toLon))
            }

            val grid = buildLandGrid(segments, south, west, north, east)
            val path = aStar(fromLat, fromLon, toLat, toLon, south, west, north, east, grid)

            if (path.size < 2) {
                listOf(GeoPoint(fromLat, fromLon), GeoPoint(toLat, toLon))
            } else {
                path
            }
        } catch (e: Exception) {
            Timber.e(e, "MarineRouter error")
            listOf(GeoPoint(fromLat, fromLon), GeoPoint(toLat, toLon))
        }
    }

    // ─────────────────────────────────────────────────────────
    // Overpass API: obtener segmentos de línea de costa
    // ─────────────────────────────────────────────────────────
    private fun fetchCoastlineSegments(
        south: Double, west: Double, north: Double, east: Double
    ): List<Pair<LatLon, LatLon>> {
        val query = """
            [out:json][timeout:30];
            way["natural"="coastline"]($south,$west,$north,$east);
            out geom;
        """.trimIndent()

        val encoded = URLEncoder.encode(query, "UTF-8")
        val conn = URL("https://overpass-api.de/api/interpreter?data=$encoded")
            .openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout   = 30_000
        conn.setRequestProperty("User-Agent", "MarineNavigator/1.0")

        return try {
            val root = JSONObject(conn.inputStream.bufferedReader().readText())
            val elements = root.getJSONArray("elements")
            val segments = mutableListOf<Pair<LatLon, LatLon>>()

            for (i in 0 until elements.length()) {
                val el = elements.getJSONObject(i)
                if (!el.has("geometry")) continue
                val geom = el.getJSONArray("geometry")
                for (j in 0 until geom.length() - 1) {
                    val p1 = geom.getJSONObject(j)
                    val p2 = geom.getJSONObject(j + 1)
                    segments += Pair(
                        LatLon(p1.getDouble("lat"), p1.getDouble("lon")),
                        LatLon(p2.getDouble("lat"), p2.getDouble("lon"))
                    )
                }
            }
            segments
        } finally {
            conn.disconnect()
        }
    }

    // ─────────────────────────────────────────────────────────
    // Construir cuadrícula: true = tierra, false = mar
    // Usa ray casting horizontal para determinar interior/exterior
    // (En OSM la línea de costa tiene la tierra a la izquierda)
    // ─────────────────────────────────────────────────────────
    private fun buildLandGrid(
        segments: List<Pair<LatLon, LatLon>>,
        south: Double, west: Double, north: Double, east: Double
    ): Array<BooleanArray> {
        val latStep = (north - south) / GRID_N
        val lonStep = (east - west) / GRID_N
        val grid = Array(GRID_N) { BooleanArray(GRID_N) }

        for (row in 0 until GRID_N) {
            val lat = south + (row + 0.5) * latStep
            var crossings = 0
            // Recorre de oeste a este, contando cruces de costa
            for (col in 0 until GRID_N) {
                val lon = west + (col + 0.5) * lonStep
                // Contar cruces de segmentos en este punto (ray cast hacia el oeste)
                var c = 0
                for ((p1, p2) in segments) {
                    val minLat = minOf(p1.lat, p2.lat)
                    val maxLat = maxOf(p1.lat, p2.lat)
                    if (lat < minLat || lat >= maxLat) continue
                    val t = (lat - p1.lat) / (p2.lat - p1.lat)
                    val iLon = p1.lon + t * (p2.lon - p1.lon)
                    if (iLon <= lon) c++
                }
                grid[row][col] = (c % 2 == 1)
            }
        }
        return grid
    }

    // ─────────────────────────────────────────────────────────
    // A* sobre la cuadrícula
    // ─────────────────────────────────────────────────────────
    private fun aStar(
        fromLat: Double, fromLon: Double,
        toLat: Double, toLon: Double,
        south: Double, west: Double, north: Double, east: Double,
        grid: Array<BooleanArray>
    ): List<GeoPoint> {
        val latStep = (north - south) / GRID_N
        val lonStep = (east - west) / GRID_N

        fun toCell(lat: Double, lon: Double) = Cell(
            ((lat - south) / latStep).toInt().coerceIn(0, GRID_N - 1),
            ((lon - west)  / lonStep).toInt().coerceIn(0, GRID_N - 1)
        )
        fun toGeo(cell: Cell) = GeoPoint(
            south + (cell.row + 0.5) * latStep,
            west  + (cell.col + 0.5) * lonStep
        )
        fun h(a: Cell, b: Cell): Double {
            val dr = (a.row - b.row).toDouble()
            val dc = (a.col - b.col).toDouble()
            return Math.sqrt(dr * dr + dc * dc)
        }

        val start = toCell(fromLat, fromLon)
        val goal  = toCell(toLat,   toLon)

        // Si el origen o destino están en tierra, rutar directamente
        if (grid.getOrNull(start.row)?.getOrNull(start.col) == true ||
            grid.getOrNull(goal.row)?.getOrNull(goal.col) == true) {
            return listOf(GeoPoint(fromLat, fromLon), GeoPoint(toLat, toLon))
        }

        data class Node(val cell: Cell, val g: Double, val f: Double)

        val open    = java.util.PriorityQueue<Node>(compareBy { it.f })
        val gScore  = HashMap<Cell, Double>()
        val cameFrom = HashMap<Cell, Cell?>()
        val closed  = HashSet<Cell>()

        gScore[start] = 0.0
        cameFrom[start] = null
        open += Node(start, 0.0, h(start, goal))

        val dirs = listOf(
            -1 to 0, 1 to 0, 0 to -1, 0 to 1,
            -1 to -1, -1 to 1, 1 to -1, 1 to 1
        )

        while (open.isNotEmpty()) {
            val cur = open.poll()!!
            if (cur.cell in closed) continue
            closed += cur.cell

            if (cur.cell == goal) {
                val path = mutableListOf<GeoPoint>()
                var c: Cell? = goal
                while (c != null) {
                    path.add(0, toGeo(c))
                    c = cameFrom[c]
                }
                path[0] = GeoPoint(fromLat, fromLon)
                path[path.size - 1] = GeoPoint(toLat, toLon)
                return simplify(path)
            }

            for ((dr, dc) in dirs) {
                val nr = cur.cell.row + dr
                val nc = cur.cell.col + dc
                if (nr !in 0 until GRID_N || nc !in 0 until GRID_N) continue
                val nb = Cell(nr, nc)
                if (nb in closed) continue
                if (grid[nr][nc]) continue // tierra

                val cost = if (dr != 0 && dc != 0) 1.414 else 1.0
                val ng = (gScore[cur.cell] ?: Double.MAX_VALUE) + cost
                if (ng < (gScore[nb] ?: Double.MAX_VALUE)) {
                    gScore[nb] = ng
                    cameFrom[nb] = cur.cell
                    open += Node(nb, ng, ng + h(nb, goal))
                }
            }
        }

        // Sin ruta encontrada
        return listOf(GeoPoint(fromLat, fromLon), GeoPoint(toLat, toLon))
    }

    // ─────────────────────────────────────────────────────────
    // Simplificar ruta: eliminar puntos colineales intermedios
    // ─────────────────────────────────────────────────────────
    private fun simplify(path: List<GeoPoint>): List<GeoPoint> {
        if (path.size <= 2) return path
        val result = mutableListOf(path.first())
        for (i in 2 until path.size step 3) result.add(path[i])
        if (result.last() != path.last()) result.add(path.last())
        return result
    }
}
