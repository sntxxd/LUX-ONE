/*
  BCM VW Sedán 1980 — Nodo principal
  Compatible con Arduino-ESP32 2.x y 3.x (probado para compilar con 3.3.x).
  Antes de instalar: configure MAC_DEL_NODO_TRASERO, claves ESP-NOW y claves OTA.
*/

#include <Arduino.h>
#include <WiFi.h>
#include <esp_now.h>
#include <BluetoothSerial.h>
#include <ArduinoOTA.h>
#include <WebServer.h>
#include <Update.h>
#include <Preferences.h>
#include <esp_arduino_version.h>
#include <atomic>
#include "BcmProtocol.h"
#include "ShowEngine.h"
#include "LdrSettings.h"

#if !defined(CONFIG_BT_ENABLED) || !defined(CONFIG_BLUEDROID_ENABLED)
#error Bluetooth clásico no está habilitado para esta placa.
#endif

// ============================ CONFIGURACIÓN ================================
constexpr char DEVICE_NAME[] = "BCM-VW1980-Main";
constexpr char BT_PIN[] = "482916";                       // Cámbielo antes de instalar.
constexpr char OTA_USER[] = "bcm";
constexpr char OTA_PASSWORD[] = "CAMBIA-ESTA-CLAVE";       // Cámbielo antes de instalar.
constexpr char SETUP_AP_SSID[] = "BCM-VW1980-Setup";
constexpr char SETUP_AP_PASSWORD[] = "cambia-esta-clave";  // 8+ caracteres.

// Copiar la MAC STA que imprime el nodo trasero. Con ceros se bloquea ESP-NOW.
const uint8_t MAC_DEL_NODO_TRASERO[6] = {0x00, 0x00, 0x00, 0x00, 0x00, 0x00};
constexpr char ESPNOW_PMK[] = "0123456789ABCDEF";  // Reemplazar: exactamente 16 caracteres.
constexpr char ESPNOW_LMK[] = "FEDCBA9876543210";  // Igual en ambos nodos.
constexpr bool ESPNOW_ENCRYPTED = true;

// Pines asignados para NodeMCU ESP32S.
constexpr int PIN_LDR = 34;
constexpr int PIN_MOSFET_LEFT = 25;
constexpr int PIN_MOSFET_RIGHT = 26;
constexpr int PIN_OPTO_HIGH = 13;
constexpr int PIN_OPTO_LOW = 14;
constexpr int PIN_OPTO_TURN_LEFT = 16;
constexpr int PIN_OPTO_TURN_RIGHT = 17;
constexpr int PIN_OPTO_PARKING = 18;
constexpr int PIN_OPTO_HORN = 19;
constexpr int PIN_RELAY_HIGH = 12;
constexpr int PIN_RELAY_LOW = 27;
constexpr int PIN_RELAY_WHITE_LEFT = 5;
constexpr int PIN_RELAY_WHITE_RIGHT = 21;
constexpr int PIN_RELAY_ORANGE_LEFT = 23;
constexpr int PIN_RELAY_ORANGE_RIGHT = 22;
constexpr int PIN_RELAY_PARKING_LEFT = 32;
constexpr int PIN_RELAY_HORN = 4;
constexpr int PIN_RELAY_ACTUATOR_LOCK = 15;
constexpr int PIN_RELAY_ACTUATOR_UNLOCK = 2;
constexpr int PIN_RELAY_PARKING_RIGHT = 33;

// La salida de los optos normalmente va a LOW al detectar 12 V.
constexpr uint8_t OPTO_ACTIVE_LEVEL = LOW;
// Con 2N2222 HIGH activa. Para módulos de relé active-low, usar LOW.
constexpr uint8_t RELAY_ON = HIGH;
constexpr uint8_t RELAY_OFF = (RELAY_ON == HIGH) ? LOW : HIGH;

constexpr uint32_t PWM_FREQ = 5000;
constexpr uint8_t PWM_BITS = 8;
constexpr uint8_t PWM_CH_LEFT = 0;   // Sólo Arduino-ESP32 2.x.
constexpr uint8_t PWM_CH_RIGHT = 1;
constexpr uint8_t HEADLIGHT_PWM = 255;

// Cambiar a false si el ADC sube en oscuridad con su divisor LDR.
bool LDR_DARK_IS_LOW = true;
constexpr uint16_t DEFAULT_LDR_ON = 1500;
constexpr uint16_t DEFAULT_LDR_OFF = 1900;
uint32_t LDR_CONFIRM_MS = 5000;
LdrSettings ldrDraft;
uint8_t ldrFields = 0;
uint32_t ldrEditAt = 0;
constexpr uint32_t MAX_HORN_APP_MS = 1000;
constexpr uint32_t ACTUATOR_PULSE_MS = 650;
constexpr uint32_t REAR_SYNC_MS = 1000;

enum HeadlightMode : uint8_t { HEADLIGHT_OFF = 0, HEADLIGHT_LOW = 1, HEADLIGHT_HIGH = 2 };

struct ManualState {
  int8_t headlight = -1;  // -1 automático, 0 off, 1 bajas, 2 altas.
  int8_t parking = -1;
  int8_t whiteRings = -1; // control en grupo
  int8_t whiteLeft = -1;  // control individual, tiene prioridad sobre el grupo
  int8_t whiteRight = -1;
  int8_t orangeRings = -1;
  int8_t orangeLeft = -1;
  int8_t orangeRight = -1;
  int8_t rearParking = -1;
  int8_t licenseLight = -1;
};

struct OutputState {
  HeadlightMode headlight = HEADLIGHT_OFF;
  bool parking = false;
  bool whiteLeft = false;
  bool whiteRight = false;
  bool orangeLeft = false;
  bool orangeRight = false;
  bool horn = false;
  bool actuatorLock = false;
  bool actuatorUnlock = false;
  bool rearParking = false;
  bool licenseLight = false;
};

// Ajustes persistentes de la retroalimentación al cerrar/abrir el vehículo.
struct FeedbackConfig {
  bool lockChirp = true;
  bool lockWhite = true;
  bool unlockChirp = false;
  bool unlockWhite = false;
  uint16_t durationMs = 220;
  uint8_t lockCount = 1;
  uint16_t lockGapMs = 200;
};

