# Manual de uso de Navi Moto

Navi Moto tiene dos partes que trabajan juntas:

- **La placa**: la pantalla redonda que va en el manillar, con este firmware.
- **La app Navi Moto** en el celular Android: calcula las rutas, lee la navegación de Google Maps o CoMaps y le envía todo a la placa por Bluetooth.

La placa sola no navega: siempre necesita la app conectada.

## Lo que necesitas

- La placa Waveshare ESP32-S3 Touch AMOLED 1.75 o 1.75C, con este firmware. Para grabarlo, mira [Compilar y grabar](https://github.com/fvalladaresg1975/navi-moto-firmware#compilar-y-grabar) o los archivos de cada [versión](https://github.com/fvalladaresg1975/navi-moto-firmware/releases).
- La app Navi Moto: descarga el APK de la [última versión de la app](https://github.com/fvalladaresg1975/navi-moto/releases/latest). Necesita Android 8.0 o superior.
- Bluetooth, GPS e internet activados en el celular.
- Para el modo CoMaps: la app [CoMaps](https://comaps.app), versión de agosto de 2026 o más nueva.

## 1. Primera vez: conectar la app con la placa

1. Enciende la placa con el botón **PWR**.
2. Abre la app y toca **1. Habilitar acceso a notificaciones**. Activa *Navi Moto* en la lista y vuelve a la app. Sin este paso no funcionan las notificaciones ni los modos Google Maps y CoMaps.
3. Toca **2. Conectar con el navegador** y acepta los permisos que pida (Bluetooth, ubicación y, si quieres, llamadas y contactos).
4. Cuando arriba diga **Estado del navegador: Conectado**, ya está. Desde ahí la app se reconecta sola.

Si tienes más de una placa, usa **Cambiar de navegador** al final de la pantalla de la app.

<img src="docs/images/app-principal.png" alt="Pantalla principal de la app, conectada" width="260">

## 2. Los tres modos de navegación

| | Modo ruta | Modo Google Maps | Modo CoMaps |
|---|---|---|---|
| **Cómo se inicia** | Compartes un destino con la app («Ruta al navegador») | Inicias la navegación en Google Maps como siempre | Inicias la navegación en CoMaps como siempre |
| **Quién calcula la ruta** | La app Navi Moto (OpenStreetMap) | Google Maps | CoMaps (OpenStreetMap) |
| **Qué muestra la placa** | El camino dibujado con las calles alrededor, como un mapa | Una flecha grande con la indicación escrita | El camino de CoMaps dibujado con las calles alrededor, como en el modo ruta |
| **Quién da la voz** | La app Navi Moto | Google Maps | CoMaps |
| **Opciones** | Evitar autopistas, evitar peajes | Las de Google Maps | Las de CoMaps |
| **Necesita** | GPS e internet al calcular | Acceso a notificaciones (paso 2 de arriba) | Acceso a notificaciones y el interruptor **Seguir la navegación de CoMaps** |

En los tres modos la placa muestra tu velocidad y el límite de la vía, que se pone en rojo si lo pasas.

## 3. Modo ruta

<img src="https://raw.githubusercontent.com/fvalladaresg1975/navi-moto-firmware/main/docs/images/navegacion-ruta.png" alt="Modo ruta en la placa" width="260">

### Iniciar una ruta

1. Busca el destino en **Google Maps** o en **CoMaps**.
2. Toca **Compartir** y elige **Ruta al navegador**.
3. La app busca tu ubicación y calcula la ruta. Aquí puedes marcar **Evitar autopistas** y **Evitar peajes (TAG)**, y lees un resumen con la distancia, el tiempo y el primer giro.
4. Toca **Iniciar en el navegador**. La placa abre la navegación sola (mira la sección 6).

Desde **CoMaps** el destino llega con coordenadas exactas. Desde **Google Maps**, a veces la app tiene que buscar el lugar por su nombre: revisa que el destino que muestra sea el correcto.

### Qué ves en la placa

- **Caja azul**: la flecha del próximo giro y cuántos metros faltan.
- **Línea blanca**: el camino que tienes por delante, sobre las calles de la zona.
- **Número a la izquierda**: tu velocidad. Se pone roja si pasas el límite.
- **Círculo rojo**: el límite de velocidad de la vía.
- **Abajo**: la distancia que falta hasta el destino.
- **Anillo naranja**: cuánto falta para el próximo giro.
- **Zoom automático**: cerca en ciudad y más lejos en carretera.

El celular da las indicaciones con voz y con un pitido antes de cada giro (el pitido se apaga en la app). Si te sales del camino, la app recalcula la ruta sola.

### Terminar la ruta

Cualquiera de estas tres:
- En la placa, **desliza hacia la izquierda** y toca **Salir**.
- En la app, toca **Detener ruta en el navegador**.
- En el widget de la pantalla de inicio, toca **Detener ruta**.

### Probar sin salir

En la app toca **Probar ruta en el navegador**: la placa recorre una ruta inventada, primero en modo ruta y después con la flecha. Sirve para conocer las pantallas antes de salir, y no se guarda como viaje.

## 4. Modo Google Maps

<img src="https://raw.githubusercontent.com/fvalladaresg1975/navi-moto-firmware/main/docs/images/navegacion-flecha.png" alt="Modo Google Maps en la placa" width="260">

1. Revisa que la app tenga **acceso a notificaciones** (paso 2 de la primera vez) y que Google Maps tenga sus notificaciones activadas en Android.
2. Con la placa conectada, abre **Google Maps**, elige el destino y toca **Iniciar**.
3. La placa abre la navegación sola: muestra la flecha de Google Maps, los metros hasta la maniobra, la indicación escrita, tu velocidad y el límite. La línea de abajo resume lo que falta.

Para terminar, sal de la navegación en Google Maps, o en la placa **desliza hacia la izquierda** y toca **Salir**: la app cierra la navegación de Google Maps en el celular.

## 5. Modo CoMaps

La navegación de **CoMaps** se ve en la placa igual que el modo ruta: el camino que calculó CoMaps dibujado sobre las calles de la zona, la flecha y la distancia del próximo giro, tu velocidad y el límite de la vía.

### Activarlo (una sola vez)

1. Instala o actualiza **CoMaps** (versión de agosto de 2026 o más nueva).
2. En la app Navi Moto activa **Seguir la navegación de CoMaps** y acepta el permiso de CoMaps que pide Android.

### Navegar

1. Con la placa conectada, abre **CoMaps**, elige el destino y toca **Iniciar**.
2. La placa abre la navegación sola. Las indicaciones con voz las da CoMaps; el pitido antes de cada giro sigue funcionando (se apaga en la app).
3. Si te sales del camino, CoMaps recalcula la ruta y la placa toma la nueva.

### Terminar

- **Detén la navegación en CoMaps**: la placa la cierra al instante.
- En la placa, **desliza hacia la izquierda** y toca **Salir**: se cierra la navegación en la placa, pero **CoMaps sigue navegando en el celular**, porque CoMaps no deja que otra app la detenga. La próxima navegación que inicies en CoMaps se abre sola en la placa.

## 6. La placa durante la navegación

| Gesto | Qué hace |
|---|---|
| Deslizar hacia abajo | Panel de música, volumen y baterías. Deslizando hacia arriba vuelves a la navegación. |
| Deslizar hacia la izquierda | Botón **Salir** para terminar la navegación. Deslizando hacia la derecha se cierra sin salir. |
| Deslizar hacia la derecha | Sale a la lista de apps sin terminar la navegación. |

Con **Iniciar navegación automática** activado en los **Ajustes** de la placa (viene así), la navegación se abre sola al empezar, y si te vas a otra pantalla la placa **vuelve sola a la navegación** después de 15 segundos sin tocarla, para que no tengas que buscarla en marcha.

## 7. Moverse por la placa

Desde la esfera (la pantalla con la hora):

| Gesto | Qué abre |
|---|---|
| Deslizar hacia abajo | Panel de control: música, volumen y batería de la placa y del celular |
| Deslizar hacia arriba | Clima |
| Deslizar hacia la izquierda | Lista de apps |
| Deslizar hacia la derecha | Notificaciones |
| Mantener presionado | Elegir otra esfera |

<img src="https://raw.githubusercontent.com/fvalladaresg1975/navi-moto-firmware/main/docs/images/esfera-pixel-moto.png" alt="Esfera Pixel Moto" width="200"> <img src="https://raw.githubusercontent.com/fvalladaresg1975/navi-moto-firmware/main/docs/images/esfera-tablero.png" alt="Esfera Tablero" width="200"> <img src="https://raw.githubusercontent.com/fvalladaresg1975/navi-moto-firmware/main/docs/images/placa-control.png" alt="Panel de control" width="200">

## 8. Otras funciones

- **Detección de caídas**: en la app, **Detección de caídas**. Agrega hasta 3 contactos de emergencia y actívala. Solo vigila con la moto en marcha (más de 20 km/h según el GPS del celular). Si detecta una caída, la placa cuenta 30 segundos; si nadie toca **Estoy bien**, el celular envía un SMS con tu ubicación. El botón **Probar** envía un SMS que dice «PRUEBA»: avísales antes a tus contactos.
- **Batería baja**: la placa avisa al 15 %. Al 5 % cuenta 30 segundos y se apaga, salvo que toques **Seguir encendida**. Durante la navegación solo muestra avisos pequeños y sigue encendida hasta que la batería está casi vacía. Se vuelve a encender con **PWR**.
- **Actualizar el firmware sin cable**: en la app, **Actualizar firmware del navegador**, elige el `.bin` de tu placa (1.75 o 1.75C) desde la [página de versiones](https://github.com/fvalladaresg1975/navi-moto-firmware/releases) y toca **Actualizar el navegador**. Si el firmware nuevo falla, la placa vuelve sola a la versión anterior.
- **Esfera con foto**: en la app, **Esfera con foto**. Elige una foto, ajústala en el círculo y envíala; luego, en la placa, mantén presionada la esfera y elige **Foto**.
- **Notificaciones y llamadas**: en la app, **Elegir apps de notificaciones** decide qué apps llegan a la placa. Las llamadas se contestan o rechazan desde la placa.

## Problemas frecuentes

| Qué pasa | Qué hacer |
|---|---|
| La app dice «No encontré un destino» o «No pude obtener la ubicación del lugar de Google Maps» | Comparte el destino desde CoMaps, que envía coordenadas exactas. |
| La app pide **ubicación precisa** | En Android: Ajustes → Apps → Navi Moto → Ubicación → Usar ubicación precisa. |
| «No pude conectar con el servidor de rutas» | Revisa la conexión a internet del celular y vuelve a compartir el destino. |
| Con Google Maps la placa no muestra nada | Revisa el acceso a notificaciones de la app (botón 1) y que Google Maps tenga las notificaciones activadas. |
| Con CoMaps la placa no muestra nada | Revisa que **Seguir la navegación de CoMaps** esté activado en la app, el acceso a notificaciones (botón 1) y que CoMaps esté actualizado. |
| «No se pudo salir de la navegación» | No hay una navegación activa en Google Maps; si estabas en modo ruta, deténla desde la app. |
| La app dice **Detenido** o la placa no se conecta | Revisa que la placa esté encendida y el Bluetooth activado, y toca **2. Conectar con el navegador**. |
