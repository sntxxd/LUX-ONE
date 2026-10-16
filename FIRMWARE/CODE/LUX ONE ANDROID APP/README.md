# VW BCM Android

Aplicación Android nativa para el firmware BCM ESP32. Usa Bluetooth clásico SPP, por lo que el teléfono debe ser Android y ya estar emparejado con `BCM-VW1980-Main` (PIN configurado en el firmware: `482916`).

## Estado actual

La interfaz y el protocolo binario están implementados: conexión, modo automático, luces, aros, bloqueo, apertura y claxon. Falta compilarla e instalarla porque esta computadora no tiene SDK de Android ni un teléfono ADB detectado.

## Prueba en teléfono

1. Conectar el teléfono con un cable de datos.
2. Activar Opciones de desarrollador y Depuración USB.
3. Aceptar la clave RSA de esta computadora en el teléfono.
4. Instalar Android SDK/JDK 17 y ejecutar `gradlew assembleDebug`.
5. Instalar `app/build/outputs/apk/debug/app-debug.apk` mediante ADB.

La app solicita el permiso Bluetooth de Android al abrirse. En Android primero se debe emparejar el ESP32 desde Ajustes Bluetooth; después se pulsa **Buscar** y **Conectar BCM** dentro de la app.

## Seguridad

Los botones sólo generan las órdenes ya limitadas por el firmware. No se incluyen freno, reversa, hazards ni direccionales del vehículo. Pruebe con cargas de banco antes de accionar relés/actuadores del automóvil.