BluetoothSerial SerialBT;
WebServer web(80);
Preferences prefs;
QueueHandle_t espNowQueue = nullptr;
ManualState manual;
OutputState outputs;
FeedbackConfig feedback;
ChirpEngine chirps;
ShowPattern showPattern, showDraft;
ShowEngine autoshow;
bool showEditing = false;
uint16_t showMasksReceived = 0, showTimesReceived = 0;
uint32_t showEditAt = 0, lastShowHeartbeat = 0;

uint8_t nextSequence = 1;
uint8_t btBuffer[sizeof(BcmPacket)];
size_t btLength = 0;
bool autoEnabled = true;
uint16_t ldrOnThreshold = DEFAULT_LDR_ON;
uint16_t ldrOffThreshold = DEFAULT_LDR_OFF;
float ldrFiltered = 0.0f;
bool autoDark = false;
bool ldrCandidateDark = false;
uint32_t ldrCandidateSince = 0;
uint32_t hornUntil = 0;
uint32_t whiteFeedbackUntil = 0;
uint32_t lockUntil = 0;
uint32_t unlockUntil = 0;
uint32_t restartAt = 0;
uint32_t lastRearSyncAt = 0;
uint16_t lastOutputMask = 0xFFFF;

// Dispositivos autorizados persistentes. NVS se escribe únicamente desde loop().
constexpr uint8_t MAX_AUTHORIZED = 8;
uint8_t authorizedAddresses[MAX_AUTHORIZED][6] = {};
uint8_t authorizedCount = 0;
uint32_t enrollmentUntil = 0;
esp_bd_addr_t enrollmentBaseline[32];
int baselineCount = 0;
std::atomic<bool> ownerSession(false);
QueueHandle_t btOwnerQueue = nullptr;
struct OwnerEvent { bool opened; uint32_t handle; uint8_t address[6]; };
uint32_t ownerHandle = 0;

void onOwnerSppEvent(esp_spp_cb_event_t event, esp_spp_cb_param_t *param) {
  OwnerEvent item = {};
  if (event == ESP_SPP_SRV_OPEN_EVT) {
    ownerSession.store(false);
    item.opened = true;
    item.handle = param->srv_open.handle;
    memcpy(item.address, param->srv_open.rem_bda, 6);
  } else if (event == ESP_SPP_CLOSE_EVT) {
    item.handle = param->close.handle;
    ownerSession.store(false);
  } else return;
  if (btOwnerQueue == nullptr || xQueueSend(btOwnerQueue, &item, 0) != pdTRUE) {
    ownerSession.store(false);
    if (item.opened) esp_spp_disconnect(item.handle);
  }
}

bool isAuthorized(const uint8_t *address) {
  for (uint8_t i = 0; i < authorizedCount; ++i)
    if (memcmp(authorizedAddresses[i], address, 6) == 0) return true;
  return false;
}

bool enrollmentOpen() {
  return enrollmentUntil != 0 && static_cast<int32_t>(enrollmentUntil - millis()) > 0;
}

bool addAuthorized(const uint8_t *address) {
  if (isAuthorized(address)) return true;
  if (authorizedCount >= MAX_AUTHORIZED) return false;
  memcpy(authorizedAddresses[authorizedCount], address, 6);
  const size_t length = (authorizedCount + 1) * 6;
  if (prefs.putBytes("btAllowed", authorizedAddresses, length) != length) return false;
  ++authorizedCount;
  enrollmentUntil = 0; // una sola alta por ventana
  Serial.println("LUX ONE: dispositivo autorizado guardado");
  return true;
}

bool beginEnrollment() {
  if (authorizedCount >= MAX_AUTHORIZED) return false;
  baselineCount = esp_bt_gap_get_bond_device_num();
  if (baselineCount < 0 || baselineCount > 32) return false;
  if (baselineCount > 0 && esp_bt_gap_get_bond_device_list(&baselineCount, enrollmentBaseline) != ESP_OK) return false;
  enrollmentUntil = millis() + 120000;
  return true;
}

bool eligibleForEnrollment(const uint8_t *address) {
  if (!enrollmentOpen()) return false;
  for (int i = 0; i < baselineCount; ++i)
    if (memcmp(enrollmentBaseline[i], address, 6) == 0) return false;
  return true;
}

void handleOwner() {
  static uint32_t lastCheck = 0;
  if ((authorizedCount == 0 || enrollmentOpen()) && millis() - lastCheck >= 500) {
    lastCheck = millis();
    int count = esp_bt_gap_get_bond_device_num();
    if (count > 0 && count <= 32) {
      esp_bd_addr_t bonds[32];
      if (esp_bt_gap_get_bond_device_list(&count, bonds) == ESP_OK) {
        for (int i = 0; i < count; ++i) {
          if ((authorizedCount == 0 && count == 1) || eligibleForEnrollment(bonds[i])) {
            addAuthorized(bonds[i]);
            break;
          }
        }
      }
    }
  }
  OwnerEvent item;
  while (btOwnerQueue != nullptr && xQueueReceive(btOwnerQueue, &item, 0) == pdTRUE) {
    if (!item.opened) {
      if (ownerHandle == item.handle) { ownerSession.store(false); ownerHandle = 0; btLength = 0; }
      continue;
    }
    // Exigir vínculo Bluetooth autenticado además de la lista de autorizados.
    int count = esp_bt_gap_get_bond_device_num();
    bool bonded = false;
    if (count > 0 && count <= 32) {
      esp_bd_addr_t bonds[32];
      if (esp_bt_gap_get_bond_device_list(&count, bonds) == ESP_OK) {
        for (int i = 0; i < count; ++i) if (memcmp(bonds[i], item.address, 6) == 0) bonded = true;
      }
    }
    if (bonded && !isAuthorized(item.address) &&
        (authorizedCount == 0 || enrollmentOpen())) addAuthorized(item.address);
    if (!bonded || !isAuthorized(item.address)) {
      esp_spp_disconnect(item.handle);
      Serial.println("LUX ONE: teléfono no autorizado");
      continue;
    }
    ownerHandle = item.handle;
    btLength = 0;
    while (SerialBT.available()) SerialBT.read();
    ownerSession.store(true);
  }

}

bool macConfigured(const uint8_t mac[6]) {
  for (uint8_t i = 0; i < 6; ++i) if (mac[i] != 0) return true;
  return false;
}

