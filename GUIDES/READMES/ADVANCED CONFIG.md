# SYNTX · Update 0.2.0

## Install

Install `SYNTX-0.2.0-debug.apk` over the existing app. The `com.vw1980.bcm` identifier is kept, and it was built using the team's existing debug key. The launcher label remains LUX ONE; the header displays the SYNTX logo.
Also update BCM_Principal with all the .h files in its folder and BcmProtocol.h from the parent directory to enable LDR reading/configuration. Do not erase the entire flash. The rear node remains unchanged.

## Appearance and dashboard

Original WebP logo integrated without modifying the file. Dark background #060606, sampled from the image. Light and System themes remain available in Settings.
Edit, under My dashboard, opens the cards: name, action, width, visibility, and order via Move Up/Move Down. Up to 20 cards are allowed; actions include lights, locks, horn, sensor, and Autoshow. The rings keep the long-press for individual control. The cards are saved per phone. Restoring the initial dashboard does not modify the car. These are internal widgets, not Android desktop widgets.

## LDR

Settings → Advanced settings → LDR. Filtered ADC 0–4095 is shown, not lux.
Configure turn-on, turn-off, 500–10000 ms confirmation, and reading direction. There must be at least 50 points between thresholds. Assisted calibration:

1. Cover the sensor, wait for a stable reading, and capture Dark.
2. Illuminate it, wait for a stable reading, and capture Light.
3. Calculate sensitivity (samples separated by at least 200 points).
4. Review the thresholds and Save to ESP32; wait for confirmation.

The calibration proposes thresholds at 35% and 65% of the dark→light range and automatically detects the direction. It does not change outputs until it is saved. Both firmware and app use range validation and hysteresis; the firmware saves the entire block in NVS.
To test, enable Automatic mode and turn off the original controls. Original and manual commands maintain their priority.

## Unexpected crash

Device log could not be obtained: ADB failed in this environment when creating its user directory. The failure is not attributed to the phone, nor is a confirmed cause stated.
Bluetooth name accesses were protected against revoked permissions, adapter initialization, and preference reading. Change confirmations are serialized. Uncaught errors are logged locally with model/Android version and stack trace; they are not sent automatically. On a subsequent opening, go to Diagnostics and copy the log. The log only exists for crashes occurring after installing this version.

## Verified / pending

* `assembleDebug` finished: APK 0.2.0, versionCode 2, minSdk 26.
* Host C++ tests for timing and LDR validation: passed.
* `git diff --check`: no errors.
* Pending opening test on M31, on-screen appearance, and Bluetooth connection with the car.
* The full firmware was not compiled for the board in this environment; its portable timing and validation engines were tested. Compile it in Arduino IDE before uploading.
* Verify card and theme persistence on restart, light/dark capture, connection loss during saving, and original behavior of the lights.


# SYNTX · actualización 0.2.0

## Instalar

Instalar `SYNTX-0.2.0-debug.apk` sobre la app existente. Se mantiene el identificador
`com.vw1980.bcm` y se ha construido con la clave de depuración existente del equipo.
La etiqueta del launcher sigue siendo LUX ONE; la cabecera muestra el logo SYNTX.
Actualizar también BCM_Principal con todos los .h de su carpeta y BcmProtocol.h del
directorio padre para habilitar lectura/configuración LDR. No borrar toda la flash.
El nodo trasero no cambia.

## Apariencia y tablero

Logo WebP original integrado sin modificar el archivo. Fondo oscuro #060606,
muestreado de la imagen. Claro y Sistema siguen disponibles en Ajustes.
Editar, en Mi tablero, abre las tarjetas: nombre, acción, ancho, visibilidad y
orden mediante Subir/Bajar. Se permiten hasta 20 tarjetas; las acciones incluyen
luces, seguros, claxon, sensor y Autoshow. Los aros conservan pulsación larga para
control individual. Las tarjetas se guardan por teléfono. Restaurar tablero inicial
no modifica el auto. Son widgets internos, no del escritorio de Android.

## LDR

Ajustes → Ajustes avanzados → LDR. Se muestra ADC filtrado 0–4095, no lux.
Configurar encendido, apagado, confirmación de 500–10000 ms y sentido de lectura.
Debe haber al menos 50 puntos entre umbrales. Calibración asistida:

1. Cubrir el sensor, esperar lectura estable y capturar Oscuro.
2. Iluminarlo, esperar lectura estable y capturar Luz.
3. Calcular sensibilidad (muestras separadas al menos 200 puntos).
4. Revisar los umbrales y Guardar en ESP32; esperar confirmación.

La calibración propone umbrales al 35% y 65% del recorrido oscuro→luz y detecta
automáticamente el sentido. No cambia salidas hasta que se guarda. Firmware y app
usan validación de rango e histéresis; el firmware guarda el bloque completo en NVS.
Para probar, habilitar Modo automático y apagar los mandos originales. Las órdenes
originales y las manuales mantienen su prioridad.

## Cierre inesperado

No se obtuvo el log del dispositivo: ADB falló en este entorno al crear su directorio
de usuario. No se atribuye el fallo al teléfono ni se afirma una causa confirmada.
Se protegieron accesos a nombres Bluetooth ante permisos revocados, inicialización
del adaptador y lectura de preferencias. Las confirmaciones de cambios se serializan.
Los errores no capturados se registran localmente con modelo/Android y stack trace;
no se envían automáticamente. En una apertura posterior, ir a Diagnóstico y copiar
el registro. El registro solo existe para fallos posteriores a instalar esta versión.

## Verificado / pendiente

- `assembleDebug` finalizado: APK 0.2.0, versionCode 2, minSdk 26.
- Pruebas C++ host de temporización y validación LDR: aprobadas.
- `git diff --check`: sin errores.
- Falta prueba de apertura en M31, apariencia en pantalla y Bluetooth con el auto.
- El firmware completo no se compiló para la placa en este entorno; se probaron sus
  motores de tiempo y validación portables. Compilarlo en Arduino IDE antes de cargar.
- Comprobar persistencia de tarjetas y tema al reiniciar, captura luz/oscuridad,
  pérdida de conexión durante guardado y comportamiento original de las luces.
