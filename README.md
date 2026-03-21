# MarineNavigator 🚢

Aplicación Android de navegación GPS marítima similar a Navionics.

## Características

- **Cartas náuticas** — OpenSeaMap superpuesto sobre OpenStreetMap (profundidades, marcas, puertos)
- **GPS en tiempo real** — posición, velocidad en nudos, rumbo y demora
- **Navegación a destino** — selecciona destino en el mapa, calcula distancia, rumbo y ETA
- **Grabación de rutas** — graba tu recorrido con track GPS, estadísticas completas
- **Visualización de rutas** — ver recorridos guardados sobre el mapa con colores
- **Puntos de pesca** — guarda puntos con nombre, coordenadas, profundidad y notas
- **Waypoints** — puntos de referencia reutilizables
- **Alerta de fondeo** — alarma por vibración y sonido si la embarcación sale del radio definido
- **Modo oscuro marino** — interfaz de alto contraste optimizada para uso en exterior

## Requisitos

- Android Studio Hedgehog (2023.1.1) o superior
- Android SDK 26 (Android 8.0) o superior
- Dispositivo Android con GPS
- Conexión a internet para descargar tiles de cartas

## Abrir en Android Studio

1. Descomprime/copia la carpeta `MarineNavigator` en tu PC
2. Abre Android Studio → **File → Open** → selecciona la carpeta `MarineNavigator`
3. Espera que Gradle sincronice las dependencias (~2-3 min primera vez)
4. Conecta tu Android en modo desarrollador o usa un emulador con GPS
5. Pulsa **Run ▶**

## Estructura del proyecto

```
app/src/main/
├── java/com/marinenavigator/
│   ├── MainActivity.kt               — Actividad principal con navegación
│   ├── data/
│   │   ├── database/                 — Room DB, DAOs
│   │   └── models/                   — Entidades (Route, TrackPoint, FishingPoint, Waypoint)
│   ├── services/
│   │   ├── NavigationService.kt      — Servicio GPS en primer plano + grabación
│   │   └── AnchorAlarmService.kt     — Servicio de alerta de fondeo
│   ├── ui/
│   │   ├── map/MapFragment.kt        — Pantalla principal con mapa
│   │   ├── map/MapViewModel.kt       — ViewModel compartido
│   │   ├── routes/RoutesFragment.kt  — Lista de rutas grabadas
│   │   ├── fishing/FishingFragment.kt — Puntos de pesca
│   │   └── waypoints/WaypointsFragment.kt — Waypoints
│   └── utils/NavigationUtils.kt      — Cálculos: Haversine, rumbo, ETA, formato
└── res/
    ├── layout/                        — Layouts XML
    ├── navigation/nav_graph.xml       — Gráfico de navegación
    ├── drawable/                      — Iconos vectoriales
    └── values/                        — Colores, estilos, strings
```

## Cartas Náuticas

Usa **OpenSeaMap** (https://openseamap.org) — cartas náuticas colaborativas y gratuitas con:
- Líneas de sonda (profundidades)
- Marcas de navegación (boyas, balizas)
- Puertos y marinas
- Zonas de pesca
- Peligros para la navegación

## Uso

### Navegar a un destino
1. Mantén pulsado en el mapa → **Navegar aquí**
2. El panel inferior muestra: distancia, ETA, rumbo y desviación

### Grabar una ruta
1. Pulsa el botón rojo (⏺) lateral → **Iniciar grabación**
2. Navega — el track se dibuja automáticamente
3. Pulsa (⏹) para parar — la ruta se guarda con estadísticas

### Alerta de fondeo
1. Pulsa el icono de ancla ⚓
2. Define el radio en metros (por defecto 50 m)
3. La app alertará con vibración y sonido si la embarcación se aleja

### Guardar punto de pesca
1. Mantén pulsado en el mapa → **Guardar punto de pesca**
2. Ponle un nombre — aparecerá en el mapa con un marcador verde

## Librerías usadas

| Librería | Versión | Uso |
|---|---|---|
| OSMDroid | 6.1.18 | Motor de mapas (OpenStreetMap) |
| OpenSeaMap | tiles | Cartas náuticas overlay |
| Room | 2.6.1 | Base de datos SQLite |
| Google Play Services Location | 21.1.0 | GPS / Fused Location Provider |
| Navigation Component | 2.7.6 | Navegación entre pantallas |
| Material Components | 1.11.0 | UI |
| Coroutines | 1.7.3 | Asincronía |

## Permisos requeridos

- `ACCESS_FINE_LOCATION` — GPS de alta precisión
- `FOREGROUND_SERVICE_LOCATION` — GPS en segundo plano
- `INTERNET` — descargar tiles de cartas
- `VIBRATE` — alerta de fondeo
- `POST_NOTIFICATIONS` — notificaciones del servicio GPS
