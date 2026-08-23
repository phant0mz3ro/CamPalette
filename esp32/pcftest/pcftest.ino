#include <Arduino.h>
#include <Wire.h>
#include <Adafruit_GFX.h>
#include <Adafruit_SSD1306.h>

#define SDA_PIN 14
#define SCL_PIN 15
#define INT_PIN 13  // Connect PCF8574 INT pin to GPIO 13

#define PCF_ADDR  0x20
#define OLED_ADDR 0x3C

#define SCREEN_WIDTH  128
#define SCREEN_HEIGHT 64
Adafruit_SSD1306 display(SCREEN_WIDTH, SCREEN_HEIGHT, &Wire, -1);

volatile bool pcfInterruptTriggered = false;

int encoderValue = 0;
uint8_t prevQuadrature = 0;
bool lastButtonState = HIGH;

// Interrupt Service Routine (ISR)
void IRAM_ATTR pcfISR() {
  pcfInterruptTriggered = true;
}

void setup() {
  Serial.begin(115200);
  delay(500);

  pinMode(INT_PIN, INPUT_PULLUP);
  attachInterrupt(digitalPinToInterrupt(INT_PIN), pcfISR, FALLING);

  Wire.setPins(SDA_PIN, SCL_PIN);
  Wire.begin();
  Wire.setClock(400000); // Fast I2C

  if (!display.begin(SSD1306_SWITCHCAPVCC, OLED_ADDR)) {
    Serial.println("OLED Init Failed");
    for(;;);
  }

  // Set PCF8574 pins to input mode (HIGH)
  Wire.beginTransmission(PCF_ADDR);
  Wire.write(0xFF);
  Wire.endTransmission();

  // Read initial state to clear any pending interrupt on PCF8574
  Wire.requestFrom(PCF_ADDR, 1);
  if (Wire.available()) {
    prevQuadrature = (Wire.read() & 0x03);
  }

  display.clearDisplay();
  display.setTextSize(1);
  display.setTextColor(SSD1306_WHITE);
  display.setCursor(0, 10);
  display.println("INT-Driven PCF8574");
  display.display();
}

void loop() {
  if (pcfInterruptTriggered) {
    pcfInterruptTriggered = false; // Reset ISR flag

    // Read PCF8574 inputs
    Wire.requestFrom(PCF_ADDR, 1);
    if (Wire.available()) {
      byte pins = Wire.read();

      uint8_t currentQuadrature = (pins & 0x03); // P0 = S1, P1 = S2
      bool currentButton = bitRead(pins, 2);      // P2 = KEY

      if (currentQuadrature != prevQuadrature) {
        uint8_t stateTransition = (prevQuadrature << 2) | currentQuadrature;

        if (stateTransition == 0b0001 || stateTransition == 0b0111 || 
            stateTransition == 0b1110 || stateTransition == 0b1000) {
          encoderValue++;
        } else if (stateTransition == 0b0010 || stateTransition == 0b1011 || 
                   stateTransition == 0b1101 || stateTransition == 0b0100) {
          encoderValue--;
        }
        prevQuadrature = currentQuadrature;

        // Print immediately to Serial without blocking I2C with OLED updates
        Serial.print("Encoder Value: ");
        Serial.println(encoderValue);
      }
    }
  }
}