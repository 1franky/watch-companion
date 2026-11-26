# 05 — Smart Hub: hogar, SmartThings y TTLock

## 1. Objetivo

Controlar desde Galaxy Watch Ultra los dispositivos y escenarios domésticos compatibles, usando el S26 Ultra como puente cuando sea necesario.

## 2. SmartThings

Primera etapa:
- consultar estado de dispositivos autorizados
- activar escenas permitidas
- controlar luces y enchufes compatibles
- recibir alertas de automatizaciones

No asumir que todas las funciones disponibles en la app SmartThings están expuestas por una API para aplicaciones de terceros.

### Resultado del spike (WC-48, 26 nov 2026)

**Credenciales.**

| Vía | Caducidad | Para el companion |
|---|---|---|
| Token personal (PAT, account.smartthings.com/tokens) | **24 h** desde su creación (tokens creados desde el 30 dic 2024) | Solo pruebas: habría que pegar uno nuevo cada día. |
| OAuth 2.0 con una «API Access App» (CLI de SmartThings) | Acceso 24 h; refresh token 30 días, se renueva al usarlo | Viable, pero el token se pide y se renueva con `client_id` + **`client_secret`** (Basic auth en `/oauth/token`): el secreto no puede ir en el APK. Se decide en WC-12 (R-09). |

**Costo.** Samsung anunció (23 jun 2026) planes de pago para la API desde octubre de 2026; para un desarrollador individual, un plan personal de **US$4.99/mes**. La app SmartThings sigue gratis. No hay publicados umbrales exactos. Entra en la decisión de WC-12: Smart Hub tendría un costo recurrente.

**Scopes necesarios** (formato `permiso:recurso:filtro`): `r:locations:*`, `r:devices:*`, `x:devices:*` (comandos), `r:scenes:*`, `x:scenes:*`. Las rutinas (`/rules`) piden otro scope y no son del MVP. Región: el endpoint es global (`api.smartthings.com`); las ubicaciones de la cuenta no traen `countryCode`, y no hizo falta nada por región.

**Capacidades reales** (cuenta del usuario):
- **Dispositivos:** 6, todos Samsung y OCF: 4 televisores y 2 sensores de luz de The Frame. **Sin luces, enchufes ni escenas** por ahora. Cada dispositivo trae sus capacidades (`switch`, `audioVolume`, `mediaInputSource`…); la app debe ofrecer solo acciones de capacidades presentes.
- **Escenas:** `GET /v1/scenes` y `POST /v1/scenes/{id}/execute`; vía API solo se ven y ejecutan. La cuenta tiene 0.
- **Eventos:** las suscripciones en tiempo real exigen una SmartApp con webhook (un servidor). Sin servidor solo queda consultar; las «alertas de automatizaciones» quedan fuera hasta WC-12.
- **Límite:** 250 peticiones por ventana (`X-RateLimit-*`; el reinicio marcaba ~8 min). El reloj no debe consultar en bucle: estado al abrir la pantalla y tras un comando.

**Llamada de prueba** (PAT temporal, ya borrado): `GET /locations`, `/devices`, `/scenes` respondieron en ~250 ms. `switch on` y luego `off` a un televisor propio (FRAME 2): la tele se encendió y se apagó.

**Reglas que salen del spike:**
- **`COMPLETED` no es «hecho».** `POST /devices/{id}/commands` respondió `COMPLETED` en 0.5–0.9 s, pero el estado del `switch` seguía igual al segundo y cambió hacia los 3 s. `SUCCEEDED` (§4) solo cuando `GET …/capabilities/switch/status` muestre el valor pedido con un `timestamp` posterior al comando; consultar unos segundos y, si no cambia, `FAILED`/sin confirmar.
- **Desconocido ≠ apagado.** Un televisor con `GET /devices/{id}/health` = `OFFLINE` seguía devolviendo `switch=off` (su último valor). Con salud `OFFLINE` el estado se muestra como desconocido, nunca como apagado (criterio de §5).
- **Encender desde la nube funciona** con un televisor en reposo (`samsungvd.supportsPowerOnByOcf`); no se probó uno totalmente desconectado de la red.

**Lo que queda para WC-12:** OAuth exige `client_secret` y servidor para eventos, y la API tendrá costo mensual. Opciones: un BFF mínimo propio (secreto y webhook fuera del APK), que el usuario registre su propia API Access App e introduzca su `client_id`/`client_secret` en el teléfono (cifrados, nunca en el APK), o posponer Smart Hub.

## 3. Cerradura XE Seguridad / TTLock

El usuario dispone de una cerradura XE Seguridad que utiliza TTLock. El modelo exacto y el tipo de integración deben verificarse.

Primera etapa:
- mostrar estado si está disponible
- mostrar batería/alertas si la API lo permite
- recibir eventos de apertura/cierre cuando exista una fuente autorizada

No habilitar apertura remota en el MVP.

Para considerar apertura/cierre remoto se requiere:
- confirmar modelo exacto y compatibilidad
- validar acceso oficial a TTLock Open Platform
- revisar permisos y flujo de autenticación
- definir confirmación explícita en el teléfono
- registrar auditoría
- establecer comportamiento offline y expiración
- evaluar riesgo de apertura accidental

No automatizar apertura por proximidad, detección de sueño ni pérdida de Bluetooth.

## 4. Modelo de comandos

Todo comando debe tener:
- `commandId`
- `targetDeviceId`
- `action`
- `requestedAt`
- `expiresAt`
- `requestedBy`
- `confirmationRequired`
- `status`

Estados:
- `PENDING`
- `CONFIRMATION_REQUIRED`
- `SENT`
- `SUCCEEDED`
- `FAILED`
- `EXPIRED`
- `CANCELLED`

No mostrar `SUCCEEDED` hasta recibir confirmación del proveedor/dispositivo.

## 5. Criterios de aceptación

- Los dispositivos se muestran solo después de una vinculación explícita.
- La app diferencia estado desconocido de apagado.
- Comandos caducados no se ejecutan.
- La apertura de cerradura permanece deshabilitada hasta completar evaluación de seguridad.
- Errores de proveedor se comunican claramente.
