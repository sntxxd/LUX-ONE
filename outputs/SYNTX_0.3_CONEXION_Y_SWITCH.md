# SYNTX 0.3 — switch original y Bluetooth en segundo plano

## Instalación

- Android: compilar/ejecutar `outputs/VWBCM_Android` en Android Studio o instalar `outputs/SYNTX-0.3.0-debug.apk`. Misma firma de depuración que la versión anterior; no hace falta desinstalar.
- ESP32 principal: cargar `outputs/BCM_ESP32/BCM_Principal/BCM_Principal.ino`, conservando **todos** sus archivos `.h`, incluido el nuevo `SwitchOff.h`. Conservar la configuración de placa/particiones y las claves/MAC propias. No hace falta actualizar el nodo trasero para este cambio.

## Switch

Con cualquier señal original de altas, bajas o cuartos activa, manda el carro. Si solo están los cuartos originales, una orden anterior de altas de la app ya no los acompaña.

Después de usar el switch, cuando las tres entradas quedan apagadas durante 150 ms, se cancelan las órdenes anteriores de altas/bajas/cuartos. Traseros y placa vuelven a seguir a los cuartos. No se modifican las órdenes de aros naranjas ni los mandos explícitos de aros blancos; los blancos que seguían automáticamente a los cuartos conservan su estado al quitar el switch. Durante el antirrebote se mantiene la última iluminación original, sin recuperar fugazmente órdenes viejas de la app.

El apagado prevalece sobre LDR/Autoshow hasta una nueva orden de app o liberación manual. Para volver a LDR, desactivar y activar Modo automático en la app. No se cambia su configuración guardada. Arrancar el ESP32 con el switch apagado no genera este apagado especial. Una ráfaga de altas también cuenta como uso de una entrada original; no existe una entrada separada para distinguirla del switch.

## Conexión

Un servicio Android `connectedDevice` mantiene un único socket, recibe estados y comprueba la conexión cada segundo. La actividad deja de ser propietaria de la conexión: bloquear pantalla o cambiar de app ya no la cierra. Al regresar reutiliza el estado del servicio sin la espera fija de dos segundos. Los estados reflejan órdenes de salida del ESP32, no medición eléctrica de focos fundidos.

- Notificación mientras el servicio está activo. En Android 13+ se solicita permiso de notificaciones.
- Si se pierde alimentación del ESP32 o no responde durante 5 s, se cierra el socket y se intenta conectar de nuevo al dispositivo guardado, sin volver a emparejar ni reenviar acciones anteriores.
- “Ceder conexión” y “Olvidar” detienen el servicio. No permite dos conexiones SPP simultáneas.
- Autoshow se detiene al pasar la app a segundo plano, aunque siga conectado Bluetooth.
- Mantener comprobaciones con pantalla apagada consume batería; el servicio utiliza un bloqueo de CPU con vencimiento y lo libera al desconectar/detenerse.
- Apagar/reiniciar el teléfono, forzar detención o que Android mate el proceso sí interrumpe el servicio. Abrir la app lo inicia nuevamente con el auto guardado. No se añadió arranque automático después de reiniciar.

Referencia Android: https://developer.android.com/develop/background-work/services/fgs/service-types#connected-device

## Verificación

Pruebas C++: arranque apagado, flanco ON/OFF, rebotes, un solo evento por ciclo, desbordamiento de millis, cancelación de mandos exteriores y conservación de mandos de aros. También ejecutar `show_engine_test.cpp` para regresión de Autoshow/pitidos/LDR.

Pendiente de prueba física (vehículo detenido):

1. Prender altas desde app; poner switch en cuartos, bajas y altas. Debe seguir cada posición original.
2. Regresar switch a apagado: altas/bajas/cuartos delanteros/traseros/placa apagados, sin recuperar la orden anterior. Verificar aros por separado.
3. Encender cuartos desde app otra vez: delanteros y traseros deben encenderse; apagar con el mismo botón.
4. Con oscuridad y automático activo, repetir el apagado: no debe reencender por LDR hasta liberar el mando.
5. Bloquear pantalla 2 minutos y volver; comprobar que el estado sigue disponible. Cortar/restaurar alimentación del ESP32 y comprobar reconexión sin accionar claxon/seguros.
6. Ceder conexión a tablet, volver al celular y reconectar; repetir después de rotar/cerrar/reabrir la actividad.

La compilación Android no equivale a prueba en el teléfono. La lógica C++ aislada tampoco sustituye compilar/cargar el firmware con Arduino IDE ni comprobar el arnés real.