bool optoActive(int pin) { return digitalRead(pin) == OPTO_ACTIVE_LEVEL; }
void relayWrite(int pin, bool state) { digitalWrite(pin, state ? RELAY_ON : RELAY_OFF); }

void setHeadlightPwm(uint8_t duty) {
#if ESP_ARDUINO_VERSION_MAJOR >= 3
  ledcWrite(PIN_MOSFET_LEFT, duty);
  ledcWrite(PIN_MOSFET_RIGHT, duty);
#else
  ledcWrite(PWM_CH_LEFT, duty);
  ledcWrite(PWM_CH_RIGHT, duty);
#endif
}

void safeOutputsOff() {
  const int pins[] = {
    PIN_RELAY_HIGH, PIN_RELAY_LOW, PIN_RELAY_WHITE_LEFT, PIN_RELAY_WHITE_RIGHT,
    PIN_RELAY_ORANGE_LEFT, PIN_RELAY_ORANGE_RIGHT, PIN_RELAY_PARKING_LEFT,
    PIN_RELAY_HORN, PIN_RELAY_ACTUATOR_LOCK, PIN_RELAY_ACTUATOR_UNLOCK,
    PIN_RELAY_PARKING_RIGHT
  };
  for (int pin : pins) {
    pinMode(pin, OUTPUT);
    relayWrite(pin, false);
  }
  setHeadlightPwm(0);
}

void configurePins() {
  pinMode(PIN_OPTO_HIGH, INPUT_PULLUP);
  pinMode(PIN_OPTO_LOW, INPUT_PULLUP);
  pinMode(PIN_OPTO_TURN_LEFT, INPUT_PULLUP);
  pinMode(PIN_OPTO_TURN_RIGHT, INPUT_PULLUP);
  pinMode(PIN_OPTO_PARKING, INPUT_PULLUP);
  pinMode(PIN_OPTO_HORN, INPUT_PULLUP);
  analogReadResolution(12);
#if ESP_ARDUINO_VERSION_MAJOR >= 3
  ledcAttach(PIN_MOSFET_LEFT, PWM_FREQ, PWM_BITS);
  ledcAttach(PIN_MOSFET_RIGHT, PWM_FREQ, PWM_BITS);
#else
  ledcSetup(PWM_CH_LEFT, PWM_FREQ, PWM_BITS);
  ledcSetup(PWM_CH_RIGHT, PWM_FREQ, PWM_BITS);
  ledcAttachPin(PIN_MOSFET_LEFT, PWM_CH_LEFT);
  ledcAttachPin(PIN_MOSFET_RIGHT, PWM_CH_RIGHT);
#endif
  safeOutputsOff();
}

void saveLightConfig() {
  prefs.putBool("auto", autoEnabled);
  prefs.putUShort("ldrOn", ldrOnThreshold);
  prefs.putUShort("ldrOff", ldrOffThreshold);
  LdrSettings settings;
  settings.on = ldrOnThreshold; settings.off = ldrOffThreshold;
  settings.confirmMs = LDR_CONFIRM_MS; settings.darkIsLow = LDR_DARK_IS_LOW;
  prefs.putBytes("ldrV1", &settings, sizeof(settings));
}

void loadLightConfig() {
  autoEnabled = prefs.getBool("auto", true);
  ldrOnThreshold = prefs.getUShort("ldrOn", DEFAULT_LDR_ON);
  ldrOffThreshold = prefs.getUShort("ldrOff", DEFAULT_LDR_OFF);
  LdrSettings saved;
  if (prefs.getBytesLength("ldrV1") == sizeof(saved) &&
      prefs.getBytes("ldrV1", &saved, sizeof(saved)) == sizeof(saved) && saved.valid()) {
    ldrOnThreshold = saved.on; ldrOffThreshold = saved.off;
    LDR_CONFIRM_MS = saved.confirmMs; LDR_DARK_IS_LOW = saved.darkIsLow;
  }
  const bool invalid = ldrOnThreshold >= 4096 || ldrOffThreshold >= 4096 ||
    (LDR_DARK_IS_LOW && ldrOnThreshold >= ldrOffThreshold) ||
    (!LDR_DARK_IS_LOW && ldrOnThreshold <= ldrOffThreshold);
  if (invalid) {
    ldrOnThreshold = DEFAULT_LDR_ON;
    ldrOffThreshold = DEFAULT_LDR_OFF;
    saveLightConfig();
  }
}

void saveFeedbackConfig() {
  prefs.putBool("lockChirp", feedback.lockChirp);
  prefs.putBool("lockWhite", feedback.lockWhite);
  prefs.putBool("unlockChirp", feedback.unlockChirp);
  prefs.putBool("unlockWhite", feedback.unlockWhite);
  prefs.putUShort("feedMs", feedback.durationMs);
  prefs.putUChar("lockCount", feedback.lockCount);
  prefs.putUShort("lockGap", feedback.lockGapMs);
}

void loadFeedbackConfig() {
  feedback.lockChirp = prefs.getBool("lockChirp", true);
  feedback.lockWhite = prefs.getBool("lockWhite", true);
  feedback.unlockChirp = prefs.getBool("unlockChirp", false);
  feedback.unlockWhite = prefs.getBool("unlockWhite", false);
  feedback.durationMs = prefs.getUShort("feedMs", 220);
  feedback.lockCount = prefs.getUChar("lockCount", 1);
  feedback.lockGapMs = prefs.getUShort("lockGap", 200);
  if (feedback.lockCount < 1 || feedback.lockCount > 5) feedback.lockCount = 1;
  if (feedback.lockGapMs < 50 || feedback.lockGapMs > 2000) feedback.lockGapMs = 200;
  if (feedback.durationMs < 50 || feedback.durationMs > MAX_HORN_APP_MS) {
    feedback.durationMs = 220;
    saveFeedbackConfig();
  }
}

