# FaceLock — ESP32-CAM Wiring Reference

## Bill of materials
| Component | Model | Qty |
|---|---|---|
| Camera + MCU | ESP32-CAM (AI Thinker, OV2640) | 1 |
| Programmer | FTDI USB-to-Serial (3.3V/5V) | 1 |
| Relay | 5V 1-channel opto-isolated relay | 1 |
| Lock | 12V solenoid lock (fail-secure) | 1 |
| Motion sensor | HC-SR501 PIR | 1 |
| Display | 16x2 LCD with I2C backpack (PCF8574) | 1 |
| Power | 12V/2A adapter + LM2596 buck to 5V | 1 |
| Misc | Flyback diode (1N4007), jumper wires | — |

## Connections

### ESP32-CAM → Relay module
| ESP32-CAM | Relay |
|---|---|
| GPIO 12 | IN |
| 5V | VCC |
| GND | GND |

### Relay → Solenoid lock
| Relay | Wiring |
|---|---|
| COM | 12V+ from power supply |
| NO | Solenoid + terminal |
| — | Solenoid − terminal → 12V GND |

Place a 1N4007 flyback diode across the solenoid terminals (band toward 12V+)
to absorb the inductive spike when the lock releases.

### ESP32-CAM → PIR sensor
| ESP32-CAM | HC-SR501 |
|---|---|
| GPIO 13 | OUT |
| 5V | VCC |
| GND | GND |

### ESP32-CAM → I2C LCD
| ESP32-CAM | LCD backpack |
|---|---|
| GPIO 14 | SDA |
| GPIO 15 | SCL |
| 5V | VCC |
| GND | GND |

### Power distribution
12V/2A adapter feeds the solenoid directly and an LM2596 buck converter
stepped down to 5V for the ESP32-CAM, relay, PIR, and LCD.
**Tie all grounds together** or the relay will trigger erratically.

## Flashing notes
1. Connect FTDI: TX→U0R, RX→U0T, 5V→5V, GND→GND.
2. Jumper **GPIO 0 to GND** to enter flash mode.
3. In Arduino IDE select **AI Thinker ESP32-CAM** and partition
   scheme **Huge APP (3MB No OTA)**.
4. Upload, then remove the GPIO 0 jumper and press RESET.

## Required libraries
- `ArduinoJson` (Benoit Blanchon)
- `LiquidCrystal_I2C` (Frank de Brabander)
- ESP32 board package 2.0.x or newer
