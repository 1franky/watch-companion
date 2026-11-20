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