bool setFeedbackConfig(uint8_t target, int16_t value) {
  if (target == TARGET_CFG_LOCK_COUNT) {
    if (value < 1 || value > 5) return false;
    feedback.lockCount = value; return true;
  }
  if (target == TARGET_CFG_LOCK_GAP) {
    if (value < 50 || value > 2000) return false;
    feedback.lockGapMs = value; return true;
  }
  if (target == TARGET_CFG_FEEDBACK_MS) {
    if (value < 50 || value > MAX_HORN_APP_MS) return false;
    feedback.durationMs = static_cast<uint16_t>(value);
    return true;
  }
  if (value != 0 && value != 1) return false;
  switch (target) {
    case TARGET_CFG_LOCK_CHIRP: feedback.lockChirp = value; return true;
    case TARGET_CFG_LOCK_WHITE: feedback.lockWhite = value; return true;
    case TARGET_CFG_UNLOCK_CHIRP: feedback.unlockChirp = value; return true;
    case TARGET_CFG_UNLOCK_WHITE: feedback.unlockWhite = value; return true;
    default: return false;
  }
}

void triggerVehicleFeedback(bool locking) {
  const bool chirp = locking ? feedback.lockChirp : feedback.unlockChirp;
  const bool white = locking ? feedback.lockWhite : feedback.unlockWhite;
  chirps.count = 0;
  if (chirp) chirps.start(millis(), locking ? feedback.lockCount : 1, feedback.durationMs, feedback.lockGapMs);
  if (white) whiteFeedbackUntil = millis() + feedback.durationMs;
}

void updateLdr() {
  const uint16_t sample = analogRead(PIN_LDR);
  if (ldrFiltered == 0.0f) ldrFiltered = sample;
  ldrFiltered = ldrFiltered * 0.90f + sample * 0.10f;

  bool desiredDark = autoDark;
  if (LDR_DARK_IS_LOW) {
    if (!autoDark && ldrFiltered <= ldrOnThreshold) desiredDark = true;
    if (autoDark && ldrFiltered >= ldrOffThreshold) desiredDark = false;
  } else {
    if (!autoDark && ldrFiltered >= ldrOnThreshold) desiredDark = true;
    if (autoDark && ldrFiltered <= ldrOffThreshold) desiredDark = false;
  }
  if (desiredDark != ldrCandidateDark) {
    ldrCandidateDark = desiredDark;
    ldrCandidateSince = millis();
  }
  if (ldrCandidateDark != autoDark && millis() - ldrCandidateSince >= LDR_CONFIRM_MS) {
    autoDark = ldrCandidateDark;
    Serial.printf("LDR: %s (%.0f)\n", autoDark ? "oscuro" : "claro", ldrFiltered);
  }
}

bool setManualTarget(uint8_t target, int16_t value, bool release) {
  int8_t *selected = nullptr;
  switch (target) {
    case TARGET_HEADLIGHT: selected = &manual.headlight; break;
    case TARGET_PARKING: selected = &manual.parking; break;
    case TARGET_WHITE_RINGS: selected = &manual.whiteRings; break;
    case TARGET_WHITE_LEFT: selected = &manual.whiteLeft; break;
    case TARGET_WHITE_RIGHT: selected = &manual.whiteRight; break;
    case TARGET_ORANGE_RINGS: selected = &manual.orangeRings; break;
    case TARGET_ORANGE_LEFT: selected = &manual.orangeLeft; break;
    case TARGET_ORANGE_RIGHT: selected = &manual.orangeRight; break;
    case TARGET_REAR_PARKING: selected = &manual.rearParking; break;
    case TARGET_LICENSE_LIGHT: selected = &manual.licenseLight; break;
    default: return false;
  }
  if (release) {
    *selected = -1;
    return true;
  }
  if (target == TARGET_HEADLIGHT && value >= HEADLIGHT_OFF && value <= HEADLIGHT_HIGH) {
    *selected = static_cast<int8_t>(value);
    return true;
  }
  if (target != TARGET_HEADLIGHT && (value == 0 || value == 1)) {
    *selected = static_cast<int8_t>(value);
    return true;
  }
  return false;
}

// Prioridad por función: original > app > LDR > Autoshow. Una direccional original no
// bloquea la app en faros, pero sí toma inmediatamente su aro naranja.
void calculateOutputs() {
  const bool originalHigh = optoActive(PIN_OPTO_HIGH);
  const bool originalLow = optoActive(PIN_OPTO_LOW);
  const bool originalParking = optoActive(PIN_OPTO_PARKING);
  const bool originalTurnLeft = optoActive(PIN_OPTO_TURN_LEFT);
  const bool originalTurnRight = optoActive(PIN_OPTO_TURN_RIGHT);
  const bool originalHorn = optoActive(PIN_OPTO_HORN);
  const bool originalHeadlight = originalHigh || originalLow;
  const bool originalExterior = originalHeadlight || originalParking;
  // Sin sensor de velocidad: el usuario confirma auto estacionado. Cualquier
  // mando original detiene el show hasta que el usuario vuelva a iniciarlo.
  if (originalExterior || originalTurnLeft || originalTurnRight || originalHorn ||
      !ownerSession.load() || millis() - lastShowHeartbeat > 6000) autoshow.stop();
  const uint16_t showMask = autoshow.tick(millis(), showPattern);

  if (originalHigh) outputs.headlight = HEADLIGHT_HIGH;
  else if (originalLow) outputs.headlight = HEADLIGHT_LOW;
  else if (manual.headlight >= 0) outputs.headlight = static_cast<HeadlightMode>(manual.headlight);
  else if (autoEnabled) outputs.headlight = autoDark ? HEADLIGHT_LOW : HEADLIGHT_OFF;
  else outputs.headlight = (showMask & 2) ? HEADLIGHT_HIGH : ((showMask & 1) ? HEADLIGHT_LOW : HEADLIGHT_OFF);

  // Los cuartos son obligatorios con bajas o altas. Esto también anula una
  // orden manual anterior de apagarlos mientras exista un faro encendido.
  if (originalExterior || outputs.headlight != HEADLIGHT_OFF) outputs.parking = true;
  else if (manual.parking >= 0) outputs.parking = manual.parking;
  else outputs.parking = autoEnabled ? autoDark : (showMask & 4) != 0;

  if (originalExterior) outputs.whiteLeft = outputs.whiteRight = true;
  else {
    const bool higherWhite = manual.whiteRings >= 0 || manual.parking >= 0 || manual.headlight >= 0 || autoEnabled;
    const bool groupWhite = manual.whiteRings >= 0 ? manual.whiteRings : outputs.parking;
    outputs.whiteLeft = manual.whiteLeft >= 0 ? manual.whiteLeft :
      (higherWhite || !autoshow.running ? groupWhite : (showMask & 8) != 0);
    outputs.whiteRight = manual.whiteRight >= 0 ? manual.whiteRight :
      (higherWhite || !autoshow.running ? groupWhite : (showMask & 16) != 0);
  }
  if (millis() < whiteFeedbackUntil) outputs.whiteLeft = outputs.whiteRight = true;

  const bool groupOrange = manual.orangeRings >= 0 ? manual.orangeRings : false;
  outputs.orangeLeft = originalTurnLeft ? true : (manual.orangeLeft >= 0 ? manual.orangeLeft :
    (manual.orangeRings >= 0 ? groupOrange : (showMask & 32) != 0));
  outputs.orangeRight = originalTurnRight ? true : (manual.orangeRight >= 0 ? manual.orangeRight :
    (manual.orangeRings >= 0 ? groupOrange : (showMask & 64) != 0));
  const bool chirpOn = chirps.tick(millis());
  outputs.horn = originalHorn || millis() < hornUntil || chirpOn;
  outputs.actuatorLock = millis() < lockUntil;
  outputs.actuatorUnlock = millis() < unlockUntil;

  if (originalExterior || outputs.headlight != HEADLIGHT_OFF) outputs.rearParking = true;
  else if (manual.rearParking >= 0) outputs.rearParking = manual.rearParking;
  else outputs.rearParking = outputs.parking;

  if (originalExterior || outputs.headlight != HEADLIGHT_OFF) outputs.licenseLight = true;
  else if (manual.licenseLight >= 0) outputs.licenseLight = manual.licenseLight;
  else outputs.licenseLight = outputs.parking;
}

