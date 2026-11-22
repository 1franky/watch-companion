# 10 — Pruebas de campo de la Fase 1 (WC-35)

Protocolo para las dos salidas reales que cierran la Fase 1 (`06` §3, salidas 1 y 2): **ruta urbana corta** y **MTB con desnivel**, en el **modo A** (el BSC100S graba; el reloj solo muestra métricas y alerta).

Objetivo: comprobar en el mundo real lo que las pruebas automatizadas no pueden, y dejar una **línea base** de batería y alertas. No se fijan tolerancias hasta tener esa línea base (`06` §3).

## 1. Antes de salir (una vez)

Requiere WC-29 a WC-34 en `develop` e instalación en los dispositivos reales (WC-24).

- [ ] Instalar **las dos apps desde la misma computadora** (README → instalación): `./gradlew :app-phone:installDebug :app-watch:installDebug`.
- [ ] Teléfono: iniciar sesión en Velora; **Zonas de frecuencia cardíaca → Usar la FC máxima de Velora → Guardar y enviar al reloj**. Anotar FC máxima y límites.
- [ ] Reloj: abrir la app; **Resumen** muestra «Conectado a …», «A · iGPSPORT principal», el estado de Velora y la última actividad.
- [ ] Reloj: activar el módulo Velora y conceder el permiso de frecuencia cardíaca.
- [ ] Captura del Resumen del reloj y de la pantalla de zonas del teléfono (evidencia de WC-24 y WC-32).

## 2. Verificaciones en el reloj que quedaron pendientes

Salieron de las revisiones de WC-29, WC-30 y WC-31; solo se pueden comprobar en el Galaxy Watch Ultra.

| # | Verificación | Cómo | Resultado |
|---|---|---|---|
| V1 | El foreground service de tipo `health` arranca solo con `READ_HEART_RATE` | Iniciar métricas; `adb -s <reloj> shell dumpsys activity services mx.watchcompanion` muestra `LiveMetricsService` en primer plano | |
| V2 | Las lecturas siguen con la pantalla apagada y en ambiente | Bajar la muñeca 5 min; al subirla, la FC y la duración siguen al día | |
| V3 | Samsung Health **no** guarda la sesión como entrenamiento propio | Tras terminar, revisar Samsung Health en reloj y teléfono: no debe aparecer un ciclismo nuevo | |
| V4 | Modo ambiente del Resumen y de Métricas legible | Captura en ambiente | |
| V5 | TalkBack lee «Teléfono, Conectado a …» y «Frecuencia cardíaca, N latidos por minuto, zona Z» | Activar TalkBack un momento | |
| V6 | Las alertas vibran con No molestar activo (`USAGE_ALARM`) | Activar No molestar y provocar un cambio de zona | |
| V7 | Las alertas funcionan en modo avión | Activar modo avión en el reloj durante un tramo | |

Si V1 o V3 fallan, **no** se cumple el criterio de la Fase 1: abrir tarjeta antes de seguir.

## 3. Durante cada salida

1. Anotar **batería del reloj y del teléfono** al salir.
2. Iniciar la grabación en el BSC100S como siempre.
3. En el reloj: **Iniciar métricas**.
4. Rodar normal. Anotar a mano (o con nota de voz al terminar) las **alertas** que se sintieron: subida/bajada y si alguna se repitió sin cambiar de esfuerzo.
5. En un tramo, alejarse del teléfono o activar modo avión en el reloj (V7): las métricas y alertas deben seguir.
6. Al llegar: terminar la grabación del BSC100S y luego **Terminar → Sí, terminar** en el reloj. Captura de la duración.
7. Anotar **batería del reloj y del teléfono** al llegar.

## 4. Después de cada salida

- [ ] Strava: **una sola** actividad de la salida (la del BSC100S).
- [ ] Velora: **una sola** actividad de la salida, importada de Strava.
- [ ] Reloj → Resumen → Última actividad: pasa a **Sincronizada** con la fecha y distancia de la salida, y «Consultado hace …». Anotar cuánto tardó desde que terminó la salida.
- [ ] Samsung Health: sin entrenamiento nuevo del reloj (V3).
- [ ] Teléfono → Diagnóstico: sin errores inesperados (captura).

## 5. Plantilla de resultados

Copiar una por salida.

```
### Salida N — <urbana corta | MTB con desnivel> — <fecha>

Ruta: <km aprox., desnivel aprox.>  ·  Clima: <…>  ·  Duración BSC100S: <h:mm>
Duración en el reloj (métricas en vivo): <h:mm:ss>

Batería reloj: <inicio> % → <fin> %  (consumo: <x> % en <h:mm> = <x/h> %/h)
Batería teléfono: <inicio> % → <fin> %

FC: media BSC100S/banda <…> · rango visto en el reloj <…>
Alertas: subidas <n> · bajadas <n> · repetidas sin cambio de esfuerzo <n> · perdidas (cambio sin vibración) <n>
Momentos sin lectura («--»): <cuándo y motivo mostrado>

Strava: actividades de la salida = <1?>  ·  Velora: actividades de la salida = <1?>
Última actividad en el reloj: «Sincronizada» a los <min> de terminar
Verificaciones V1–V7: <✓/✗ por cada una>
Evidencia: <capturas / video>
Observaciones: <…>
```

## 6. Criterio de cierre de WC-35

- Dos salidas registradas con el BSC100S, con su plantilla y evidencia.
- Una sola actividad en Velora y en Strava por salida.
- Consumo de batería y comportamiento de las alertas anotados como **línea base**. Si hubo alertas repetidas o perdidas, abrir tarjeta para ajustar la histéresis (3 lpm) o la permanencia (5 s) de WC-31.
- V1–V7 resueltas (✓, o tarjeta abierta para cada ✗).

Los resultados se agregan al final de este documento en el mismo PR que cierra WC-35.
