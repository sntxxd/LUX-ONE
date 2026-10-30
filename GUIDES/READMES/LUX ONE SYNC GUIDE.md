# LUX ONE: Appearance and Pairing

## Installation

Build the Android app and update `BCM_Principal`. The rear node and light codes do not change. Keep `Erase All Flash` disabled during normal updates to preserve authorized devices and settings.

## First Connection

1. Turn on the BCM. On Android, pair `BCM-VW1980-Main` using the configured PIN.
2. Open LUX ONE, grant Nearby Devices permissions, tap Bluetooth, and select the car.
3. When `STATE` is received, the app saves the car's address. Controls are enabled after that response, not merely upon opening the socket.
4. Upon reopening the app, it searches for the saved car and retries if it is still off.

The ESP32 adopts the first authenticated bond when there is exactly one in its list. If updating a module that already had multiple bonds, it adopts the first one that opens an SPP connection. It then supports up to 8 authorized devices in total. The list is stored in NVS; pairing keys are maintained by the BT stack. Unauthorized phones may appear paired in Android, but their SPP connection is rejected. Both the Bluetooth bond and the saved list are verified.

Searching consists of retrying the saved paired device. It works while the app is in the foreground; exiting the app closes the connection. There is no background continuous search nor proximity unlock. Lock commands or horn triggers are never resent upon reconnecting.

## Appearance

In Settings, choose System, Light, or Dark. It is saved on the phone. System follows Android's night mode. Rounded cards and a neutral palette inspired by the references; original vector illustration of a classic sedan. System typography (no external font downloads required).

## Adding a Phone or Tablet

1. From a connected, authorized device, go to Settings → Add phone or tablet.
2. Wait for ESP32 confirmation: a 120-second window appears.
3. Tap "Yield connection to another device" (pauses reconnection in this session).
4. Pair the new phone/tablet with the PIN and open LUX ONE to select the BCM.
5. The list is saved, and the window closes after a single addition.

If the second device was already paired, open its connection from LUX ONE within the window. New devices are authorized upon detecting their bond; previously paired devices are authorized upon opening SPP during that window. Only open this window when you intend to add a trusted device using the PIN.

All authorized devices can open another window. Both mobile and tablet remain registered simultaneously, but `BluetoothSerial` only supports a single active control connection. Close the app on the first device or yield its connection to use the other. There has been no switch to BLE/Wi-Fi or a multi-client SPP server. There is no USB recovery tool or `BORRAR_PROPIETARIO` command. "Forget car in this app" only removes the local record and does not revoke its authorization. This version adds devices; individual revocation is not yet included.

## Pending Bench Test on Hardware

* Pair the M31, connect, and verify that states are received before enabling controls.
* Close/open the app and restart the ESP32: both retain the record and reconnect.
* Cut power: in approximately 5–7 seconds the app disables controls; when power is restored, it reconnects without touching locks or horn.
* Pair another phone: it must not be able to query states or execute commands.
* Switch between the three themes, rotate the screen, and restart the app.
* Deny permissions / turn off Bluetooth: the app must not crash; restore them and return.
* Authorize a tablet during the window; verify that it persists upon restart.
* Let the window expire and confirm that a third device is not authorized.
* Yield connection between the two authorized devices without repeating pairing.

The reported state represents commanded outputs, not confirmation of actual current or a physically illuminated bulb: that detection would require sensors.



# LUX ONE: apariencia y vinculación

## Instalar

Compilar la app Android y actualizar BCM_Principal. El nodo trasero y los códigos
de luces no cambian. Mantener `Erase All Flash` desactivado en actualizaciones
normales para conservar dispositivos autorizados y preferencias.

## Primera conexión

1. Encender el BCM. En Android, emparejar `BCM-VW1980-Main` con el PIN configurado.
2. Abrir LUX ONE, conceder Dispositivos cercanos, tocar Bluetooth y seleccionar el auto.
3. Cuando llega STATE, la app guarda la dirección del auto. Los controles se habilitan
   después de esa respuesta, no solo por abrir el socket.
4. Al volver a abrir la app, busca el auto guardado y reintenta si todavía está apagado.

El ESP32 adopta el primer vínculo autenticado cuando hay exactamente uno en su lista.
Si se actualiza un módulo que ya tenía varios vínculos, adopta al primero de ellos que
abra una conexión SPP. Después admite hasta 8 dispositivos autorizados en total.
La lista se guarda en NVS; las claves de emparejamiento las conserva el stack BT.
Los teléfonos no autorizados pueden aparecer emparejados en Android, pero su conexión
SPP se rechaza. Se comprueba tanto el bond Bluetooth como la lista guardada.

La búsqueda consiste en reintentar el dispositivo emparejado guardado. Funciona con
la app en primer plano; al salir, se cierra la conexión. No hay búsqueda continua
con la app cerrada ni desbloqueo por proximidad. Nunca se reenvían órdenes de seguros
o claxon al reconectar.

## Apariencia

En Ajustes, elegir Sistema, Claro u Oscuro. Se guarda en el teléfono. Sistema sigue
el modo nocturno de Android. Tarjetas redondeadas y paleta neutra inspiradas en las
referencias; ilustración vectorial original de un sedán clásico. Tipografía del
sistema (sin dependencia de descargas de fuentes).

## Añadir celular o tablet

1. Desde un dispositivo autorizado conectado, ir a Ajustes → Añadir celular o tablet.
2. Esperar la confirmación del ESP32: aparece una ventana de 120 segundos.
3. Pulsar Ceder conexión a otro dispositivo (pausa la reconexión en esta sesión).
4. Emparejar el nuevo teléfono/tablet con el PIN y abrir LUX ONE para seleccionar el BCM.
5. La lista se guarda y la ventana se cierra tras una sola alta.

Si el segundo dispositivo ya estaba emparejado, abrir su conexión desde LUX ONE
dentro de la ventana. Los dispositivos nuevos se autorizan al detectar su bond;
los que ya estaban emparejados se autorizan al abrir SPP durante esa ventana.
Solo abrirla cuando se desea añadir un dispositivo de confianza con el PIN.

Todos los autorizados pueden abrir otra ventana. El móvil y la tablet quedan
registrados simultáneamente, pero BluetoothSerial admite una sola conexión de
control activa. Cerrar la app del primero o ceder su conexión para usar el otro.
No se ha cambiado a BLE/Wi-Fi ni a un servidor SPP multicliente.
No hay herramienta de recuperación por USB ni comando BORRAR_PROPIETARIO.
“Olvidar auto en esta app” solo elimina el registro local y no revoca su autorización.
Esta versión añade dispositivos; todavía no incluye revocación individual.

## Prueba en banco pendiente en hardware

- Emparejar el M31, conectar y verificar que llegan estados antes de habilitar controles.
- Cerrar/abrir app y reiniciar ESP32: ambos conservan el registro y reconectan.
- Cortar alimentación: en aproximadamente 5–7 segundos la app deshabilita controles;
  al volver la alimentación, reconecta sin tocar seguros ni claxon.
- Emparejar otro teléfono: no debe poder consultar estados ni ejecutar órdenes.
- Cambiar entre los tres temas, girar pantalla y reiniciar app.
- Negar permisos/apagar Bluetooth: no debe cerrarse la app; restaurarlos y volver.
- Autorizar una tablet durante la ventana; verificar que persiste al reiniciar.
- Dejar vencer la ventana y comprobar que no se autoriza un tercero.
- Ceder conexión entre los dos dispositivos autorizados, sin repetir emparejamiento.

El estado informado representa salidas ordenadas, no confirma corriente ni un foco
físicamente encendido: esa detección requeriría sensores.
