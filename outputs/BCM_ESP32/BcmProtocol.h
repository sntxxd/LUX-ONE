#pragma once

// Protocolo BCM v1. Se envían exactamente estos 10 bytes por Bluetooth SPP y
// ESP-NOW. No usar String ni JSON para los mandos: este formato es atómico,
// comprobable con CRC y no cambia al añadir nodos.

#include <Arduino.h>

constexpr uint8_t BCM_SOF = 0xA5;
constexpr uint8_t BCM_PROTOCOL_VERSION = 1;

enum PacketKind : uint8_t {
  PKT_COMMAND = 0x01,
  PKT_STATE   = 0x81,
  PKT_ACK     = 0x82,
};

enum BcmCommand : uint8_t {
  CMD_SET_AUTO          = 0x10, // value: 0/1
  CMD_SET_MANUAL        = 0x11, // target/value, conserva la orden manual
  CMD_RELEASE_MANUAL    = 0x12, // target, devuelve el canal a automático
  CMD_PULSE_HORN        = 0x13, // value: duración solicitada en ms
  CMD_LOCK              = 0x14, // pulso actuador 1
  CMD_UNLOCK            = 0x15, // pulso actuador 2
  CMD_SET_LDR_ON        = 0x16, // value: 0..4095, umbral de encendido
  CMD_SET_LDR_OFF       = 0x17, // value: 0..4095, umbral de apagado
  CMD_GET_STATE         = 0x18,
  CMD_SET_CONFIG        = 0x19, // target: configuración persistente
  CMD_GET_CONFIG        = 0x1A,
  CMD_AUTHORIZE_DEVICE  = 0x1B, // abre ventana 120 s; solo Bluetooth autorizado

  CMD_SYNC_REAR         = 0x30, // principal -> nodo trasero
  CMD_HEARTBEAT         = 0x31, // reservado para nodos futuros

  CMD_REPORT_STATE      = 0x80,
  CMD_REPORT_ACK        = 0x81,
  CMD_REPORT_CONFIG     = 0x83,
  CMD_REPORT_DEVICES    = 0x84, // value: cantidad; flags: segundos de alta restantes
};

enum BcmTarget : uint8_t {
  TARGET_SYSTEM         = 0,
  TARGET_HEADLIGHT      = 1, // value: 0=off, 1=bajas, 2=altas
  TARGET_PARKING        = 2, // cuartos delanteros y traseros
  TARGET_WHITE_RINGS    = 3,
  TARGET_ORANGE_LEFT    = 4,
  TARGET_ORANGE_RIGHT   = 5,
  TARGET_REAR_PARKING   = 6, // normalmente se controla con TARGET_PARKING
  TARGET_LICENSE_LIGHT  = 7,
  TARGET_REAR_STATE     = 8,
  TARGET_WHITE_LEFT     = 9,  // control individual de aro blanco
  TARGET_WHITE_RIGHT    = 10, // control individual de aro blanco
  TARGET_ORANGE_RINGS   = 11, // ambos aros naranjas; los targets 4/5 son individuales
  TARGET_CFG_LOCK_CHIRP = 20, // 0/1
  TARGET_CFG_LOCK_WHITE = 21, // 0/1
  TARGET_CFG_UNLOCK_CHIRP = 22, // 0/1
  TARGET_CFG_UNLOCK_WHITE = 23, // 0/1
  TARGET_CFG_FEEDBACK_MS = 24, // 50..1000 ms
};

// El empaquetado es obligatorio: sizeof(BcmPacket) debe ser 10 en todos los
// nodos y en la app móvil.
struct __attribute__((packed)) BcmPacket {
  uint8_t sof;
  uint8_t version;
  uint8_t kind;
  uint8_t sequence;
  uint8_t command;
  uint8_t target;
  int16_t value;  // little-endian (ESP32 y Android normalmente lo son)
  uint8_t flags;
  uint8_t crc8;
};

static_assert(sizeof(BcmPacket) == 10, "BcmPacket debe medir 10 bytes");

inline uint8_t bcmCrc8(const uint8_t *data, size_t length) {
  uint8_t crc = 0x00;
  while (length--) {
    crc ^= *data++;
    for (uint8_t bit = 0; bit < 8; ++bit) {
      crc = (crc & 0x80) ? (uint8_t)((crc << 1) ^ 0x07) : (uint8_t)(crc << 1);
    }
  }
  return crc;
}

inline void bcmFinalize(BcmPacket &packet) {
  packet.sof = BCM_SOF;
  packet.version = BCM_PROTOCOL_VERSION;
  packet.crc8 = bcmCrc8(reinterpret_cast<const uint8_t *>(&packet), sizeof(BcmPacket) - 1);
}

inline bool bcmIsValid(const BcmPacket &packet) {
  return packet.sof == BCM_SOF &&
         packet.version == BCM_PROTOCOL_VERSION &&
         packet.crc8 == bcmCrc8(reinterpret_cast<const uint8_t *>(&packet), sizeof(BcmPacket) - 1);
}