void writeOutputs() {
  relayWrite(PIN_RELAY_HIGH, outputs.headlight == HEADLIGHT_HIGH);
  relayWrite(PIN_RELAY_LOW, outputs.headlight == HEADLIGHT_LOW);
  setHeadlightPwm(outputs.headlight == HEADLIGHT_OFF ? 0 : HEADLIGHT_PWM);
  relayWrite(PIN_RELAY_WHITE_LEFT, outputs.whiteLeft);
  relayWrite(PIN_RELAY_WHITE_RIGHT, outputs.whiteRight);
  relayWrite(PIN_RELAY_ORANGE_LEFT, outputs.orangeLeft);
  relayWrite(PIN_RELAY_ORANGE_RIGHT, outputs.orangeRight);
  relayWrite(PIN_RELAY_PARKING_LEFT, outputs.parking);
  relayWrite(PIN_RELAY_PARKING_RIGHT, outputs.parking);
  relayWrite(PIN_RELAY_HORN, outputs.horn);
  relayWrite(PIN_RELAY_ACTUATOR_LOCK, outputs.actuatorLock);
  relayWrite(PIN_RELAY_ACTUATOR_UNLOCK, outputs.actuatorUnlock);
}

uint16_t outputMask() {
  uint16_t mask = 0;
  if (outputs.headlight == HEADLIGHT_LOW) mask |= 1u << 0;
  if (outputs.headlight == HEADLIGHT_HIGH) mask |= 1u << 1;
  if (outputs.parking) mask |= 1u << 2;
  if (outputs.whiteLeft) mask |= 1u << 3;
  if (outputs.whiteRight) mask |= 1u << 4;
  if (outputs.orangeLeft) mask |= 1u << 5;
  if (outputs.orangeRight) mask |= 1u << 6;
  if (outputs.horn) mask |= 1u << 7;
  if (outputs.actuatorLock) mask |= 1u << 8;
  if (outputs.actuatorUnlock) mask |= 1u << 9;
  if (outputs.rearParking) mask |= 1u << 10;
  if (outputs.licenseLight) mask |= 1u << 11;
  if (autoEnabled) mask |= 1u << 12;
  if (autoDark) mask |= 1u << 13;
  return mask;
}

void sendBluetoothPacket(uint8_t kind, uint8_t sequence, uint8_t command, uint8_t target,
                         int16_t value, uint8_t flags = 0) {
  BcmPacket packet = {};
  packet.kind = kind;
  packet.sequence = sequence;
  packet.command = command;
  packet.target = target;
  packet.value = value;
  packet.flags = flags;
  bcmFinalize(packet);
  SerialBT.write(reinterpret_cast<const uint8_t *>(&packet), sizeof(packet));
}

void reportState(uint8_t sequence) {
  uint8_t inputs = 0;
  if (optoActive(PIN_OPTO_HIGH)) inputs |= 1u << 0;
  if (optoActive(PIN_OPTO_LOW)) inputs |= 1u << 1;
  if (optoActive(PIN_OPTO_PARKING)) inputs |= 1u << 2;
  if (optoActive(PIN_OPTO_TURN_LEFT)) inputs |= 1u << 3;
  if (optoActive(PIN_OPTO_TURN_RIGHT)) inputs |= 1u << 4;
  if (optoActive(PIN_OPTO_HORN)) inputs |= 1u << 5;
  sendBluetoothPacket(PKT_STATE, sequence, CMD_REPORT_STATE, TARGET_SYSTEM,
                      static_cast<int16_t>(outputMask()), inputs);
}

void reportFeedbackConfig(uint8_t sequence) {
  uint16_t flags = 0;
  if (feedback.lockChirp) flags |= 1u << 0;
  if (feedback.lockWhite) flags |= 1u << 1;
  if (feedback.unlockChirp) flags |= 1u << 2;
  if (feedback.unlockWhite) flags |= 1u << 3;
  // flags va en value; flags del paquete codifica duración en pasos de 10 ms.
  sendBluetoothPacket(PKT_STATE, sequence, CMD_REPORT_CONFIG, TARGET_SYSTEM,
    static_cast<int16_t>(flags), static_cast<uint8_t>(feedback.durationMs / 10));
  sendBluetoothPacket(PKT_STATE, sequence, CMD_REPORT_CHIRP, feedback.lockCount, feedback.lockGapMs);
}

void reportShow(uint8_t sequence, bool pattern) {
  sendBluetoothPacket(PKT_STATE, sequence, CMD_REPORT_SHOW, autoshow.step, autoshow.running, showPattern.repeat);
  if (!pattern) return;
  for (uint8_t i = 0; i < 16; ++i) {
    sendBluetoothPacket(PKT_STATE, sequence, CMD_REPORT_SHOW_MASK, i, showPattern.masks[i]);
    sendBluetoothPacket(PKT_STATE, sequence, CMD_REPORT_SHOW_TIME, i, showPattern.durations[i]);
  }
}

