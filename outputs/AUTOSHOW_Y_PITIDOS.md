# Pitidos y Autoshow

Actualizar BCM_Principal y la app Android juntos. No es necesario cambiar el nodo
trasero. No borrar toda la flash durante una actualización normal: los patrones,
preferencias y autorizaciones se guardan en NVS.

## Pitidos al cerrar

En Ajustes: cantidad (1–5), pausa (50–2000 ms) y duración del aviso (50–1000 ms).
La duración también se usa al abrir y para el destello de los aros blancos.
El aviso al abrir sigue siendo de un pitido; el destello blanco es único.
La prueba reproduce el aviso configurado, sin activar actuadores. Si el aviso de
claxon está desactivado, la prueba tampoco pita. Una orden nueva reemplaza la
secuencia anterior. No se usa delay para producir los pitidos.

## Studio

16 columnas, 7 pistas: cuartos, bajas, altas, blanco izquierdo/derecho y naranja
izquierdo/derecho. Tocar celdas enciende/apaga en cada paso. Tocar el número de un
paso selecciona su duración, 200–5000 ms. Se puede aplicar un mismo tiempo a todos.
Guardar envía una copia de trabajo completa y espera ACK de cada comando. El ESP32
solo reemplaza el patrón al recibir los 16 tiempos y máscaras válidos y guardarlos
en NVS. Un envío cortado conserva el patrón anterior. Recargar descarta cambios
locales y vuelve a consultar el patrón guardado. Solo hay un patrón almacenado.

Activar Repetir para bucle; desactivarlo reproduce una vuelta. No se permite altas
y bajas simultáneas. Siempre se encienden cuartos con cualquier faro, incluso si la
celda de cuartos está apagada. Cuartos incluye delanteros, traseros y placa salvo
una orden de mayor prioridad. El envío trasero conserva el mecanismo ESP-NOW existente;
no es un reloj de sincronización de precisión para shows.

## Prioridades y reproducción

Mandos originales > órdenes manuales de app > automático LDR > Autoshow.
Un canal manual en OFF también prevalece sobre el patrón. El LDR activo conserva
el control de faros/cuartos/blancos incluso de día. Para liberar los canales hay
un botón explícito que libera las órdenes manuales y desactiva el automático;
ese cambio del modo automático se guarda y se debe revertir desde la pantalla
principal al terminar si se quiere recuperar el modo automático.

Antes de reproducir hay que guardar y confirmar auto estacionado. No existe
sensor de velocidad ni freno de estacionamiento: la confirmación es del usuario.
Autoshow nunca incluye actuadores, claxon, freno, reversa ni direccionales originales.
Solo controla los aros naranjas auxiliares. Cualquier entrada original activa
detiene el show (incluidos claxon/direccionales), y hay que iniciarlo de nuevo.
También se detiene al cerrar el editor, perder SPP o dejar de recibir comandos
durante 6 segundos. El reinicio conserva patrón pero siempre inicia detenido.
La app muestra el paso reportado cada aproximadamente 200 ms, con latencia Bluetooth.

## Validación

Pruebas host de motores: `outputs/BCM_ESP32/tests/show_engine_test.cpp` cubre
límites de pasos, repetición, fin de una vuelta, máscaras inválidas, límites de
duración, wrap de millis y cantidad/pausas/final de pitidos sin bloqueo.

Prueba de banco pendiente: tres pitidos de 100 ms separados 200 ms; cortar Bluetooth
en reproducción y a mitad de guardado; reiniciar sin arranque de show; comprobar
prioridad original/manual/automática por canal y que altas/bajas nunca coincidan.
Comprobar con cargas de prueba antes de usar faros reales y medir la temperatura
de los relés al usar patrones repetidos.
