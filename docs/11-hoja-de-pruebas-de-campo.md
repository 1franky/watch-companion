# 11 — Hoja de pruebas de campo (WC-35 y WC-57)

Una sola hoja para las **seis salidas** de `06` §3. Las salidas 1 y 2 cierran la Fase 1 (WC-35) y su protocolo detallado, con las verificaciones V1–V9, está en `10`. Las salidas 3 a 6 son de WC-57 y dan la línea base del motor de reconciliación (WC-55): **no se fijan tolerancias sin estos datos**.

## 1. Las seis salidas

| # | Salida | Modo | Tarjeta | Qué se busca | Estado |
|---|---|---|---|---|---|
| 1 | Ruta urbana corta (≤ 1 h) | A | WC-35 | Alertas de zona, enlace, sincronización de la última actividad | Hecha sin plantilla (de ella salió WC-59); registrar sus datos si se tienen |
| 2 | MTB con desnivel | A | WC-35 | Alertas con esfuerzo cambiante, V2–V9 | |
| 3 | Ruta larga (≥ 3 h) | A | WC-57 | Consumo de batería por hora, sesión estable horas seguidas | |
| 4 | Ruta con pérdida de señal móvil | B | WC-57 | Que la grabación del reloj llegue a Velora al recuperar señal; reloj ↔ teléfono sin internet | |
| 5 | Ruta con batería del reloj baja | B | WC-57 | Qué pasa con la sesión y la grabación cuando el reloj se queda sin batería | |
| 6 | BSC100S y reloj grabando a la vez | B | WC-57 | Comparativa completa BSC100S vs. reloj: la base de las tolerancias | |

- **Modo A:** el BSC100S graba; el reloj solo muestra métricas y alertas (**Iniciar métricas**).
- **Modo B:** el BSC100S graba y el reloj también graba su ruta con GPS (**Grabar ruta**). La grabación del reloj nunca va a Strava: llega a Velora como grabación secundaria de la misma actividad.
- **Se pueden juntar** la 4 o la 5 con la 6 si la ruta lo permite (las tres son modo B). Anótalo en la plantilla. La 3 conviene en modo A para tener el consumo sin GPS del reloj; si se hace en modo B, también sirve, pero entonces falta una salida larga en modo A.
- **Seguridad:** en la 4 y la 5 el teléfono o el reloj pueden quedarse sin servicio. No dependas del reloj para orientarte ni para pedir ayuda: lleva el teléfono con batería y avisa tu ruta.

## 2. Antes de la primera salida (una vez)

- [ ] Apps actualizadas desde `develop`, instaladas desde la misma computadora: `./gradlew :app-phone:installDebug :app-watch:installDebug`.
- [ ] Teléfono: sesión de Velora iniciada; zonas de FC guardadas y enviadas al reloj (`10` §1).
- [ ] Reloj: módulo Velora activo con permiso de frecuencia cardíaca. Para el modo B, tocar **Grabar ruta** una vez en casa y conceder la **ubicación precisa** (sin la precisa no graba).
- [ ] Samsung Health: anotar si la detección automática de ciclismo está activada (V8).
- [ ] Guardian: desactiva el **aviso sin teléfono** si vas a rodar lejos del teléfono, o déjalo y anota si vibró (no es parte de la prueba, pero sirve saberlo).

## 3. Tarjeta de bolsillo (cada salida)

Al salir:
1. Anotar **batería del reloj y del teléfono** y la hora.
2. BSC100S: iniciar como siempre.
3. Reloj: **Iniciar métricas** (modo A) o **Grabar ruta** (modo B).

Durante:
4. Anotar las **alertas** que sientas raras: repetidas sin cambiar de esfuerzo o que faltaron.
5. Anotar paradas largas (hora aproximada y duración): el reloj no tiene auto-pausa (WC-59).
6. Lo específico de la salida (§4).

Al llegar:
7. BSC100S: terminar. Reloj: **Terminar → Sí, terminar**. Captura de la pantalla final del reloj.
8. Anotar **batería del reloj y del teléfono** y la hora.
9. Dejar el teléfono con internet y la app del companion instalada; no hace falta abrirla.

Después (ese día):
10. Strava y Velora: **una sola** actividad de la salida.
11. Modo B: en Velora, la actividad tiene la **grabación secundaria** del reloj adjunta; anotar cuánto tardó en aparecer desde que terminaste.
12. Reloj → Resumen: «Última actividad» pasa a **Sincronizada**; anotar a los cuántos minutos.
13. Samsung Health: sin entrenamiento nuevo del reloj (V3).
14. Llenar la plantilla (§5) y pasarme los archivos (§6).

## 4. Lo específico de cada salida

**Salidas 1 y 2.** Seguir `10` §2 y §3 (V1–V9).

**Salida 3 — larga.**
- Anotar la batería del reloj **cada hora** (o al menos a la mitad).
- Si la sesión del reloj se corta o se reinicia, anotar la hora y lo que mostró.
- Comprobar al final que la duración del reloj cubre toda la salida.

