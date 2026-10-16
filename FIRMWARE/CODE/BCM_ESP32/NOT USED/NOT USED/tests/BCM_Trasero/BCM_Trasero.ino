/*
  BCM para VW Sedán 1980 - Nodo trasero

  Sólo controla cuartos traseros y lámpara de placa. Recibe CMD_SYNC_REAR del
  nodo principal mediante el mismo BcmPacket de 10 bytes usado por Bluetooth.
  Necesita estar en el mismo canal Wi-Fi que el principal: configure ambos con
  el mismo router desde la página de configuración, o use ambos AP de respaldo
  mientras se hacen pruebas (canal 1).
*/

#include <Arduino.h>
#include <WiFi.h>
#include <esp_now.h>
#include <ArduinoOTA.h>
#include <WebServer.h>
#include <Update.h>
#include <Preferences.h>
#include <esp_arduino_version.h>
#include "BcmProtocol.h"

constexpr char DEVICE_NAME[] = "BCM-VW1980-Rear";
constexpr char OTA_USER[] = "bcm";
constexpr char OTA_PASSWORD[] = "CAMBIA-ESTA-CLAVE"; // debe igualar si se desea misma clave OTA.
constexpr char SETUP_AP_SSID[] = "BCM-VW1980-Rear-Setup";
constexpr char SETUP_AP_PASSWORD[] = "cambia-esta-clave";

// Copie aquí la STA MAC que imprime el nodo principal. No termine la instalación
// con ceros: el nodo trasero rechazará los paquetes por seguridad.
const uint8_t MAC_DEL_NODO_PRINCIPAL[6] = {0x00, 0x00, 0x00, 0x00, 0x00, 0x00};
constexpr char ESPNOW_PMK[] = "0123456789ABCDEF";
constexpr char ESPNOW_LMK[] = "FEDCBA9876543210";
constexpr bool ESPNOW_ENCRYPTED = true;

// PENDIENTE DE CONFIRMACIÓN: el usuario no proporcionó pines para el nodo 2.
// GPIO 25 y GPIO 26 son una propuesta segura para un ESP32 clásico; cámbielos
// aquí por los dos GPIO realmente cableados antes de instalar el nodo.
constexpr int PIN_RELAY_REAR_PARKING = 25;
constexpr int PIN_RELAY_LICENSE_LIGHT = 26;
constexpr uint8_t RELAY_ON = HIGH;
constexpr uint8_t RELAY_OFF = (RELAY_ON == HIGH) ? LOW : HIGH;

WebServer web(80);
Preferences prefs;
QueueHandle_t espNowQueue;
bool rearParking = false;
bool licenseLight = false;
uint32_t lastPacketAt = 0;
uint32_t restartAt = 0;

bool macConfigured(const uint8_t mac[6]) {
  for (uint8_t i = 0; i < 6; ++i) if (mac[i] != 0) return true;
  return false;
}

void relayWrite(int pin, bool on) { digitalWrite(pin, on ? RELAY_ON : RELAY_OFF); }

void writeOutputs() {
  relayWrite(PIN_RELAY_REAR_PARKING, rearParking);
  relayWrite(PIN_RELAY_LICENSE_LIGHT, licenseLight);
}

void safeOutputsOff() {
  pinMode(PIN_RELAY_REAR_PARKING, OUTPUT);
  pinMode(PIN_RELAY_LICENSE_LIGHT, OUTPUT);
  rearParking = licenseLight = false;
  writeOutputs();
}

void sendHeartbeat() {
  if (!macConfigured(MAC_DEL_NODO_PRINCIPAL)) return;
  BcmPacket packet = {};
  packet.kind = PKT_COMMAND;
  packet.sequence = 0;
  packet.command = CMD_HEARTBEAT;
  packet.target = TARGET_SYSTEM;
  packet.value = 0;
  bcmFinalize(packet);
  esp_now_send(MAC_DEL_NODO_PRINCIPAL, reinterpret_cast<const uint8_t *>(&packet), sizeof(packet));
}

#if ESP_ARDUINO_VERSION_MAJOR >= 3
void onEspNowReceive(const esp_now_recv_info_t *info, const uint8_t *data, int length) {
  if (length != sizeof(BcmPacket) || !macConfigured(MAC_DEL_NODO_PRINCIPAL) ||
      memcmp(info->src_addr, MAC_DEL_NODO_PRINCIPAL, 6) != 0) return;
  BcmPacket packet;
  memcpy(&packet, data, sizeof(packet));
  xQueueSend(espNowQueue, &packet, 0);
}
#else
void onEspNowReceive(const uint8_t *mac, const uint8_t *data, int length) {
  if (length != sizeof(BcmPacket) || !macConfigured(MAC_DEL_NODO_PRINCIPAL) ||
      memcmp(mac, MAC_DEL_NODO_PRINCIPAL, 6) != 0) return;
  BcmPacket packet;
  memcpy(&packet, data, sizeof(packet));
  xQueueSend(espNowQueue, &packet, 0);
}
#endif

