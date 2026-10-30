# ESP32 BCM for 1980 VW Beetle

Hiiii!!! im sntxxd(Santiago Alonso)

i made this project/prototype cuz i wanted to give my classic 1980 vw sedan some modern features, but i didnt want to deeply modify the car, so i decided to make it plug and play, this is only the repo of the prototype, cause i dont have the money to buy the specific car relays or make the pcb, so this is fully made in breadbord / 3d printing

this repo contains the firmware and app from the project, also contains the diagram, pinout and everything to build this project :D


## Before starting, u should know :
the bcm have some security protocols, and levels of priority, like...

1.. the physical vehicle input has top priority over its specific function (like high beams, low beams, parking lights.. etc...)

2.. if there are not input in the optocouplers, the app can send the signals via bt

3.. LDR: if there are not signal in the optocouplers, and if the option is enabled on the app, a LDR can turn on parking lights and low beams, only if there is dark for +5 secs

## WIRING
<img width="3752" height="1632" alt="diagrama github con resisDOS" src="https://github.com/user-attachments/assets/f44cffb2-e49c-4932-a901-058b76c21f7e" />

the female harnesses that connect low and high beams, need to be connected to gnd and the NO/OUT of the respective relay (IN THE DIAGRAM/PIN LIST), and the male harness need to be connected to the respective optocoupler (IN THE DIAGRAM/PIN LIST) !! DONT FORGET THE RESISTOR OR YOU WILL BURN EVERYTHING !!, in the BOM i put two male harnesses, but u only need to connect one to the optocouplers, you can use the other male harness to keep one of the original car cables isolated 

## PINS 

| Component | Function | Pin | Notes |
| --- | --- | --- | --- |
| **Light Sensor (LDR)** | Analog Light Sensor | GPIO 34 | ADC1 (Pure analog input, no Wi-Fi/OTA conflict). |
| **Optocoupler 1** | High Beams (12V Input) | GPIO 13 | Native `INPUT_PULLUP`. |
| **Optocoupler 2** | Low Beams (12V Input) | GPIO 14 | Native `INPUT_PULLUP`. |
| **Optocoupler 3** | Left Turn Signal (12V Input) | GPIO 16 | Native `INPUT_PULLUP`. |
| **Optocoupler 4** | Right Turn Signal (12V Input) | GPIO 17 | Native `INPUT_PULLUP`. |
| **Optocoupler 5** | Parking Lights Signal (Ex-Wiper) | GPIO 18 | Native `INPUT_PULLUP`. |
| **Optocoupler 6** | Horn (12V Input) | GPIO 19 | Native `INPUT_PULLUP` (Stays in its original location). |
| **Relay 1 (2N2222)** | High Beams | GPIO 12 | Digital Output (HIGH = On). |
| **Relay 2 (2N2222)** | Low Beams | GPIO 27 | Digital Output. |
| **Relay 3 (2N2222)** | Left White Halo | GPIO 5 | Digital Output. |
| **Relay 4 (2N2222)** | Right White Halo | GPIO 21 | Digital Output. |
| **Relay 5 (2N2222)** | Left Orange Halo | GPIO 23 | Digital Output. |
| **Relay 6 (2N2222)** | Right Orange Halo | GPIO 22 | Digital Output. |
| **Relay 7 (2N2222)** | Left Parking Lights  | GPIO 32 | Digital Output. |
| **Relay 8 (2N2222)** | Horn | GPIO 4 | Digital Output. |
| **Relay 9 (2N2222)** | Actuator 1 (NOT ENABLED YET) | GPIO 15 | Digital Output . |
| **Relay 10 (2N2222)** | Actuator 2 (NOT ENABLED YET)| GPIO 2 | Digital Output . |
| **Relay 11 (2N2222)** | Right Parking Lights | GPIO 33 | Digital Output. |

## Before uploading the firmware to the ESP32

1. you need to download esp32 by Espressif Systems in the boards manager, then select NodeMCU-32S
2. Open the files provided in FIRMWARE/CODE/BCM_ESP32/LUX ONE ESP32
3. change the passwords `OTA_PASSWORD`, `ESPNOW_PMK` and `ESPNOW_LMK` in the .ino named BCM_Principal.ino
4. once installed, try the device in a external power supply before connecting to the car 

## AUTH AND SECURITY

when u pair your phone for the first time, this device becomes the master admin, u need to grant authorization to another device via the app, cause if another person tries to pair and open the car, the lux one will deny the access

the detailed guide is in the readmes folder hehe

## COMPATIBLE DEVICES

devices like android phones and tablets are compatible with this projects, apple devices are not supported yet hehe

## SECURITY

this project doesn't manage turning signals, hazards, brake lights, or reverse lights, turn signals are only monitored just in case you want to sync it with the orange halos

12 v inputs must be opto isolated, and is highly recommended to add independent fuses for every input and output

every relay need the protection stage like flyback diodes

GPIO 2, 5, 12, and 15 affect ESP32 boot modes. Specifically, GPIO 12 controls flash voltage at boot. Ensure transistor driver stages (like 2N2222) do not pull these pins HIGH during reset. Using non-strapping pins or adding solid external pull-downs is strongly recommended.

