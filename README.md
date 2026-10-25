# Navi Moto

App Android que convierte una pantalla redonda **Waveshare ESP32-S3 Touch AMOLED 1.75** montada en el manillar en un **navegador para moto**, al estilo Beeline / BaRing. Trabaja junto al firmware de la placa: **[navi-moto-firmware](https://github.com/fvalladaresg1975/navi-moto-firmware)**.

Empezó como una alternativa abierta a la app oficial de Chronos para relojes con el firmware [ChronosESP32 de fbiego](https://github.com/fbiego/chronos-esp32), y conserva su protocolo Bluetooth LE, más comandos propios (0xC1-0xC9) que el firmware decodifica sin modificar la librería.

## Funciones

- **Modo ruta** — comparte un destino desde CoMaps o Google Maps ("Ruta al navegador"): la app calcula la ruta con [Valhalla](https://valhalla1.openstreetmap.de) (perfil moto, evitar autopistas/peajes; OSRM de respaldo) y la placa dibuja el camino por delante, con flecha y distancia del próximo giro, voz en español, pitido antes de cada giro, recálculo al salirse de la ruta y estimación de avance en túneles.
- **Calles alrededor** — calles de OpenStreetMap ([Overpass](https://overpass-api.de)) dibujadas tenues bajo la ruta. Las de toda la ruta se guardan en el teléfono al compartirla (cuadrantes de ~1 km, reutilizados en rutas siguientes y sin internet, límite automático de 50 MB).
- **Zoom según la velocidad** — cerca en ciudad, lejos en carretera.
- **Velocidad y límite** — velocidad GPS y límite de la vía (OpenStreetMap; 50 km/h en calles residenciales sin dato), con aviso rojo al excederlo.
- **Navegación de Google Maps** — reenvía las indicaciones y la flecha de Maps, con velocidad y límite.
- **Notificaciones, llamadas y contactos** — WhatsApp, SMS, etc.; contestar/rechazar y llamar desde la placa.
- **Música, clima (Open-Meteo) y hora**; el clima incluye probabilidad de lluvia por hora y atardecer para las esferas de la placa.
- **Batería del teléfono** en el panel de control de la placa.
- **Widget** de pantalla de inicio: estado, conectar, detener ruta, pitido, calles y brillo de la pantalla de la placa.

## Requisitos

- La placa con el firmware [navi-moto-firmware](https://github.com/fvalladaresg1975/navi-moto-firmware).
- Android 8.0 (API 26) o superior.

## Compilar

```bash
./gradlew assembleDebug
```

El APK queda en `app/build/outputs/apk/debug/`. La versión release (`assembleRelease`) se firma con la clave de `keystore/keystore.properties`, que no está en el repositorio: sin ella se compila igual la versión debug.

## Permisos

| Permiso | Para qué |
|---|---|
| Bluetooth (buscar/conectar) | Hablar con la placa. |
| Acceso a notificaciones | Reenviar notificaciones y leer la navegación de Google Maps. |
| Ubicación precisa | Seguir la ruta, velocidad, clima. |
| Teléfono y contactos (opcional) | Mostrar y contestar llamadas, llamar desde la placa. |
| Servicio en primer plano | Mantener la conexión y el GPS con la pantalla apagada. |
| Internet | Rutas, calles, límites y clima. |

## Privacidad

No hay cuentas ni servidores propios. Salen del teléfono solo estas consultas: coordenadas de origen/destino a Valhalla (u OSRM) para la ruta y los límites; la zona de la ruta a Overpass para las calles; el nombre del destino a Nominatim cuando viene de Google Maps sin coordenadas; las coordenadas a Open-Meteo para el clima. Todos son servicios públicos de OpenStreetMap/Open-Meteo.

## Licencia

MIT — ver [LICENSE](LICENSE). Datos de mapas © colaboradores de OpenStreetMap (ODbL).

Proyecto personal, sin relación con fbiego/ChronosESP32 ni con la app oficial de Chronos.
