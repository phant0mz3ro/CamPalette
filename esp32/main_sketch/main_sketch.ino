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

const int joyXPin = 14;
const int joyYPin = 2;
const int shutterButtonPin = 12;

unsigned long lastMoveTime = 0;
bool lastButtonState = HIGH;
unsigned long buttonPressStartTime = 0;
bool longPressSent = false;

void showIdleMessage() {
    display.clearDisplay();
    display.setTextSize(1);
    display.setTextColor(SSD1306_WHITE);
    display.setCursor(0, 25);
    display.println("Not connected");
    display.display();
}

void showConnectedMessage() {
    display.clearDisplay();
    display.setTextSize(2);
    display.setTextColor(SSD1306_WHITE);
    display.setCursor(0, 20);
    display.println("Connected!");
    display.display();
}

class ServerCallbacks : public BLEServerCallbacks {
    void onConnect(BLEServer* pServer) {
        deviceConnected = true;
        showConnectedMessage();
    }
    void onDisconnect(BLEServer* pServer) {
        deviceConnected = false;
        showIdleMessage();
    }
};

void setup() {
    Serial.begin(115200);
    pinMode(shutterButtonPin, INPUT_PULLUP);
    
    delay(50);
    digitalRead(shutterButtonPin); // throwaway read to let pin stabilize
    lastButtonState = digitalRead(shutterButtonPin) == LOW ? LOW : HIGH; // sync to actual current state

    Wire.begin(15, 13); // SDA = GPIO13, SCL = GPIO12

    // OLED init/
    
    if (!display.begin(SSD1306_SWITCHCAPVCC, 0x3C)) {
        Serial.println("OLED init failed");
    }
    display.clearDisplay();
    showIdleMessage();

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


void showSavedMessage() {
    display.clearDisplay();
    display.setTextSize(2);
    display.setTextColor(SSD1306_WHITE);
    display.setCursor(0, 20);
    display.println("Saved!");
    display.display();
    delay(800);
    display.clearDisplay();
    display.display();
}

void sendModeCycle(int direction) {
    String payload = "mode_cycle:" + String(direction);
    pCharacteristic->setValue(payload.c_str());
    pCharacteristic->notify();
    Serial.println("Sent: " + payload);
}

void sendIntensityDelta(int delta) {
    String payload = "intensity_delta:" + String(delta);
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

    int xVal = analogRead(joyXPin);
    int yVal = analogRead(joyYPin);
    unsigned long now = millis();

    // Left/right cycles preset (debounced)
    if (now - lastMoveTime > 400) {
        if (xVal < 1000) {
            sendModeCycle(1);
            lastMoveTime = now;
        } else if (xVal > 3000) {
            sendModeCycle(-1);
            lastMoveTime = now;
        }
    }

    // Up/down sends an intensity delta
    if (now - lastMoveTime > 150) {
        if (yVal < 1000) {
            sendIntensityDelta(5);
            lastMoveTime = now;
        } else if (yVal > 3000) {
            sendIntensityDelta(-5);
            lastMoveTime = now;
        }
    }

    // Shutter button — short press = capture/retake, long press = save
    bool shutterPressed = digitalRead(shutterButtonPin) == LOW;

    if (shutterPressed && lastButtonState == HIGH) {
        buttonPressStartTime = now;
        longPressSent = false;
    }

    if (shutterPressed && !longPressSent && (now - buttonPressStartTime > 800)) {
        sendSave();
        showSavedMessage();
        longPressSent = true;
    }

    if (!shutterPressed && lastButtonState == LOW && !longPressSent) {
        sendShutter();
    }

    lastButtonState = shutterPressed ? LOW : HIGH;

    delay(50);
}