void reportLdr(uint8_t sequence, bool config) {
  sendBluetoothPacket(PKT_STATE, sequence, CMD_REPORT_LDR, 0, static_cast<int16_t>(ldrFiltered), autoDark);
  if (!config) return;
  sendBluetoothPacket(PKT_STATE, sequence, CMD_REPORT_LDR_CONFIG, 0, ldrOnThreshold);
  sendBluetoothPacket(PKT_STATE, sequence, CMD_REPORT_LDR_CONFIG, 1, ldrOffThreshold);
  sendBluetoothPacket(PKT_STATE, sequence, CMD_REPORT_LDR_CONFIG, 2, LDR_CONFIRM_MS);
  sendBluetoothPacket(PKT_STATE, sequence, CMD_REPORT_LDR_CONFIG, 3, LDR_DARK_IS_LOW);
}

void sendAck(uint8_t sequence, uint8_t command, uint8_t result) {
  sendBluetoothPacket(PKT_ACK, sequence, CMD_REPORT_ACK, command, result);
}

void reportDevices(uint8_t sequence) {
  uint8_t remaining = enrollmentOpen() ? (enrollmentUntil - millis() + 999) / 1000 : 0;
  sendBluetoothPacket(PKT_STATE, sequence, CMD_REPORT_DEVICES, TARGET_SYSTEM, authorizedCount, remaining);
}

bool addRearPeer() {
  if (!macConfigured(MAC_DEL_NODO_TRASERO)) {
    Serial.println("ESP-NOW deshabilitado: configure MAC_DEL_NODO_TRASERO.");
    return false;
  }
  if (esp_now_is_peer_exist(MAC_DEL_NODO_TRASERO)) return true;
  esp_now_peer_info_t peer = {};
  memcpy(peer.peer_addr, MAC_DEL_NODO_TRASERO, 6);
  peer.channel = 0;   // El canal actual de Wi-Fi.
  peer.ifidx = WIFI_IF_STA;
  peer.encrypt = ESPNOW_ENCRYPTED;
  if (ESPNOW_ENCRYPTED) memcpy(peer.lmk, ESPNOW_LMK, 16);
  const esp_err_t err = esp_now_add_peer(&peer);
  if (err != ESP_OK) Serial.printf("No se agregó peer trasero: %d\n", err);
  return err == ESP_OK;
}

void sendRearState() {
  if (!macConfigured(MAC_DEL_NODO_TRASERO)) return;
  BcmPacket packet = {};
  packet.kind = PKT_COMMAND;
  packet.sequence = nextSequence++;
  packet.command = CMD_SYNC_REAR;
  packet.target = TARGET_REAR_STATE;
  packet.value = (outputs.rearParking ? 0x01 : 0) | (outputs.licenseLight ? 0x02 : 0);
  bcmFinalize(packet);
  const esp_err_t err = esp_now_send(MAC_DEL_NODO_TRASERO,
    reinterpret_cast<const uint8_t *>(&packet), sizeof(packet));
  if (err != ESP_OK) Serial.printf("ESP-NOW send: %d\n", err);
}

