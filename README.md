# BCM ESP32 para VW Sedán 1980

Este paquete contiene un firmware inicial para dos NodeMCU ESP32S:

- `BCM_Principal/BCM_Principal.ino`: LDR, entradas de optoacoplador, faros, aros, claxon, actuadores y el frente.
- `BCM_Trasero/BCM_Trasero.ino`: cuartos traseros y lámpara de placa.
- `BcmProtocol.h`: protocolo binario compartido para ESP-NOW y Bluetooth.

## Prioridades implementadas

La prioridad se resuelve **por función**, no como un bloqueo global. Así una direccional real sigue el parpadeo aunque la app maneje las luces frontales.

1. La entrada original del automóvil manda sobre su función: altas/bajas/cuartos, direccional para el aro naranja y botón de claxon.
2. La orden manual de la app manda si no existe entrada original para ese canal.
3. El LDR actúa sólo si el automático está activo y no hay orden superior.

El LDR enciende **cuartos y bajas** al detectar oscuridad. No activa altas: no es seguro elegir altas sólo con un sensor de luminosidad. La confirmación de oscuridad/claro es de 5 segundos y hay histéresis para evitar parpadeos.

## Antes del primer encendido

1. Instala Arduino IDE con `esp32 by Espressif Systems` (2.x o 3.x) y selecciona `NodeMCU-32S`.
2. Abre `BCM_Principal/BCM_Principal.ino`. Conserva la estructura de carpetas: los pequeños archivos `BcmProtocol.h` de cada sketch enlazan al protocolo común.
3. En ambos sketches cambia **las tres claves**: `OTA_PASSWORD`, `ESPNOW_PMK` y `ESPNOW_LMK`; las últimas dos han de ser iguales entre nodos y tener 16 caracteres.
4. Graba temporalmente ambos nodos por USB y abre el monitor serie a 115200. Cada nodo imprime su MAC STA.
5. Copia la MAC trasera en `MAC_DEL_NODO_TRASERO` del principal y la MAC principal en `MAC_DEL_NODO_PRINCIPAL` del trasero. Vuelve a grabarlos. Con la MAC en ceros el firmware bloquea ESP-NOW a propósito.
6. En el nodo trasero sustituye los pines provisionales `GPIO25/GPIO26`; no fueron especificados y no deben asumirse como el cableado definitivo.
7. Prueba cada salida con fuente limitada, fusibles y una carga de banco antes de unirla al coche.

## OTA y configuración Wi-Fi

Al arrancar, cada nodo intenta usar las credenciales guardadas. Si no puede, crea una red Wi-Fi:

- Principal: `BCM-VW1980-Setup`
- Trasero: `BCM-VW1980-Rear-Setup`

La contraseña inicial de ambas es `cambia-esta-clave`; cámbiala en el código antes de poner el auto en servicio. Conéctate, abre `http://192.168.4.1`, autentícate con usuario `bcm` y la contraseña definida en `OTA_PASSWORD`, guarda el Wi-Fi de 2.4 GHz. Configura **ambos nodos en el mismo router**, porque ESP-NOW debe compartir canal. La misma página permite subir el `.bin` exportado por Arduino IDE para actualizar por OTA.

También queda habilitado ArduinoOTA para carga por red desde el IDE. Durante una actualización las salidas se fuerzan a apagado; nunca quites alimentación durante la carga.

## Protocolo único: Bluetooth SPP y ESP-NOW

El principal expone Bluetooth clásico SPP como `BCM-VW1980-Main`. Es adecuado para Android o un estéreo Android. iPhone/iOS no ofrece SPP genérico; para iOS la app futura debe ofrecer BLE GATT, enviando exactamente los mismos 10 bytes dentro de la característica BLE.

Cada trama tiene 10 bytes, en este orden:

| Byte(s) | Campo | Nota |
| --- | --- | --- |
| 0 | `0xA5` | inicio |
| 1 | `0x01` | versión |
| 2 | tipo | `0x01` comando, `0x81` estado, `0x82` ACK |
| 3 | secuencia | la asigna la app |
| 4 | comando | tabla siguiente |
| 5 | destino | tabla siguiente |
| 6–7 | valor | `int16`, little-endian |
| 8 | flags | reservado/estado de entradas |
| 9 | CRC-8 | polinomio `0x07` de bytes 0–8 |

| Comando | Hex | Valor / efecto |
| --- | --- | --- |
| `SET_AUTO` | `0x10` | `0` desactiva o `1` activa automático |
| `SET_MANUAL` | `0x11` | define el estado del destino |
| `RELEASE_MANUAL` | `0x12` | devuelve ese destino al LDR |
| `PULSE_HORN` | `0x13` | milisegundos, limitado a 1000 ms |
| `LOCK` / `UNLOCK` | `0x14` / `0x15` | pulso de 650 ms en actuador 1 / 2 |
| `SET_LDR_ON` / `OFF` | `0x16` / `0x17` | umbral ADC 0–4095 |
| `GET_STATE` | `0x18` | contesta estado actual |

Destinos: `1=faros` (`0` apagado, `1` bajas, `2` altas), `2=cuartos`, `3=aros blancos`, `4=aro naranja izq.`, `5=aro naranja der.`, `6=cuartos traseros`, `7=placa`. Para una salida binaria el valor es `0` o `1`. La app debe enviar `RELEASE_MANUAL` para volver un canal al automático; enviar `SET_MANUAL(...,0)` significa **forzar apagado**.

El estado recibido (`CMD_REPORT_STATE`) lleva una máscara de salidas en `value`: bit 0 bajas, 1 altas, 2 cuartos, 3/4 aros blancos, 5/6 aros naranjas, 7 claxon, 8/9 actuadores, 10 cuartos traseros, 11 placa, 12 automático activo, 13 oscuro. En `flags` vienen las entradas originales: altas, bajas, cuartos, direccional izquierda, derecha, claxon en bits 0–5.

## Límites y seguridad que se deben conservar

- El firmware **no toca** direccionales, hazards, freno ni reversa del vehículo; sólo lee direccionales para los aros.
- El *passthrough* implementado es lógico: una entrada original tiene prioridad y el ESP32 manda la salida. Si las luces originales deben seguir funcionando aun con el ESP32 sin alimentación o bloqueado, el arnés requiere además un bypass físico diseñado con relés/contactos adecuados; ningún sketch puede sustituir ese respaldo.
- Los optoacopladores deben aislar 12 V correctamente. Añade fusible independiente por cada rama y supresión de transientes automotrices (TVS, protección de polaridad, buck apto para load dump).
- Las bobinas de relé necesitan diodo flyback y los MOSFET deben tener gate pulldown.
- GPIO 2, 5, 12 y 15 son pines de arranque del ESP32. GPIO 12, en particular, puede afectar el voltaje de la flash. Comprueba que la etapa 2N2222 no eleve esos pines durante reset. Es preferible usar un pin no strapping o garantizar pull-down externo.
- El control por presencia de teléfono todavía **no está habilitado**. Una MAC Bluetooth no es una credencial fiable ni se anuncia de forma estable en teléfonos modernos. Para esa fase use autenticación BLE con claves rotativas o una acción explícita de la app, y nunca permita que un fallo de radio deje el vehículo inseguro.
- Esta base no sustituye las protecciones mecánicas, fusibles, ni la validación de un electricista automotriz. Las órdenes de claxon y actuadores ya llevan límites temporales para evitar activación sostenida por software.
