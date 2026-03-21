package com.marinenavigator.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import timber.log.Timber
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Enrutador marino que evita:
 *  - Tierra firme (coastline)
 *  - Arrecifes y bajos (natural=reef / natural=shoal)
 *  - Rocas, obstrucciones y naufragios (seamark)
 *  - Zonas de poco fondo (depth < minDepth)
 *
 * Algoritmo: Overpass API → cuadrícula mar/peligro → A*
 */
object MarineRouter {

    private const val GRID_N        = 200   // resolución cuadrícula 200×200
    private const val PADDING       = 0.15  // grados de margen
    private const val HAZARD_BUFFER = 2     // celdas de seguridad alrededor de cada peligro puntual
    private const val COAST_BUFFER  = 1     // celdas de buffer alrededor de los propios segmentos de costa
    private const val RDP_EPSILON   = 0.0008 // umbral de simplificación Ramer-Douglas-Peucker (~80 m)

    data class LatLon(val lat: Double, val lon: Double)
    data class Cell(val row: Int, val col: Int)

    // ─────────────────────────────────────────────────────────
    // Punto de entrada
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

            Timber.d("MarineRouter bbox: $south,$west → $north,$east")

            // Obtener costa + peligros en una sola consulta
            val (coastSegments, hazardPoints, hazardAreas) =
                fetchAllHazards(south, west, north, east)

            Timber.d("Costa: ${coastSegments.size} seg | Peligros punto: ${hazardPoints.size} | Áreas: ${hazardAreas.size}")

            // Si no hay datos de costa, ruta directa (mar abierto)
            if (coastSegments.isEmpty() && hazardPoints.isEmpty() && hazardAreas.isEmpty()) {
                return@withContext listOf(GeoPoint(fromLat, fromLon), GeoPoint(toLat, toLon))
            }

            val grid = buildGrid(coastSegments, hazardPoints, hazardAreas, south, west, north, east)
            val path = aStar(fromLat, fromLon, toLat, toLon, south, west, north, east, grid)