void processCommand(const BcmPacket &packet, bool fromBluetooth) {
  if (packet.kind != PKT_COMMAND) return;
  if (fromBluetooth) lastShowHeartbeat = millis();
  uint8_t result = 0;
  switch (packet.command) {
    case CMD_LDR_STAGE:
      if (packet.target > 3 || packet.value < 0) { result = 1; break; }
      if (millis() - ldrEditAt > 10000) ldrFields = 0;
      if (packet.target == 0) { ldrFields = 0; ldrDraft = LdrSettings(); ldrDraft.on = packet.value; }
      if (packet.target == 1) ldrDraft.off = packet.value;
      if (packet.target == 2) ldrDraft.confirmMs = packet.value;
      if (packet.target == 3) { if (packet.value > 1) { result = 1; break; } ldrDraft.darkIsLow = packet.value; }
      ldrFields |= 1u << packet.target; ldrEditAt = millis();
      break;
    case CMD_LDR_SAVE:
      if (ldrFields != 15 || millis() - ldrEditAt > 10000 || !ldrDraft.valid() ||
          prefs.putBytes("ldrV1", &ldrDraft, sizeof(ldrDraft)) != sizeof(ldrDraft)) { result = 1; break; }
      ldrOnThreshold = ldrDraft.on; ldrOffThreshold = ldrDraft.off;
      LDR_CONFIRM_MS = ldrDraft.confirmMs; LDR_DARK_IS_LOW = ldrDraft.darkIsLow;
      ldrCandidateSince = millis(); ldrFields = 0;
      if (fromBluetooth) reportLdr(packet.sequence, true);
      break;
    case CMD_TEST_LOCK_SOUND:
      triggerVehicleFeedback(true);
      break;
    case CMD_SHOW_BEGIN:
      autoshow.stop(); showEditing = true; showDraft = ShowPattern();
      showMasksReceived = showTimesReceived = 0; showEditAt = millis();
      break;
    case CMD_SHOW_MASK:
    case CMD_SHOW_TIME:
      if (!showEditing || packet.target >= 16 || millis() - showEditAt > 10000) { result = 1; break; }
      if (packet.command == CMD_SHOW_MASK) {
        if (packet.value < 0 || packet.value > 127 || (packet.value & 3) == 3) { result = 1; break; }
        showDraft.masks[packet.target] = packet.value;
        showMasksReceived |= 1u << packet.target;
      } else {
        if (packet.value < 200 || packet.value > 5000) { result = 1; break; }
        showDraft.durations[packet.target] = packet.value;
        showTimesReceived |= 1u << packet.target;
      }
      showEditAt = millis();
      break;
    case CMD_SHOW_SAVE:
      if (!showEditing || millis() - showEditAt > 10000 || showMasksReceived != 0xFFFF || showTimesReceived != 0xFFFF ||
          (packet.value != 0 && packet.value != 1)) { result = 1; break; }
      showDraft.repeat = packet.value;
      if (!showDraft.valid() || prefs.putBytes("showV1", &showDraft, sizeof(showDraft)) != sizeof(showDraft)) { result = 1; break; }
      showPattern = showDraft; showEditing = false;
      break;
    case CMD_SHOW_CONTROL:
      if (packet.value == 0) autoshow.stop();
      else if (packet.value == 1 && !showEditing && fromBluetooth && ownerSession.load()) autoshow.start(millis());
      else result = 1;
      break;
    case CMD_SHOW_GET:
      if (fromBluetooth) reportShow(packet.sequence, true);
      return;
    case CMD_SET_AUTO:
      if (packet.value == 0 || packet.value == 1) {
        autoEnabled = packet.value;
        saveLightConfig();
      } else result = 1;
      break;
    case CMD_SET_MANUAL:
      if (!setManualTarget(packet.target, packet.value, false)) result = 1;
      // Una orden a un grupo recupera el control de sus dos miembros; de otra
      // forma una orden individual previa impediría que el botón grupal funcione.
      if (result == 0 && packet.target == TARGET_WHITE_RINGS) {
        manual.whiteLeft = -1;
        manual.whiteRight = -1;
      }
      if (result == 0 && packet.target == TARGET_ORANGE_RINGS) {
        manual.orangeLeft = -1;
        manual.orangeRight = -1;
      }
      break;
    case CMD_RELEASE_MANUAL:
      if (!setManualTarget(packet.target, 0, true)) result = 1;
      break;
    case CMD_PULSE_HORN:
      hornUntil = millis() + constrain(packet.value, static_cast<int16_t>(50),
        static_cast<int16_t>(MAX_HORN_APP_MS));
      break;
    case CMD_LOCK:
      unlockUntil = 0;
      lockUntil = millis() + ACTUATOR_PULSE_MS;
      triggerVehicleFeedback(true);
      break;
    case CMD_UNLOCK:
      lockUntil = 0;
      unlockUntil = millis() + ACTUATOR_PULSE_MS;
      triggerVehicleFeedback(false);
      break;
    case CMD_SET_LDR_ON:
      if (packet.value >= 0 && packet.value <= 4095 &&
          (LDR_DARK_IS_LOW ? packet.value + 50 <= ldrOffThreshold : packet.value >= ldrOffThreshold + 50)) {
        ldrOnThreshold = packet.value;
        saveLightConfig();
      } else result = 1;
      break;
    case CMD_SET_LDR_OFF:
      if (packet.value >= 0 && packet.value <= 4095 &&
          (LDR_DARK_IS_LOW ? packet.value >= ldrOnThreshold + 50 : packet.value + 50 <= ldrOnThreshold)) {
        ldrOffThreshold = packet.value;
        saveLightConfig();
      } else result = 1;
      break;
    case CMD_GET_STATE:
      calculateOutputs();
      if (fromBluetooth) { reportState(packet.sequence); reportDevices(packet.sequence); reportShow(packet.sequence, false); reportLdr(packet.sequence, false); }
      return;
    case CMD_AUTHORIZE_DEVICE:
      if (!fromBluetooth || !ownerSession.load() || !beginEnrollment()) result = 1;
      if (fromBluetooth) reportDevices(packet.sequence);
      break;
    case CMD_SET_CONFIG:
      if (!setFeedbackConfig(packet.target, packet.value)) result = 1;
      else saveFeedbackConfig();
      break;
    case CMD_GET_CONFIG:
      if (fromBluetooth) { reportFeedbackConfig(packet.sequence); reportLdr(packet.sequence, true); }
      return;
    default:
      result = 2;
      break;
  }
  calculateOutputs();
  if (fromBluetooth) {
    sendAck(packet.sequence, packet.command, result);
    if (packet.command == CMD_SHOW_SAVE || packet.command == CMD_SHOW_CONTROL) reportShow(packet.sequence, false);
    if (packet.command == CMD_SET_CONFIG && result == 0) reportFeedbackConfig(packet.sequence);
    reportState(packet.sequence);
  }
}

void handleBluetooth() {
  if (!ownerSession.load()) {
    btLength = 0;
    while (SerialBT.available()) SerialBT.read();
    return;
  }
  while (SerialBT.available()) {
    const uint8_t incoming = static_cast<uint8_t>(SerialBT.read());
    if (btLength == 0 && incoming != BCM_SOF) continue;
    btBuffer[btLength++] = incoming;
    if (btLength < sizeof(BcmPacket)) continue;
    BcmPacket packet;
    memcpy(&packet, btBuffer, sizeof(packet));
    btLength = 0;
    if (ownerSession.load() && bcmIsValid(packet)) processCommand(packet, true);
    else Serial.println("Bluetooth: paquete inválido");
  }
}

#if ESP_ARDUINO_VERSION_MAJOR >= 3
void onEspNowReceive(const esp_now_recv_info_t *info, const uint8_t *data, int length) {
  if (length != sizeof(BcmPacket) || !macConfigured(MAC_DEL_NODO_TRASERO) ||
      memcmp(info->src_addr, MAC_DEL_NODO_TRASERO, 6) != 0 || espNowQueue == nullptr) return;
  BcmPacket packet;
  memcpy(&packet, data, sizeof(packet));
  xQueueSend(espNowQueue, &packet, 0);
}
#else
void onEspNowReceive(const uint8_t *mac, const uint8_t *data, int length) {
  if (length != sizeof(BcmPacket) || !macConfigured(MAC_DEL_NODO_TRASERO) ||
      memcmp(mac, MAC_DEL_NODO_TRASERO, 6) != 0 || espNowQueue == nullptr) return;
  BcmPacket packet;
  memcpy(&packet, data, sizeof(packet));
  xQueueSend(espNowQueue, &packet, 0);
}
#endif

void handleEspNowPackets() {
  if (espNowQueue == nullptr) return;
  BcmPacket packet;
  while (xQueueReceive(espNowQueue, &packet, 0) == pdTRUE) {
    if (bcmIsValid(packet) && packet.command == CMD_HEARTBEAT) {
      Serial.println("Heartbeat recibido del nodo trasero");
    }
  }
}

bool webAuth() {
  if (web.authenticate(OTA_USER, OTA_PASSWORD)) return true;
  web.requestAuthentication();
  return false;
}

