# 01 — Velora Watch: ciclismo y MTB

## 1. Objetivo

Extender Velora al Galaxy Watch Ultra para ofrecer métricas y controles durante rutas de ciclismo, sin romper el flujo actual:

**iGPSPORT BSC100S → aplicación iGPSPORT → Strava → webhook de Velora → Velora API → Velora Android/Web.**

El BSC100S sigue siendo el ciclocomputador principal en la configuración predeterminada. El reloj complementa, no compite con él.

## 2. Objetivos funcionales

### MVP

El MVP solo contiene funciones del **modo A** (WC-ADR-012; hallazgos R-02 y R-03 de `09`).

- Pantalla de resumen: conexión con el teléfono, modo activo y estado de sincronización.
- **Sesión de métricas en vivo** (§3.1): frecuencia cardíaca, zona y duración medidas por el propio reloj.
- Alertas hápticas por zonas de frecuencia cardíaca, sin depender de internet.
- Configuración de las zonas de frecuencia cardíaca desde el teléfono.
- Mostrar el estado de la última actividad importada en Velora (vía Strava).
- No crear ni subir ninguna actividad desde el reloj: ni a Velora ni a Strava.

### Evolución

- **Modo B:** grabación del reloj (GPS + telemetría) con persistencia local e inicio/pausa/fin de sesión de grabación (Fase 5).
- **Velocidad y distancia en vivo:** en el modo A no existen fuentes para ellas (el BSC100S no expone API ni BLE abierto, §4). Requieren el GPS del reloj (modo B) o un sensor BLE de velocidad propio, a decidir en un PoC.
- Exportación FIT/GPX/TCX si la librería y el formato elegido lo permiten.
- Comparación de rutas de BSC100S y Watch.
- Recuperación de actividad cuando el ciclocomputador falla.
- Analítica de carga y segmentos en Velora.
- Configuración de sensores adicionales.

## 3. Modos de operación

La selección se configura en el reloj y se refleja en la aplicación Android.

### Modo A — iGPSPORT principal (predeterminado)

- BSC100S graba el track GPS y las métricas de sensores conectados.
- BSC100S se sincroniza con iGPSPORT y Strava según la configuración actual del usuario.
- Strava dispara el webhook de Velora.
- Velora importa y procesa esa actividad.
- El Watch se utiliza para mostrar información y generar alertas mediante la sesión de métricas en vivo (§3.1).
- El Watch no crea ni sube una segunda actividad de ciclismo.

Guardar la HR del reloj como serie auxiliar en Velora queda fuera del MVP. Si se hace en el futuro, no debe sustituir automáticamente la HR del ciclocomputador o de la banda conectada.

#### 3.1 Sesión de métricas en vivo (modo A)

Para leer HR y duración de forma continua, el reloj necesita un ejercicio activo de Health Services (`ExerciseClient`; `MeasureClient` no sirve para captura continua, §4). En el modo A ese ejercicio es una **sesión de métricas en vivo**, que **no es una grabación**:

- **La inicia y la termina el usuario** en el reloj. La duración mostrada es la del ejercicio de Health Services; pausar y reanudar solo afectan a esa duración.
- **Sin auto-pausa:** la duración incluye las paradas. Detectar que la bici está detenida requiere velocidad, y en el modo A no hay GPS. No se enciende el GPS solo para esto: costaría batería para un dato que ya da el BSC100S, cuya duración es la de referencia (WC-59, hallazgo de la salida 1 de WC-35). El usuario puede pausar a mano.
- **Datos:** solo frecuencia cardíaca (y la zona derivada) y duración. Sin GPS ni ubicación: no se solicita el permiso de ubicación para esta sesión.
- **Sin persistencia de muestras:** las lecturas viven en memoria para la pantalla y las alertas. Al terminar se descartan. Solo se conservan los eventos técnicos del registro de diagnóstico (WC-23), sin muestras de HR.
- **Sin subidas:** no se envía nada a Velora, a Strava ni a ningún backend. Tampoco se envían las muestras al teléfono.
- **Sin dependencia del teléfono ni de internet:** las zonas configuradas se guardan en el reloj y las alertas hápticas se evalúan localmente.
- **Coexistencia con el BSC100S:** la sesión no altera el flujo BSC100S → iGPSPORT → Strava → Velora. La única actividad lógica sigue siendo la del ciclocomputador.

Esta definición es la que verifica la prueba automatizada «el modo A no sube ni crea actividades» (WC-34).

### Modo B — Grabación dual / respaldo

- BSC100S registra su actividad habitual.
- Watch registra un track independiente mediante Health Services ExerciseClient y ubicación.
- La copia del Watch se guarda como respaldo y se sincroniza con Velora bajo una entidad de sesión/fuente secundaria.
- No subir la copia del Watch a Strava automáticamente.
- Si el BSC100S falla, el usuario puede seleccionar la ruta del Watch como candidata para recuperar la actividad.
- Si ambas grabaciones son válidas, conservar ambas; no fusionar de manera destructiva.
- Con el GPS del reloj, evaluar la auto-pausa de Health Services (`setIsAutoPauseAndResumeEnabled`) para que la duración coincida con la del BSC100S (WC-53).

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

Pantallas del MVP (modo A):

1. **Resumen:** estado del teléfono, modo activo y estado de sincronización. No muestra el estado del BSC100S: ni el reloj ni el teléfono tienen forma de conocerlo (§4).
2. **Métricas:** BPM, zona y duración de la sesión de métricas en vivo, con controles para iniciarla, pausarla y terminarla.
3. **Alertas:** zona cardíaca alta/baja, pérdida de conexión, batería baja.
4. **Sincronización:** estado de la última actividad en Velora (pendiente, procesada, error).

Evolución (modo B):

- **Sesión de grabación:** iniciar, pausar, reanudar y finalizar la grabación del reloj, y ver si se guardó.
- **Métricas:** velocidad y distancia cuando exista una fuente (GPS del reloj o sensor BLE).

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

### MVP (modo A)

- Una salida normal con BSC100S produce una sola actividad en Strava y una sola actividad lógica en Velora.
- La sesión de métricas en vivo no crea ni sube ninguna actividad, y no envía muestras a ningún backend ni al teléfono.
- La sesión de métricas en vivo no solicita ni usa la ubicación.
- Al terminar la sesión no quedan muestras de HR persistidas en el reloj.
- La sesión de métricas en vivo sigue funcionando al perder la conexión con el S26 Ultra.
- Las alertas cardíacas funcionan sin depender de internet ni del teléfono.
- Un estado de sincronización fallido no se presenta como sincronizado.

### Evolución (modo B)

- La sesión local sobrevive al reinicio de la app y puede sincronizarse posteriormente.
- Las muestras tienen timestamps y fuente identificable.
- El usuario puede detener la grabación y ver claramente si se guardó.
