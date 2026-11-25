# 07 — Seguridad y privacidad

## 1. Principios

- Privilegio mínimo.
- Consentimiento explícito por módulo.
- No compartir tokens entre Velora y Nexora.
- No almacenar datos de salud/ubicación más tiempo del necesario.
- Cifrar información sensible en reposo.
- Usar HTTPS y validar certificados.
- No incluir secretos en APK, logs, repositorios ni documentación.

## 2. Datos sensibles por dominio

### Velora
Ubicación, rutas, frecuencia cardíaca y métricas deportivas. Permitir desactivar telemetría del reloj y borrar sesiones locales no sincronizadas.

#### Privacidad de inicio y fin de ruta (R-13, WC-11)

Velora ya oculta el inicio y el fin de cada recorrido (ADR-016 de Velora, migración `V7__track_privacy`). El companion se apoya en esa política y no la duplica:
- **Herencia:** cualquier track del reloj que llegue a Velora (modo B, Fase 5) entra por la misma importación y hereda la privacidad de inicio y fin de Velora. El companion no pide ni ofrece desactivarla.
- **Sin coordenadas exactas fuera de la app:** el companion no muestra ni comparte coordenadas exactas en notificaciones, complicaciones, tiles, logs, diagnósticos ni mensajes de la Data Layer. Si alguna vez hace falta indicar un lugar fuera de la app, se usa la geometría que Velora ya recortó, nunca el track crudo.
- **Track crudo local:** mientras una grabación del reloj no se haya subido, el track crudo vive solo en el almacenamiento privado de la app del reloj, excluido de copias de seguridad (`noBackupFilesDir/recordings`, `allowBackup=false`), y se borra al confirmarse la subida o cuando el usuario lo pide. El reloj borra su copia en cuanto el teléfono confirma que la tiene, y el teléfono borra la suya (`noBackupFilesDir/grabaciones-del-reloj`) en cuanto Velora la confirma o la rechaza (WC-54). En Velora se puede borrar cada grabación (`DELETE /secondary-recordings/{id}`) y se incluye en la exportación (ADR-032 de Velora).
- **Modo A:** el companion no maneja coordenadas. Activar Velora no pide ubicación (`ModeAWatchTest`), la sesión de métricas no enciende el GPS ni escribe archivos (`LiveMetricsSessionTest`) y la última actividad que muestra solo lleva fecha y distancia (WC-33). La ubicación se pide solo al tocar «Grabar ruta» (modo B).

### Nexora
Saldos, tarjetas, movimientos y pagos. Ocultar contenido sensible de notificaciones por defecto.

### Guardian
Eventos de bloqueo y proximidad. No recopilar ubicación continua para detectar una simple desconexión Bluetooth.

### Smart Hub
Estados y eventos de hogar. No habilitar comandos de cerradura sin autorización y confirmación apropiadas.

## 3. Credenciales

- Android Keystore para claves locales.
- Tokens con scopes mínimos y expiración.
- Refresh tokens protegidos y revocables.
- Pairing mediante código/nonce de un solo uso.
- No enviar bearer tokens por Data Layer.
- No colocar client secrets de OAuth en aplicaciones móviles.

## 4. Logs

Permitido:
- identificador aleatorio de operación
- módulo
- código de resultado
- duración
- versión de app

Prohibido:
- access/refresh tokens
- client secrets
- números completos de cuenta/tarjeta
- coordenadas GPS precisas en logs
- payloads financieros completos
- muestras de frecuencia cardíaca completas salvo entorno de prueba consentido

## 5. Retención y borrado

Definir políticas por dominio:
- telemetría deportiva: retención configurable
- archivos originales: preservar mientras el usuario mantenga la actividad o según política de Velora
- snapshots financieros: caché corta
- eventos Guardian: historial local configurable
- eventos Smart Hub: mínimo necesario

El usuario debe poder revocar el companion y eliminar datos locales. El borrado en backend debe seguir las reglas de cada producto.
