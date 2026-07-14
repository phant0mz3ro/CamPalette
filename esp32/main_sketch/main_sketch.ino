#include <Wire.h>
#include <Adafruit_GFX.h>
#include <Adafruit_SSD1306.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>

#define SCREEN_WIDTH 128
#define SCREEN_HEIGHT 64
Adafruit_SSD1306 display(SCREEN_WIDTH, SCREEN_HEIGHT, &Wire, -1);

#define SERVICE_UUID        "4fafc201-1fb5-459e-8fcc-c5c9c331914b"
#define CHARACTERISTIC_UUID "beb5483e-36e1-4688-b7f5-ea07361b26a8"

BLEServer* pServer = NULL;
BLECharacteristic* pCharacteristic = NULL;
bool deviceConnected = false;
bool oldDeviceConnected = false;
uint32_t value = 0;

const int joyXPin = 12;
const int joyYPin = 14;
const int shutterButtonPin = 2;

String modes[] = {"moody", "colorful"};
int modeIndex = 0;
int intensity = 50;

unsigned long lastMoveTime = 0;
bool lastButtonState = HIGH;

unsigned long buttonPressStartTime = 0;
bool longPressSent = false;


void showMessage(String message) {
    char text[30];
    sprintf(text,"%s!",message);

    display.clearDisplay();
    display.setTextSize(2);
    display.setTextColor(SSD1306_WHITE);
    display.setCursor(0, 20);
    display.println(text);
    display.display();
    delay(2000); // brief confirmation, then switch to normal display
    updateDisplay();
}

void updateDisplay() {
    display.clearDisplay();
    display.setTextSize(2);
    display.setTextColor(SSD1306_WHITE);
    display.setCursor(0, 0);
    display.println(modes[modeIndex]);
    display.setTextSize(1);
    display.setCursor(0, 30);
    display.print("Intensity: ");
    display.println(intensity);
    display.display();
}

class ServerCallbacks : public BLEServerCallbacks {
    void onConnect(BLEServer* pServer) { 
        deviceConnected = true; 
        showMessage("Connected");
        }
    void onDisconnect(BLEServer* pServer) {
        deviceConnected = false;
        showMessage("Disconnected");
    }
};

void setup() {
    Serial.begin(115200);
    pinMode(shutterButtonPin, INPUT_PULLUP);
    
    delay(50);
    digitalRead(shutterButtonPin); // throwaway read to let pin stabilize
    lastButtonState = digitalRead(shutterButtonPin) == LOW ? LOW : HIGH; // sync to actual current state

    Wire.begin(13, 15); // SDA = GPIO13, SCL = GPIO15

    // OLED init/
    
    if (!display.begin(SSD1306_SWITCHCAPVCC, 0x3C)) {
        Serial.println("OLED init failed");
    }
    display.clearDisplay();
    updateDisplay();

    // BLE init
    BLEDevice::init("CamStick");
    pServer = BLEDevice::createServer();
    pServer->setCallbacks(new ServerCallbacks());

    BLEService *pService = pServer->createService(SERVICE_UUID);
    pCharacteristic = pService->createCharacteristic(
                      CHARACTERISTIC_UUID,
                      BLECharacteristic::PROPERTY_READ   |
                      BLECharacteristic::PROPERTY_WRITE  |
                      BLECharacteristic::PROPERTY_NOTIFY |
                      BLECharacteristic::PROPERTY_INDICATE
                    );
    pCharacteristic->addDescriptor(new BLE2902());
    pService->start();

    BLEAdvertising *pAdvertising = BLEDevice::getAdvertising();
    pAdvertising->addServiceUUID(SERVICE_UUID);
    pAdvertising->setScanResponse(false);
    pAdvertising->setMinPreferred(0x0);
    BLEDevice::startAdvertising();

    Serial.println("BLE advertising started");
}


void sendUpdate() {
    String payload = "mode:" + modes[modeIndex] + ",intensity:" + String(intensity);
    pCharacteristic->setValue(payload.c_str());
    pCharacteristic->notify();
    Serial.println("Sent: " + payload);
}

void sendShutter() {
    pCharacteristic->setValue("shutter:1");
    pCharacteristic->notify();
    Serial.println("Sent: shutter:1");
}

void sendSave() {
    pCharacteristic->setValue("save:1");
    pCharacteristic->notify();
    Serial.println("Sent: save:1");
}

void loop() {
    if (!deviceConnected) {
        delay(200);
        return;
    }

    int xVal = analogRead(joyXPin); // 0-4095, center ~2048
    int yVal = analogRead(joyYPin);

    unsigned long now = millis();
    bool changed = false;

    // Left/right cycles mode (with debounce delay so it doesn't spam)
    if (now - lastMoveTime > 400) {
        if (xVal < 1000) {
            modeIndex = (modeIndex + 1) % 2;
            changed = true;
            lastMoveTime = now;
        } else if (xVal > 3000) {
            modeIndex = (modeIndex - 1 + 2) % 2;
            changed = true;
            lastMoveTime = now;
        }
    }

    // Up/down adjusts intensity
    if (now - lastMoveTime > 150) {
        if (yVal < 1000 && intensity < 100) {
            intensity += 5;
            changed = true;
            lastMoveTime = now;
        } else if (yVal > 3000 && intensity > 0) {
            intensity -= 5;
            changed = true;
            lastMoveTime = now;
        }
    }

    if (changed) {
        intensity = constrain(intensity, 0, 100);
        updateDisplay();
        sendUpdate();
    }

    bool shutterPressed = digitalRead(shutterButtonPin) == LOW;

    if (shutterPressed && lastButtonState == HIGH) {
        // Button just went down — start timing
        buttonPressStartTime = now;
        longPressSent = false;
    }

    if (shutterPressed && !longPressSent && (now - buttonPressStartTime > 800)) {
        // Held for 800ms+ — treat as long press
        sendSave();
        longPressSent = true;
    }

    if (!shutterPressed && lastButtonState == LOW && !longPressSent) {
        // Released quickly, before long-press threshold — treat as normal press
        sendShutter();
    }

    lastButtonState = shutterPressed ? LOW : HIGH;
    delay(50);
}