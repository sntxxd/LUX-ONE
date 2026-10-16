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