String page() {
  String text = F("<!doctype html><meta name=viewport content='width=device-width,initial-scale=1'>"
    "<title>BCM VW1980</title><h2>BCM principal</h2><p>LDR: ");
  text += String(static_cast<int>(ldrFiltered));
  text += autoDark ? F(" oscuro") : F(" claro");
  text += F("</p><h3>Wi-Fi</h3><form method=post action=/wifi>SSID <input name=ssid required><br>"
    "Clave <input name=pass type=password><br><button>Guardar y reiniciar</button></form>"
    "<h3>OTA</h3><form method=post action=/update enctype='multipart/form-data'>"
    "<input type=file name=firmware accept='.bin' required><button>Actualizar</button></form>");
  return text;
}

void startWeb() {
  web.on("/", HTTP_GET, []() { if (webAuth()) web.send(200, "text/html", page()); });
  web.on("/wifi", HTTP_POST, []() {
    if (!webAuth()) return;
    const String ssid = web.arg("ssid");
    const String pass = web.arg("pass");
    if (ssid.length() == 0 || ssid.length() > 32 || pass.length() > 63) {
      web.send(400, "text/plain", "SSID o clave inválidos");
      return;
    }
    prefs.putString("wifiSsid", ssid);
    prefs.putString("wifiPass", pass);
    web.send(200, "text/plain", "Guardado; reiniciando.");
    restartAt = millis() + 1000;
  });
  web.on("/update", HTTP_POST, []() {
    if (!webAuth()) return;
    const bool ok = !Update.hasError();
    web.send(ok ? 200 : 500, "text/plain", ok ? "Actualizado; reiniciando." : "Error OTA");
    if (ok) restartAt = millis() + 1200;
  }, []() {
    if (!webAuth()) return;
    HTTPUpload &upload = web.upload();
    if (upload.status == UPLOAD_FILE_START) {
      if (!Update.begin(UPDATE_SIZE_UNKNOWN)) Update.printError(Serial);
    } else if (upload.status == UPLOAD_FILE_WRITE) {
      if (Update.write(upload.buf, upload.currentSize) != upload.currentSize) Update.printError(Serial);
    } else if (upload.status == UPLOAD_FILE_END) {
      if (!Update.end(true)) Update.printError(Serial);
    }
  });
  web.begin();
}

void startNetwork() {
  const String ssid = prefs.getString("wifiSsid", "");
  const String pass = prefs.getString("wifiPass", "");
  WiFi.mode(WIFI_STA);
  WiFi.setSleep(false);
  if (ssid.length() > 0) {
    WiFi.begin(ssid.c_str(), pass.c_str());
    const uint32_t started = millis();
    while (WiFi.status() != WL_CONNECTED && millis() - started < 12000) delay(50);
  }
  if (WiFi.status() == WL_CONNECTED) {
    Serial.printf("Wi-Fi: %s\n", WiFi.localIP().toString().c_str());
  } else {
    WiFi.mode(WIFI_AP_STA);
    WiFi.softAP(SETUP_AP_SSID, SETUP_AP_PASSWORD, 1);
    Serial.printf("AP: %s / %s\n", SETUP_AP_SSID, WiFi.softAPIP().toString().c_str());
  }
  ArduinoOTA.setHostname(DEVICE_NAME);
  ArduinoOTA.setPassword(OTA_PASSWORD);
  ArduinoOTA.onStart([]() { safeOutputsOff(); });
  ArduinoOTA.begin();
  startWeb();
}

void startEspNow() {
  espNowQueue = xQueueCreate(8, sizeof(BcmPacket));
  if (espNowQueue == nullptr) {
    Serial.println("No se pudo crear cola ESP-NOW");
    return;
  }
  const esp_err_t err = esp_now_init();
  if (err != ESP_OK) {
    Serial.printf("esp_now_init: %d\n", err);
    return;
  }
  if (ESPNOW_ENCRYPTED) esp_now_set_pmk(reinterpret_cast<const uint8_t *>(ESPNOW_PMK));
  esp_now_register_recv_cb(onEspNowReceive);
  addRearPeer();
  Serial.printf("MAC principal: %s\n", WiFi.macAddress().c_str());
}

void setup() {
  Serial.begin(115200);
  delay(300);
  prefs.begin("bcm", false);
  const size_t savedLength = prefs.getBytesLength("btAllowed");
  if (savedLength > 0 && savedLength <= sizeof(authorizedAddresses) && savedLength % 6 == 0 &&
      prefs.getBytes("btAllowed", authorizedAddresses, savedLength) == savedLength) {
    authorizedCount = savedLength / 6;
  } else if (prefs.getBytesLength("btOwner") == 6) {
    uint8_t previousOwner[6];
    if (prefs.getBytes("btOwner", previousOwner, 6) == 6) addAuthorized(previousOwner);
  }
  btOwnerQueue = xQueueCreate(8, sizeof(OwnerEvent));
  loadLightConfig();
  loadFeedbackConfig();
  ShowPattern savedShow;
  if (prefs.getBytesLength("showV1") == sizeof(savedShow) &&
      prefs.getBytes("showV1", &savedShow, sizeof(savedShow)) == sizeof(savedShow) && savedShow.valid()) showPattern = savedShow;
  configurePins();

  // En Core 3.x setPin exige el segundo argumento (longitud del PIN).
#if ESP_ARDUINO_VERSION_MAJOR >= 3
  SerialBT.setPin(BT_PIN, sizeof(BT_PIN) - 1);
#else
  SerialBT.setPin(BT_PIN);
#endif
  SerialBT.register_callback(onOwnerSppEvent);
  SerialBT.begin(DEVICE_NAME);
  Serial.printf("Bluetooth SPP: %s\n", DEVICE_NAME);

  startNetwork();
  startEspNow();
}

void loop() {
  handleOwner();
  if (!ownerSession.load()) ldrFields = 0;
  if (!ownerSession.load() || (showEditing && millis() - showEditAt > 10000)) showEditing = false;
  handleBluetooth();
  handleEspNowPackets();
  updateLdr();
  calculateOutputs();
  writeOutputs();

  const uint16_t newMask = outputMask();
  if (newMask != lastOutputMask || millis() - lastRearSyncAt >= REAR_SYNC_MS) {
    lastOutputMask = newMask;
    lastRearSyncAt = millis();
    sendRearState();
  }

  web.handleClient();
  ArduinoOTA.handle();
  if (restartAt != 0 && millis() >= restartAt) ESP.restart();
  delay(5);
}
