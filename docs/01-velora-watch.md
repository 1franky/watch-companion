# 01 — Velora Watch: ciclismo y MTB

## 1. Objetivo

Extender Velora al Galaxy Watch Ultra para ofrecer métricas y controles durante rutas de ciclismo, sin romper el flujo actual:

**iGPSPORT BSC100S → aplicación iGPSPORT → Strava → webhook de Velora → Velora API → Velora Android/Web.**

El BSC100S sigue siendo el ciclocomputador principal en la configuración predeterminada. El reloj complementa, no compite con él.

## 2. Objetivos funcionales

### MVP

- Pantalla de estado de ruta y conexión con el teléfono.
- Mostrar frecuencia cardíaca disponible desde el reloj.
- Mostrar tiempo de actividad y métricas recibidas del teléfono cuando estén disponibles.
- Alertas hápticas por zonas de frecuencia cardíaca configuradas.
- Registrar telemetría del reloj localmente cuando se habilite el modo dual.
- Mostrar estado de sincronización con Velora.
- No publicar una segunda actividad en Strava por defecto.
- Permitir iniciar/pausar/finalizar una sesión del reloj únicamente si el usuario habilitó el modo de grabación dual o de respaldo.

### Evolución

- Registro GPS independiente.
- Exportación FIT/GPX/TCX si la librería y el formato elegido lo permiten.
- Comparación de rutas de BSC100S y Watch.
- Recuperación de actividad cuando el ciclocomputador falla.
- Analítica de carga y segmentos en Velora.
- Configuración de sensores y zonas cardíacas.

## 3. Modos de operación

La selección se configura en el reloj y se refleja en la aplicación Android.

### Modo A — iGPSPORT principal (predeterminado)

- BSC100S graba el track GPS y las métricas de sensores conectados.
- BSC100S se sincroniza con iGPSPORT y Strava según la configuración actual del usuario.
- Strava dispara el webhook de Velora.
- Velora importa y procesa esa actividad.
- El Watch se utiliza para mostrar información, registrar telemetría propia opcional y generar alertas.
- El Watch no crea ni sube una segunda actividad de ciclismo.

Si el reloj registra HR por separado, almacenar esa serie como fuente auxiliar y no sustituir automáticamente la HR del ciclocomputador o de la banda conectada.

### Modo B — Grabación dual / respaldo

- BSC100S registra su actividad habitual.
- Watch registra un track independiente mediante Health Services ExerciseClient y ubicación.
- La copia del Watch se guarda como respaldo y se sincroniza con Velora bajo una entidad de sesión/fuente secundaria.
- No subir la copia del Watch a Strava automáticamente.
- Si el BSC100S falla, el usuario puede seleccionar la ruta del Watch como candidata para recuperar la actividad.
- Si ambas grabaciones son válidas, conservar ambas; no fusionar de manera destructiva.

### Modo C — Fusión avanzada

- Importar las dos fuentes originales.
- Normalizar tiempo, coordenadas, altitud y métricas.
- Alinear relojes mediante marcas temporales y estimar offset.
- Comparar distancia temporal, separación espacial, precisión GPS y continuidad.
- Construir una representación canónica derivada, conservando todos los datos originales.
- Mostrar al usuario un resumen de diferencias y pedir confirmación si la fusión altera significativamente distancia, duración o track.
- No reemplazar una actividad ya publicada en Strava sin una estrategia explícita y validada.

## 4. Datos y sensores

### Watch

Evaluar Health Services `ExerciseClient` para sesiones activas. `MeasureClient` no debe utilizarse como mecanismo de captura continua en background. Gestionar permisos y foreground service de salud/ubicación según la versión de Wear OS.

Datos candidatos:
- heart rate (BPM)
- timestamp
- ubicación GPS
- altitud disponible
- precisión horizontal/vertical si la API la proporciona
- velocidad derivada o reportada
- estado de sesión
- batería y calidad de señal como metadatos técnicos

### BSC100S

El BSC100S admite GPS GNSS, altitud, sensores de frecuencia cardíaca/velocidad/cadencia por BLE o ANT+, y potencia por ANT+ según especificaciones del fabricante. Su integración actual con Strava debe conservarse.

No asumir que existe una API pública o un protocolo BLE abierto del BSC100S para descargar su FIT directamente. Validar documentación oficial, exportación de archivos y posibilidades de integración antes de planear una conexión directa.

## 5. UI del reloj

Pantallas sugeridas:

1. **Resumen:** estado BSC100S, estado del teléfono, modo de grabación.
2. **Métricas:** BPM, zona, duración, velocidad/distancia si se reciben.
3. **Alertas:** zona cardíaca alta/baja, pérdida de conexión, batería baja.
4. **Sesión:** iniciar, pausar, reanudar y finalizar grabación Watch cuando el modo lo permita.
5. **Sincronización:** pendiente, sincronizando, sincronizada, error.

Evitar una pantalla saturada. Priorizar lectura rápida, contraste, tipografía grande y controles de pocos toques.

## 6. API Velora: capacidades por evaluar

No fijar rutas REST hasta auditar el OpenAPI actual. Proponer contratos equivalentes a:

- crear una sesión de grabación Watch
- subir lotes de telemetría
- finalizar sesión y registrar metadatos
- subir archivo de ruta (FIT/GPX/TCX)
- consultar estado de ingestión
- asociar fuente secundaria a actividad importada desde Strava
- consultar discrepancias y resultado de reconciliación

Todas las operaciones de ingestión deben ser idempotentes.

## 7. Criterios de aceptación

- Una salida normal con BSC100S produce una sola actividad en Strava y una sola actividad lógica en Velora.
- Activar el modo A no inicia una segunda subida de actividad desde el Watch.
- El Watch no pierde toda la sesión al perder conexión con el S26 Ultra.
- En modo B, la sesión local sobrevive al reinicio de la app y puede sincronizarse posteriormente.
- Las muestras tienen timestamps y fuente identificable.
- Las alertas cardíacas funcionan sin depender de internet.
- El usuario puede detener la grabación y ver claramente si se guardó.
- Una sesión fallida no se presenta como sincronizada.
