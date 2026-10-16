

|                         |                                      |               |                                                                 |
| ----------------------- | ------------------------------------ | ------------- | --------------------------------------------------------------- |
| **Sensor de Luz (LDR)** | Sensor de Luz Analógico              | **`GPIO 34`** | **ADC1** (Entrada analógica pura, sin conflicto con Wi-Fi/OTA). |
| **MOSFET 1**            | Faro Delantero Izquierdo (PWM)       | **`GPIO 25`** | Salida PWM para tierra (`ledcWrite`). Sin destellos al prender. |
| **MOSFET 2**            | Faro Delantero Derecho (PWM)         | **`GPIO 26`** | Salida PWM para tierra (`ledcWrite`). Sin destellos al prender. |
| **Optoacoplador 1**     | Luces Altas (Entrada 12V)            | **`GPIO 13`** | `INPUT_PULLUP` nativo.                                          |
| **Optoacoplador 2**     | Luces Bajas (Entrada 12V)            | **`GPIO 14`** | `INPUT_PULLUP` nativo.                                          |
| **Optoacoplador 3**     | Direccional Izquierda (Entrada 12V)  | **`GPIO 16`** | `INPUT_PULLUP` nativo.                                          |
| **Optoacoplador 4**     | Direccional Derecha (Entrada 12V)    | **`GPIO 17`** | `INPUT_PULLUP` nativo.                                          |
| **Optoacoplador 5**     | **Señal de Cuartos** _(Ex-Limpia)_   | **`GPIO 18`** | `INPUT_PULLUP` nativo.                                          |
| **Optoacoplador 6**     | Claxon (Entrada 12V)                 | **`GPIO 19`** | `INPUT_PULLUP` nativo **(Se queda en su lugar original)**.      |
| **Relé 1 (2N2222)**     | Luces Altas                          | **`GPIO 12`** | Salida Digital (`HIGH` = Encendido).                            |
| **Relé 2 (2N2222)**     | Luces Bajas                          | **`GPIO 27`** | Salida Digital.                                                 |
| **Relé 3 (2N2222)**     | Aro Blanco Izquierdo                 | **`GPIO 5`**  | Salida Digital.                                                 |
| **Relé 4 (2N2222)**     | Aro Blanco Derecho                   | **`GPIO 21`** | Salida Digital.                                                 |
| **Relé 5 (2N2222)**     | Aro Nar. Izq.                        | **`GPIO 23`** | Salida Digital.                                                 |
| **Relé 6 (2N2222)**     | Aro Nar. Der.                        | **`GPIO 22`** | Salida Digital.                                                 |
| **Relé 7 (2N2222)**     | **Cuartos Izquierdos** _(Ex-Limpia)_ | **`GPIO 32`** | Salida Digital.                                                 |
| **Relé 8 (2N2222)**     | Claxon                               | **`GPIO 4`**  | Salida Digital.                                                 |
| **Relé 9 (2N2222)**     | **Actuador 1**                       | **`GPIO 15`** | Salida Digital _(Lleva resistencia Pull-down de 10 kΩ a GND)_.  |
| **Relé 10 (2N2222)**    | **Actuador 2**                       | **`GPIO 2`**  | Salida Digital _(Lleva resistencia Pull-down de 10 kΩ a GND)_.  |
| **Relé 11 (2N2222)**    | **Cuartos Derechos** _(Nuevo)_       | **`GPIO 33`** | Salida Digital _(Lleva resistencia Pull-down de 10 kΩ a GND)_.  |





ENGLISH

|  |  |  |  |
| --- | --- | --- | --- |
| **Light Sensor (LDR)** | Analog Light Sensor | **`GPIO 34`** | **ADC1** (Pure analog input, no conflict with Wi-Fi/OTA). |
| **MOSFET 1** | Left Headlight (PWM) | **`GPIO 25`** | PWM ground output (`ledcWrite`). No flashes on startup. |
| **MOSFET 2** | Right Headlight (PWM) | **`GPIO 26`** | PWM ground output (`ledcWrite`). No flashes on startup. |
| **Optocoupler 1** | High Beams (12V Input) | **`GPIO 13`** | Native `INPUT_PULLUP`. |
| **Optocoupler 2** | Low Beams (12V Input) | **`GPIO 14`** | Native `INPUT_PULLUP`. |
| **Optocoupler 3** | Left Turn Signal (12V Input) | **`GPIO 16`** | Native `INPUT_PULLUP`. |
| **Optocoupler 4** | Right Turn Signal (12V Input) | **`GPIO 17`** | Native `INPUT_PULLUP`. |
| **Optocoupler 5** | **Parking Lights Signal** *(Ex-Wiper)* | **`GPIO 18`** | Native `INPUT_PULLUP`. |
| **Optocoupler 6** | Horn (12V Input) | **`GPIO 19`** | Native `INPUT_PULLUP` **(Stays in its original place)**. |
| **Relay 1 (2N2222)** | High Beams | **`GPIO 12`** | Digital Output (`HIGH` = On). |
| **Relay 2 (2N2222)** | Low Beams | **`GPIO 27`** | Digital Output. |
| **Relay 3 (2N2222)** | Left White Ring | **`GPIO 5`** | Digital Output. |
| **Relay 4 (2N2222)** | Right White Ring | **`GPIO 21`** | Digital Output. |
| **Relay 5 (2N2222)** | Left Amber Ring | **`GPIO 23`** | Digital Output. |
| **Relay 6 (2N2222)** | Right Amber Ring | **`GPIO 22`** | Digital Output. |
| **Relay 7 (2N2222)** | **Left Parking Lights** *(Ex-Wiper)* | **`GPIO 32`** | Digital Output. |
| **Relay 8 (2N2222)** | Horn | **`GPIO 4`** | Digital Output. |
| **Relay 9 (2N2222)** | **Actuator 1** | **`GPIO 15`** | Digital Output *(Requires 10 kΩ Pull-down resistor to GND)*. |
| **Relay 10 (2N2222)** | **Actuator 2** | **`GPIO 2`** | Digital Output *(Requires 10 kΩ Pull-down resistor to GND)*. |
| **Relay 11 (2N2222)** | **Right Parking Lights** *(New)* | **`GPIO 33`** | Digital Output *(Requires 10 kΩ Pull-down resistor to GND)*. |
