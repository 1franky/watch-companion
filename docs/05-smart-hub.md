# 05 — Smart Hub: hogar, SmartThings y TTLock

## 1. Objetivo

Controlar desde Galaxy Watch Ultra los dispositivos y escenarios domésticos compatibles, usando el S26 Ultra como puente cuando sea necesario.

## 2. SmartThings

Primera etapa:
- consultar estado de dispositivos autorizados
- activar escenas permitidas
- controlar luces y enchufes compatibles
- recibir alertas de automatizaciones

Antes de implementar, verificar el estado actual de las APIs/SDK oficiales de SmartThings, requisitos de OAuth, scopes, publicación y compatibilidad regional.

No asumir que todas las funciones disponibles en la app SmartThings están expuestas por una API para aplicaciones de terceros.

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
