#include <Wire.h>
#include <Adafruit_GFX.h>
#include <Adafruit_SSD1306.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>
#include <PCF8574.h>

#define SCREEN_WIDTH 128
#define SCREEN_HEIGHT 64
Adafruit_SSD1306 display(SCREEN_WIDTH, SCREEN_HEIGHT, &Wire, -1);

PCF8574 pcf(0x20);

#define SERVICE_UUID        "4fafc201-1fb5-459e-8fcc-c5c9c331914b"
#define CHARACTERISTIC_UUID "beb5483e-36e1-4688-b7f5-ea07361b26a8"
#define DISPLAY_CHARACTERISTIC_UUID "a1b2c3d4-1234-5678-9abc-def012345678"

BLEServer* pServer = NULL;
BLECharacteristic* pCharacteristic = NULL;
BLECharacteristic* pDisplayCharacteristic = NULL;
bool deviceConnected = false;

const int encoderCLK = 14;
const int encoderDT = 2;

int lastEncoded = 0;
unsigned long lastButtonCheck = 0;

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

void showReceivedText(String text) {
    display.clearDisplay();
    display.setTextSize(1);
    display.setTextColor(SSD1306_WHITE);
    display.setCursor(0, 0);
    display.println(text);
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

class DisplayCallbacks : public BLECharacteristicCallbacks {
    void onWrite(BLECharacteristic *pChar) {
        String value = pChar->getValue().c_str();
        showReceivedText(value);
    }
};

void setup() {
    Serial.begin(115200);

    Wire.begin(13, 15); // OLED SDA, SCL

    if (!display.begin(SSD1306_SWITCHCAPVCC, 0x3C)) {
        Serial.println("OLED init failed");
    }

    if (!pcf.begin()) {
        Serial.println("PCF8574 not found!");
    } else {
        Serial.println("PCF8574 OK");
    }
    pcf.pinMode(P0, INPUT_PULLUP); // Up
    pcf.pinMode(P1, INPUT_PULLUP); // Down
    pcf.pinMode(P2, INPUT_PULLUP); // Select
    pcf.pinMode(P3, INPUT_PULLUP); // Exit
    pcf.pinMode(P4, INPUT_PULLUP); // Encoder click
    pcf.pinMode(P5, INPUT_PULLUP); // Shutter

    pinMode(encoderCLK, INPUT_PULLUP);
    pinMode(encoderDT, INPUT_PULLUP);
    delay(50);
    int msb = digitalRead(encoderCLK);
    int lsb = digitalRead(encoderDT);
    lastEncoded = (msb << 1) | lsb;

    showIdleMessage();

    BLEDevice::init("CamStick");
    pServer = BLEDevice::createServer();
    pServer->setCallbacks(new ServerCallbacks());

    BLEService *pService = pServer->createService(SERVICE_UUID);

    pCharacteristic = pService->createCharacteristic(
                      CHARACTERISTIC_UUID,
                      BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_NOTIFY
                    );
    pCharacteristic->addDescriptor(new BLE2902());

    pDisplayCharacteristic = pService->createCharacteristic(
                      DISPLAY_CHARACTERISTIC_UUID,
                      BLECharacteristic::PROPERTY_WRITE
                    );
    pDisplayCharacteristic->setCallbacks(new DisplayCallbacks());

    pService->start();

    BLEAdvertising *pAdvertising = BLEDevice::getAdvertising();
    pAdvertising->addServiceUUID(SERVICE_UUID);
    pAdvertising->setScanResponse(false);
    pAdvertising->setMinPreferred(0x0);
    BLEDevice::startAdvertising();

    Serial.println("BLE advertising started");
}

void sendNav(String direction) {
    String payload = "nav:" + direction;
    pCharacteristic->setValue(payload.c_str());
    pCharacteristic->notify();
    Serial.println("Sent: " + payload);
}

void sendEncoderDelta(int delta) {
    String payload = "encoder_delta:" + String(delta);
    pCharacteristic->setValue(payload.c_str());
    pCharacteristic->notify();
    Serial.println("Sent: " + payload);
}

void sendSelect() {
    pCharacteristic->setValue("select:1");
    pCharacteristic->notify();
    Serial.println("Sent: select:1");
}

void sendExit() {
    pCharacteristic->setValue("exit:1");
    pCharacteristic->notify();
    Serial.println("Sent: exit:1");
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

void readEncoder() {
    int msb = digitalRead(encoderCLK);
    int lsb = digitalRead(encoderDT);
    delayMicroseconds(50);
    int msb2 = digitalRead(encoderCLK);
    int lsb2 = digitalRead(encoderDT);

    if (msb != msb2 || lsb != lsb2) return; // bouncing, skip

    int encoded = (msb << 1) | lsb;
    int sum = (lastEncoded << 2) | encoded;

    if (sum == 0b1101 || sum == 0b0100 || sum == 0b0010 || sum == 0b1011) {
        sendEncoderDelta(-1);
    }
    if (sum == 0b1110 || sum == 0b0111 || sum == 0b0001 || sum == 0b1000) {
        sendEncoderDelta(1);
    }

    lastEncoded = encoded;
}

void checkButtons() {
    static bool lastUp = HIGH, lastDown = HIGH, lastSelect = HIGH, lastExit = HIGH, lastEncClick = HIGH, lastShutter = HIGH;
    static unsigned long shutterPressStart = 0;
    static bool longPressSent = false;

    bool up = pcf.digitalRead(P0) == LOW;
    if (up && lastUp == HIGH) sendNav("up");
    lastUp = up ? LOW : HIGH;

    bool down = pcf.digitalRead(P1) == LOW;
    if (down && lastDown == HIGH) sendNav("down");
    lastDown = down ? LOW : HIGH;

    bool select = pcf.digitalRead(P2) == LOW;
    if (select && lastSelect == HIGH) sendSelect();
    lastSelect = select ? LOW : HIGH;

    bool exitBtn = pcf.digitalRead(P3) == LOW;
    if (exitBtn && lastExit == HIGH) sendExit();
    lastExit = exitBtn ? LOW : HIGH;

    bool encClick = pcf.digitalRead(P4) == LOW;
    // reserved for future use
    lastEncClick = encClick ? LOW : HIGH;

    bool shutter = pcf.digitalRead(P5) == LOW;
    unsigned long now = millis();
    if (shutter && lastShutter == HIGH) {
        shutterPressStart = now;
        longPressSent = false;
    }
    if (shutter && !longPressSent && (now - shutterPressStart > 800)) {
        sendSave();
        showSavedMessage();
        longPressSent = true;
    }
    if (!shutter && lastShutter == LOW && !longPressSent) {
        sendShutter();
    }
    lastShutter = shutter ? LOW : HIGH;
}

void loop() {
    if (!deviceConnected) {
        delay(200);
        return;
    }

    readEncoder(); // every iteration, no delay

    unsigned long now = millis();
    if (now - lastButtonCheck > 50) {
        lastButtonCheck = now;
        checkButtons();
    }
}