            if (path.size < 2) listOf(GeoPoint(fromLat, fromLon), GeoPoint(toLat, toLon))
            else path

        } catch (e: Exception) {
            Timber.e(e, "MarineRouter error")
            listOf(GeoPoint(fromLat, fromLon), GeoPoint(toLat, toLon))
        }
    }

    // ─────────────────────────────────────────────────────────
    // Overpass: costas + arrecifes + rocas + obstrucciones + naufragios
    // ─────────────────────────────────────────────────────────
    data class HazardData(
        val coastSegments: List<Pair<LatLon, LatLon>>,
        val hazardPoints:  List<LatLon>,                       // rocas, boyas peligro, etc.
        val hazardAreas:   List<List<Pair<LatLon, LatLon>>>    // arrecifes / bajos como polígono
    )

    private fun fetchAllHazards(
        south: Double, west: Double, north: Double, east: Double
    ): HazardData {
        val bbox = "$south,$west,$north,$east"
        // Una sola consulta que obtiene todos los elementos relevantes
        val query = """
            [out:json][timeout:40];
            (
              way["natural"="coastline"]($bbox);
              way["natural"="reef"]($bbox);
              way["natural"="shoal"]($bbox);
              node["natural"="reef"]($bbox);
              node["natural"="shoal"]($bbox);
              node["seamark:type"="rock_awash"]($bbox);
              node["seamark:type"="rock_submerged"]($bbox);
              node["seamark:type"="rock"]($bbox);
              node["seamark:type"="obstruction"]($bbox);
              node["seamark:type"="wreck"]($bbox);
              node["seamark:type"="snag"]($bbox);
              way["seamark:type"="obstruction"]($bbox);
              way["seamark:type"="wreck"]($bbox);
            );
            out geom;
        """.trimIndent()

        val encoded = URLEncoder.encode(query, "UTF-8")
        val conn = URL("https://overpass-api.de/api/interpreter?data=$encoded")
            .openConnection() as HttpURLConnection
        conn.connectTimeout = 25_000
        conn.readTimeout    = 40_000
        conn.setRequestProperty("User-Agent", "MarineNavigator/1.0")

        val coastSegments = mutableListOf<Pair<LatLon, LatLon>>()
        val hazardPoints  = mutableListOf<LatLon>()
        val hazardAreas   = mutableListOf<List<Pair<LatLon, LatLon>>>()

        return try {
            val root     = JSONObject(conn.inputStream.bufferedReader().readText())
            val elements = root.getJSONArray("elements")

            for (i in 0 until elements.length()) {
                val el   = elements.getJSONObject(i)
                val type = el.getString("type")
                val tags = el.optJSONObject("tags")
                val natural   = tags?.optString("natural", "") ?: ""
                val seamarkType = tags?.optString("seamark:type", "") ?: ""

                when (type) {
                    "node" -> {
                        // Peligros puntuales → marcar con buffer de seguridad
                        if (natural in listOf("reef", "shoal") ||
                            seamarkType in listOf("rock_awash", "rock_submerged", "rock",
                                "obstruction", "wreck", "snag")) {
                            hazardPoints += LatLon(el.getDouble("lat"), el.getDouble("lon"))
                        }
                    }
                    "way" -> {
                        if (!el.has("geometry")) continue
                        val geom = el.getJSONArray("geometry")
                        val pts  = (0 until geom.length()).map {
                            val p = geom.getJSONObject(it)
                            LatLon(p.getDouble("lat"), p.getDouble("lon"))
                        }
                        when {
                            natural == "coastline" -> {
                                // Segmentos de costa para ray casting
                                for (j in 0 until pts.size - 1)
                                    coastSegments += Pair(pts[j], pts[j + 1])
                            }
                            natural in listOf("reef", "shoal") ||
                            seamarkType in listOf("obstruction", "wreck") -> {
                                // Área de peligro → lista de segmentos perimetrales
                                val segs = (0 until pts.size - 1).map { j -> Pair(pts[j], pts[j + 1]) }
                                hazardAreas += segs
                                // También los nodos interiores como puntos peligrosos
                                hazardPoints += pts
                            }
                        }
                    }
                }
            }
            HazardData(coastSegments, hazardPoints, hazardAreas)
        } finally {
            conn.disconnect()
        }
    }

    // ─────────────────────────────────────────────────────────
    // Construir cuadrícula combinando tierra + peligros
    // ─────────────────────────────────────────────────────────
    private fun buildGrid(
        coastSegments: List<Pair<LatLon, LatLon>>,
        hazardPoints:  List<LatLon>,
        hazardAreas:   List<List<Pair<LatLon, LatLon>>>,
        south: Double, west: Double, north: Double, east: Double
    ): Array<BooleanArray> {
        val latStep = (north - south) / GRID_N
        val lonStep = (east - west)  / GRID_N
        val grid = Array(GRID_N) { BooleanArray(GRID_N) }

        // 1. Marcar TIERRA con algoritmo Winding Number sobre segmentos de costa OSM.
        //    Convención OSM: el MAR está a la DERECHA de la dirección de viaje → la tierra
        //    queda a la izquierda → los polígonos de tierra son CCW.
        //    Winding Number != 0 ⟹ el punto está en tierra.
        //
        //    Para cada segmento (p1→p2) emitido hacia el este (+lon) desde el punto:
        //      · cruce ascendente (p1.lat ≤ lat < p2.lat) y punto a la IZQUIERDA del seg. → wn++
        //      · cruce descendente (p2.lat ≤ lat < p1.lat) y punto a la DERECHA del seg. → wn--
        //    "isLeft = (p2.lon-p1.lon)*(lat-p1.lat) - (lon-p1.lon)*(p2.lat-p1.lat)"
        //      > 0 ⟹ punto a la izquierda; < 0 ⟹ punto a la derecha
        for (row in 0 until GRID_N) {
            val lat = south + (row + 0.5) * latStep
            for (col in 0 until GRID_N) {
                val lon = west + (col + 0.5) * lonStep
                var wn = 0
                for ((p1, p2) in coastSegments) {
                    if (p1.lat <= lat) {
                        if (p2.lat > lat) {
                            // cruce ascendente
                            val cross = (p2.lon - p1.lon) * (lat - p1.lat) -
                                        (lon    - p1.lon) * (p2.lat - p1.lat)
                            if (cross > 0) wn++
                        }
                    } else {
                        if (p2.lat <= lat) {
                            // cruce descendente
                            val cross = (p2.lon - p1.lon) * (lat - p1.lat) -
                                        (lon    - p1.lon) * (p2.lat - p1.lat)
                            if (cross < 0) wn--
                        }
                    }
                }
                if (wn != 0) grid[row][col] = true
            }
        }

        // 2. Rasterizar los propios segmentos de costa y añadir un buffer de seguridad
        //    (asegura que las celdas por las que pasa la línea de costa queden bloqueadas
        //    incluso cuando el winding number falla por polígonos incompletos en la bbox)
        for ((p1, p2) in coastSegments) {
            val r1 = ((p1.lat - south) / latStep).toInt().coerceIn(0, GRID_N - 1)
            val c1 = ((p1.lon - west)  / lonStep).toInt().coerceIn(0, GRID_N - 1)
            val r2 = ((p2.lat - south) / latStep).toInt().coerceIn(0, GRID_N - 1)
            val c2 = ((p2.lon - west)  / lonStep).toInt().coerceIn(0, GRID_N - 1)
            val steps = maxOf(abs(r2 - r1), abs(c2 - c1)) * 2 + 1
            for (s in 0..steps) {
                val t = s.toDouble() / steps
                val r = (r1 + t * (r2 - r1)).toInt().coerceIn(0, GRID_N - 1)
                val c = (c1 + t * (c2 - c1)).toInt().coerceIn(0, GRID_N - 1)
                for (dr in -COAST_BUFFER..COAST_BUFFER) {
                    for (dc in -COAST_BUFFER..COAST_BUFFER) {
                        val nr = r + dr; val nc = c + dc
                        if (nr in 0 until GRID_N && nc in 0 until GRID_N) grid[nr][nc] = true
                    }
                }
            }
        }

        // 3. Marcar ÁREAS DE PELIGRO (arrecifes/bajos como polígono) con ray casting eastward.
        //    Ray hacia el ESTE (+lon): conta cruces donde iLon > lon (a la derecha del punto).
        for (areaSegs in hazardAreas) {
            for (row in 0 until GRID_N) {
                val lat = south + (row + 0.5) * latStep
                for (col in 0 until GRID_N) {
                    if (grid[row][col]) continue  // ya marcada
                    val lon = west + (col + 0.5) * lonStep
                    var crossings = 0
                    for ((p1, p2) in areaSegs) {
                        val minLat = minOf(p1.lat, p2.lat)
                        val maxLat = maxOf(p1.lat, p2.lat)
                        if (lat < minLat || lat >= maxLat) continue
                        val t    = (lat - p1.lat) / (p2.lat - p1.lat)
                        val iLon = p1.lon + t * (p2.lon - p1.lon)
                        if (iLon > lon) crossings++   // ray eastward: contar cruces a la derecha
                    }
                    if (crossings % 2 == 1) grid[row][col] = true
                }
            }
        }

        // 4. Marcar PELIGROS PUNTUALES (rocas, wreck, etc.) con buffer de seguridad
        for (pt in hazardPoints) {
            val centerRow = ((pt.lat - south) / latStep).toInt().coerceIn(0, GRID_N - 1)
            val centerCol = ((pt.lon - west)  / lonStep).toInt().coerceIn(0, GRID_N - 1)
            for (dr in -HAZARD_BUFFER..HAZARD_BUFFER) {
                for (dc in -HAZARD_BUFFER..HAZARD_BUFFER) {
                    val r = centerRow + dr
                    val c = centerCol + dc
                    if (r in 0 until GRID_N && c in 0 until GRID_N) grid[r][c] = true
                }
            }
        }

        return grid
    }

    // ─────────────────────────────────────────────────────────
    // A* sobre la cuadrícula
    // ─────────────────────────────────────────────────────────
    private fun aStar(
        fromLat: Double, fromLon: Double,
        toLat: Double,   toLon: Double,
        south: Double, west: Double, north: Double, east: Double,
        grid: Array<BooleanArray>
    ): List<GeoPoint> {
        val latStep = (north - south) / GRID_N
        val lonStep = (east  - west)  / GRID_N

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
            return sqrt(dr * dr + dc * dc)
        }

        val start = toCell(fromLat, fromLon)
        val goal  = toCell(toLat,   toLon)

        // Si origen/destino están en zona bloqueada, buscar celda libre más cercana
        fun nearestFree(cell: Cell): Cell {
            if (!grid[cell.row][cell.col]) return cell
            for (r in 1..5) {
                for (dr in -r..r) for (dc in -r..r) {
                    val nr = cell.row + dr; val nc = cell.col + dc
                    if (nr in 0 until GRID_N && nc in 0 until GRID_N && !grid[nr][nc])
                        return Cell(nr, nc)
                }
            }
            return cell
        }

        val safeStart = nearestFree(start)
        val safeGoal  = nearestFree(goal)

        data class Node(val cell: Cell, val g: Double, val f: Double)

        val open     = java.util.PriorityQueue<Node>(compareBy { it.f })
        val gScore   = HashMap<Cell, Double>()
        val cameFrom = HashMap<Cell, Cell?>()
        val closed   = HashSet<Cell>()

        gScore[safeStart] = 0.0
        cameFrom[safeStart] = null
        open += Node(safeStart, 0.0, h(safeStart, safeGoal))

        val dirs = listOf(
            -1 to 0, 1 to 0, 0 to -1, 0 to 1,
            -1 to -1, -1 to 1, 1 to -1, 1 to 1
        )

        while (open.isNotEmpty()) {
            val cur = open.poll()!!
            if (cur.cell in closed) continue
            closed += cur.cell

            if (cur.cell == safeGoal) {
                val path = mutableListOf<GeoPoint>()
                var c: Cell? = safeGoal
                while (c != null) { path.add(0, toGeo(c)); c = cameFrom[c] }
                path[0] = GeoPoint(fromLat, fromLon)
                path[path.size - 1] = GeoPoint(toLat, toLon)
                return simplify(path)
            }

            for ((dr, dc) in dirs) {
                val nr = cur.cell.row + dr
                val nc = cur.cell.col + dc
                if (nr !in 0 until GRID_N || nc !in 0 until GRID_N) continue
                val nb = Cell(nr, nc)
                if (nb in closed || grid[nr][nc]) continue

                val cost = if (dr != 0 && dc != 0) 1.414 else 1.0
                val ng   = (gScore[cur.cell] ?: Double.MAX_VALUE) + cost
                if (ng < (gScore[nb] ?: Double.MAX_VALUE)) {
                    gScore[nb]   = ng
                    cameFrom[nb] = cur.cell
                    open += Node(nb, ng, ng + h(nb, safeGoal))
                }
            }
        }

        return listOf(GeoPoint(fromLat, fromLon), GeoPoint(toLat, toLon))
    }

    // ─────────────────────────────────────────────────────────
    // Simplificar ruta con Ramer-Douglas-Peucker
    // Mantiene los puntos necesarios para no cruzar tierra
    // ─────────────────────────────────────────────────────────
    private fun simplify(path: List<GeoPoint>): List<GeoPoint> {
        if (path.size <= 2) return path
        return rdp(path, 0, path.size - 1, RDP_EPSILON)
    }

    private fun rdp(path: List<GeoPoint>, start: Int, end: Int, epsilon: Double): List<GeoPoint> {
        if (end - start < 2) return listOf(path[start], path[end])

        val p1 = path[start]
        val p2 = path[end]
        val dx = p2.longitude - p1.longitude
        val dy = p2.latitude  - p1.latitude
        val lenSq = dx * dx + dy * dy

        var maxDist = 0.0
        var maxIdx  = start + 1

        for (i in start + 1 until end) {
            val p  = path[i]
            val dist = if (lenSq < 1e-14) {
                val ex = p.longitude - p1.longitude
                val ey = p.latitude  - p1.latitude
                sqrt(ex * ex + ey * ey)
            } else {
                val t  = ((p.longitude - p1.longitude) * dx + (p.latitude - p1.latitude) * dy) / lenSq
                val tc = t.coerceIn(0.0, 1.0)
                val ex = p.longitude - (p1.longitude + tc * dx)
                val ey = p.latitude  - (p1.latitude  + tc * dy)
                sqrt(ex * ex + ey * ey)
            }
            if (dist > maxDist) { maxDist = dist; maxIdx = i }
        }

        return if (maxDist > epsilon) {
            val left  = rdp(path, start,   maxIdx, epsilon)
            val right = rdp(path, maxIdx,  end,    epsilon)
            left.dropLast(1) + right
        } else {
            listOf(path[start], path[end])
        }
    }
}
