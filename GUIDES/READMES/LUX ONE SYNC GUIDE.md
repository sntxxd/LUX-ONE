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
