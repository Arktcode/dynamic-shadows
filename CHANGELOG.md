# Dynamic Shadows v1.6.160.5
<img width="64" height="64" alt="ases" src="https://github.com/user-attachments/assets/bb2a773f-ae8d-4c86-9d4e-3657ce0df26b" />
<img width="64" height="64" alt="icon" src="https://github.com/user-attachments/assets/3de8e2cf-cf73-4e6d-946c-df1278aea5e5" />

## Resumen

La versión **v1.6.160.5** consolida la primera versión estable oficial de **Dynamic Shadows**, enfocándose en optimizaciones de rendimiento a gran escala, un sistema adaptativo de desvanecimiento dinámico de sombras al alejar la cámara (independiente de la resolución y DPI en PC y móviles), resolución de problemas críticos de compilación y ejecución en Android DEX (D8), restauración del ciclo de día y noche, y expansión de la internacionalización con soporte para Chino Simplificado.

---

## Cambios

### Renderizado y Motor de Sombras

- **Desvanecimiento Dinámico de Sombras al Alejar la Cámara (Zoom Shadow Fade):**
  - Implementado desvanecimiento progresivo y fluido escalonado por niveles (Tiers) según el tamaño del bloque o estructura:
    - **Tier SMALL (1x1, transportes, rocas pequeñas):** se desvanece suavemente de 18px a 10px.
    - **Tier MED (2x2, vegetación mediana, árboles):** se desvanece de 14px a 8px.
    - **Tier LARGE (3x3):** se desvanece de 12px a 7px.
    - **Tier XL (4x4, 5x5, núcleos, enlaces de puentes) y Tier ENV (montañas y paredes rocosas):** se desvanece de 11px a 6.1px.
  - Al alejar la cámara al nivel máximo de zoom, **todas las sombras (incluidas montañas y unidades) se desvanecen por completo**, eliminando el ruido visual de sombras borrosas desde la vista lejana/orbital.
  - Añadido interruptor configurable en el menú de ajustes (`zoom_shadow_fade`) con soporte multiidioma.
- **Descarte de Tiers Vacíos (Empty Tier Skipping):**
  - Si un Tier está completamente desvanecido por zoom (`tierFade ≤ 0.005f`) o no contiene ningún proyector visible en la vista actual de la cámara, el motor **omite por completo la vinculación de FBOs, pases Gaussianos y limpiezas de huella de ese nivel**.
  - Incremento masivo de FPS en mapas grandes y al alejar la cámara (de ~15–20 FPS a 60 FPS estables).
- **Restauración del Ciclo Día/Noche:**
  - Reintegrado el ciclo solar dinámico con rotación angular, atenuación nocturna de sombras y tinte ambiental ajustable desde los ajustes (`day_night_cycle`).
- **Sombras de Puentes Transportadores:**
  - Animación suave de entrada y salida (*warmup*) para sombras de enlaces aéreos en puentes (Tier XL), con borrado automático de huella en capas inferiores para evitar solapamientos visuales.

### Soporte Multiplataforma y Móvil

- **Normalización de Zoom por Escala Mínima (`minScale`):**
  - Corregido el cálculo de zoom en dispositivos móviles: ahora se calibra automáticamente utilizando la escala nativa del renderizador (`Vars.renderer.minScale()`) y la densidad de pantalla (`Scl.scl()`). El desvanecimiento y desaparición de sombras al hacer zoom out funciona de forma idéntica y precisa tanto en pantallas táctiles de alta densidad (1080p, 1440p, 20:9, tablets) como en PC.
- **Resolución Adaptativa de FBOs en Móvil:**
  - La resolución máxima de los FrameBuffers se limita inteligentemente a `1920px` en dispositivos móviles (`Vars.mobile`), reduciendo el consumo de ancho de banda y fillrate de la GPU móvil en más de un 40% y previniendo el calentamiento, mientras que en PC se conservan los `3840px` nativos para pantallas 4K.
- **Estabilización de Micro-Temblor Táctil:**
  - Ajustados los umbrales de redibujado de la cámara en móvil (`camMoveThreshold` y `camZoomThreshold`) para ignorar el temblor natural del dedo al sostener la pantalla, garantizando que el caché de sombras estáticas se mantenga activo y no gaste recursos innecesarios.
- **Compatibilidad Android DEX / Dalvik / ART:**
  - Código fuente y compilador Java ajustados a **Java 8 (Major Version 52)**, garantizando que la herramienta D8 genere `classes.dex` sin advertencias de compatibilidad desde Android 4.4 (API 14) hasta Android 15.
  - Tareas `gradlew dex` y `gradlew dexify` agregadas en `build.gradle` con inyección de `--lib android.jar` y aislamiento de JAR limpio para permitir compilaciones repetidas sin errores de conflicto.

### Pantalla Splash (DUI) e Internacionalización

- Actualizada la versión en la cabecera del splash a **v1.6.160.5**.
- Actualizadas las 4 entradas de novedades destacadas en la interfaz de bienvenida:
  1. *Mejora de calidad / Quality improvements.*
  2. *Culling direccional / Directional culling.*
  3. *Optimización gráfica / Graphics optimization.*
  4. *First Stable Version.*
- Añadido soporte completo para **Chino Simplificado** (`bundle_zh_CN.properties`) para todos los textos de configuración y de la interfaz splash.
- Actualizados y alineados los archivos de localización en Español (`bundle_es.properties`), Inglés (`bundle.properties`) y Ruso (`bundle_ru.properties`).
- Limpieza integral del código fuente: eliminados caracteres decorativos de comentarios (`──`, `----`, comentarios residuales o informales), dejando comentarios concisos en texto plano y código limpio.

---

## Corrección de Errores

- **[MÓVIL / DEX]** Corregido el fallo crítico `com.android.tools.r8.CompilationFailedException: Unsupported class file major version 69` al compilar para Android, causado por la ausencia de configuración explícita de `sourceCompatibility` y `targetCompatibility` en `build.gradle` y por el uso de sintaxis preview (`switch` con pattern matching en `isCrystal`).
- **[MÓVIL / ZOOM]** Corregido el problema por el cual las sombras no se desvanecían ni desaparecían en dispositivos móviles al alejar la cámara, debido a la multiplicación directa por los DPIs físicos de la pantalla.
- **[RENDIMIENTO]** Corregido el cuello de botella de rendimiento al alejar la cámara implementando descarte total de FBOs para capas invisibles o sin casters en pantalla.
- **[ANDROID RUNTIME]** Sustituido `Math.clamp` (introducido en Java 21) por `Mathf.clamp` de Arc en la inicialización del ThreadPool, evitando un fallo en tiempo de ejecución de tipo `NoSuchMethodError` en versiones de Android anteriores a la API 35.

---

## Colaboradores

- **owo** (Sentinel) — ideas, arquitectura y retroalimentación de diseño
- **S_m_i_t_e** — soporte de desarrollo y testeo
- **128_OTEMLn** — pruebas y retroalimentación de optimización

---

## Compatibilidad

| Plataforma | Soporte |
|---|---|
| Mindustry PC (Windows / Linux / macOS) | ✓ |
| Mindustry Android (API 14+) | ✓ |
| Mindustry iOS | ✓ |
| Mindustry v146+ a v160.5+ | ✓ |

**Versión completa del mod:** `v1.6.160.5`  
**Build mínimo de Mindustry:** `v146` (recomendado `v157+` / `v160.5+`)