bool addMainPeer() {
  if (!macConfigured(MAC_DEL_NODO_PRINCIPAL)) {
    Serial.println("ESP-NOW: falta MAC_DEL_NODO_PRINCIPAL; recepción bloqueada.");
    return false;
  }
  esp_now_peer_info_t peer = {};
  memcpy(peer.peer_addr, MAC_DEL_NODO_PRINCIPAL, 6);
  peer.channel = 0;
  peer.ifidx = WIFI_IF_STA;
  peer.encrypt = ESPNOW_ENCRYPTED;
  if (ESPNOW_ENCRYPTED) memcpy(peer.lmk, ESPNOW_LMK, 16);
  const esp_err_t error = esp_now_add_peer(&peer);
  if (error != ESP_OK && error != ESP_ERR_ESPNOW_EXIST) {
    Serial.printf("ESP-NOW add peer falló: %d\n", error);
    return false;
  }
  return true;
}

void processEspNow() {
  BcmPacket packet;
  while (xQueueReceive(espNowQueue, &packet, 0) == pdTRUE) {
    if (!bcmIsValid(packet) || packet.kind != PKT_COMMAND ||
        packet.command != CMD_SYNC_REAR || packet.target != TARGET_REAR_STATE) continue;
    rearParking = (packet.value & 0x01) != 0;
    licenseLight = (packet.value & 0x02) != 0;
    lastPacketAt = millis();
    writeOutputs();
  }
}

bool authenticateWeb() {
  if (web.authenticate(OTA_USER, OTA_PASSWORD)) return true;
  web.requestAuthentication();
  return false;
}

String page() {
  String text = F("<!doctype html><meta name=viewport content='width=device-width,initial-scale=1'>"
                  "<h2>BCM nodo trasero</h2><p>IP: ");
  text += WiFi.localIP().toString();
  text += F("</p><p>Cuartos: "); text += rearParking ? F("on") : F("off");
  text += F(" | Placa: "); text += licenseLight ? F("on") : F("off");
  text += F("</p><form method=post action=/wifi>SSID <input name=ssid required><br>"
            "Clave <input name=pass type=password><br><button>Guardar Wi-Fi</button></form>"
            "<h3>OTA por web</h3><form method=post action=/update enctype='multipart/form-data'>"
            "<input type=file name=firmware accept='.bin' required><button>Actualizar</button></form>");
  return text;
}

void startWebServer() {
  web.on("/", HTTP_GET, []() { if (authenticateWeb()) web.send(200, "text/html", page()); });
  web.on("/wifi", HTTP_POST, []() {
    if (!authenticateWeb()) return;
    const String ssid = web.arg("ssid");
    if (ssid.length() == 0 || ssid.length() > 32 || web.arg("pass").length() > 63) {
      web.send(400, "text/plain", "SSID o clave no válidos"); return;
    }
    prefs.putString("wifiSsid", ssid);
    prefs.putString("wifiPass", web.arg("pass"));
    web.send(200, "text/plain", "Guardado; reiniciando.");
    restartAt = millis() + 1000;
  });
  web.on("/update", HTTP_POST, []() {
    if (!authenticateWeb()) return;
    bool ok = !Update.hasError();
    web.send(ok ? 200 : 500, "text/plain", ok ? "Firmware recibido. Reiniciando." : "Error al actualizar.");
    if (ok) restartAt = millis() + 1200;
  }, []() {
    if (!authenticateWeb()) return;
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
    Serial.printf("Wi-Fi conectado: %s\n", WiFi.localIP().toString().c_str());
  } else {
    WiFi.mode(WIFI_AP_STA);
    WiFi.softAP(SETUP_AP_SSID, SETUP_AP_PASSWORD, 1);
    Serial.printf("AP configuración: %s / %s\n", SETUP_AP_SSID, WiFi.softAPIP().toString().c_str());
  }
  ArduinoOTA.setHostname(DEVICE_NAME);
  ArduinoOTA.setPassword(OTA_PASSWORD);
  ArduinoOTA.onStart([]() { safeOutputsOff(); });
  ArduinoOTA.begin();
  startWebServer();
}

void startEspNow() {
  espNowQueue = xQueueCreate(8, sizeof(BcmPacket));
  if (espNowQueue == nullptr || esp_now_init() != ESP_OK) {
    Serial.println("No se pudo iniciar ESP-NOW"); return;
  }
  if (ESPNOW_ENCRYPTED) esp_now_set_pmk(reinterpret_cast<const uint8_t *>(ESPNOW_PMK));
  esp_now_register_recv_cb(onEspNowReceive);
  addMainPeer();
  Serial.printf("MAC trasera (copiar en principal): %s\n", WiFi.macAddress().c_str());
}

void setup() {
  Serial.begin(115200);
  delay(300);
  prefs.begin("bcm", false);
  safeOutputsOff();
  startNetwork();
  startEspNow();
}

void loop() {
  processEspNow();
  // Falla segura: si pierde al principal, apaga sólo cargas que este nodo controla.
  if (lastPacketAt != 0 && millis() - lastPacketAt > 3000) {
    rearParking = licenseLight = false;
    writeOutputs();
  }
  web.handleClient();
  ArduinoOTA.handle();
  if (restartAt != 0 && millis() >= restartAt) ESP.restart();
  delay(5);
}