**Salida 4 — sin señal móvil.**
- Elegir una ruta con un tramo largo sin datos móviles (o poner el **teléfono** en modo avión durante un tramo de al menos 20 min, con el Bluetooth encendido).
- Durante el tramo: las métricas y alertas del reloj deben seguir; la conexión reloj ↔ teléfono va por Bluetooth y no necesita internet.
- Al terminar, **sin señal todavía**: la grabación del reloj pasa al teléfono (en la app del teléfono aparece «grabaciones pendientes»). Anotar si apareció.
- Al recuperar señal: anotar cuánto tardó la grabación en llegar a Velora. Se sube sola con reintentos; no hace falta abrir la app.

**Salida 5 — batería del reloj baja.**
- Salir con el reloj entre **15 y 25 %**. Anotar el porcentaje exacto.
- Anotar si el reloj activó ahorro de energía y si la sesión siguió.
- Si el reloj se apaga: al cargarlo y abrir la app, la grabación debe cerrarse como **parcial** con los puntos guardados y llegar al teléfono. Anotar la hora del apagado y si llegó.
- Si no se apaga, anotar con qué porcentaje terminó y el consumo por hora (comparar con la salida 3).

**Salida 6 — modo B completo.**
- Iniciar BSC100S y **Grabar ruta** en el reloj con menos de un minuto de diferencia; anotar ambas horas de inicio y de fin.
- Ruta con algo de desnivel y alguna zona arbolada o entre edificios, para ver la continuidad del GPS.
- Esta es la que alimenta las tolerancias: los archivos de los dos aparatos son obligatorios (§6).

## 5. Plantilla de resultados

Copiar una por salida al final de este documento (o mandármela y yo la paso).

```
### Salida N — <tipo> — <fecha> — modo <A|B>

Ruta: <km aprox., desnivel aprox.>  ·  Clima: <…>  ·  Combinada con: <ninguna | salida M>
Inicio BSC100S <hh:mm> · reloj <hh:mm>  ·  Fin BSC100S <hh:mm> · reloj <hh:mm>
Paradas largas: <hora y duración aprox.>

Batería reloj: <inicio> % → <fin> % en <h:mm>  (<x> %/h)  ·  por hora: <…>
Batería teléfono: <inicio> % → <fin> %

Alertas: subidas <n> · bajadas <n> · repetidas sin cambio de esfuerzo <n> · perdidas <n>
Momentos sin lectura («--») o cortes de sesión: <cuándo y qué mostró>

Strava: actividades = <1?>  ·  Velora: actividades = <1?>
Modo B: grabación secundaria en Velora a los <min> (o tras recuperar señal: <min>) · parcial: <sí/no>
Última actividad en el reloj: «Sincronizada» a los <min>
Samsung Health sin entrenamiento nuevo: <sí/no>
Lo específico (§4): <…>
Archivos enviados: <BSC100S .fit/.gpx · reloj .gpx · capturas>
Observaciones: <…>
```

## 6. Qué me pasas y cómo lo comparo

**Archivos por salida:**
- **BSC100S:** el `.fit` desde la app iGPSPORT (la salida → **exportar .fit**; queda en `Descargas/Archivos` del teléfono, `02` §10). Si no, el original desde Strava web (actividad → ⋯ → **Exportar original**). Un GPX también sirve, pero sin la altitud barométrica cruda.
- **Reloj (modo B):** el GPX de la grabación secundaria desde Velora; si no llegó, se saca del reloj por adb (README, modo B) antes de que viaje al teléfono.
- **Capturas** de la pantalla final del reloj y de Velora.

Guárdalos en una carpeta fuera del repositorio (por ejemplo `~/pruebas-campo/salida-N/`) y dime la ruta. **No los subas al repositorio:** tienen tu ubicación, incluidos inicio y fin de ruta (R-13).

**Comparativa (salidas 4 a 6, con los dos archivos):**

| Métrica | BSC100S | Reloj | Diferencia |
|---|---|---|---|
| Distancia (km) | | | |
| Tiempo transcurrido | | | |
| Tiempo en movimiento | | | |
| Desnivel positivo (m) | | | |
| Continuidad: huecos > 10 s (número y duración total) | | | |
| FC media / máxima | | | |
| Desfase de reloj entre aparatos (s) | | | |
| Batería consumida | | | |
| Tiempo hasta Velora | | | |

Yo saco la tabla de los archivos (distancia, tiempos, desnivel, huecos, FC y desfase); tú aportas batería, tiempos de sincronización y observaciones.

## 7. Cierre

**WC-35** (salidas 1 y 2): según `10` §6.

**WC-57** (salidas 3 a 6):
- Las cuatro salidas con plantilla y evidencia.
- Comparativa BSC100S vs. reloj (§6) de las salidas en modo B.
- **Tolerancias propuestas** para WC-55 a partir de la línea base: diferencia aceptable de distancia (%), de desnivel (m o %), desfase máximo esperado y duración de hueco a partir de la cual se marca y no se interpola (`02` §4).
- Una tarjeta abierta por cada problema encontrado.

Los resultados se agregan al final de este documento en el PR que cierra cada tarjeta.
