#include <Wire.h>
#include <Adafruit_GFX.h>
#include <Adafruit_SSD1306.h>
#include <PCF8574.h>

#define SCREEN_WIDTH 128
#define SCREEN_HEIGHT 64
Adafruit_SSD1306 display(SCREEN_WIDTH, SCREEN_HEIGHT, &Wire, -1);

PCF8574 pcf(0x20);

const int encoderCLK = 14;
const int encoderDT = 2;

int lastEncoded = 0;
int encoderPosition = 0;

unsigned long lastButtonCheck = 0;

void showOnDisplay(String line1, String line2 = "") {
    display.clearDisplay();
    display.setTextSize(2);
    display.setTextColor(SSD1306_WHITE);
    display.setCursor(0, 10);
    display.println(line1);
    if (line2 != "") {
        display.setTextSize(1);
        display.setCursor(0, 40);
        display.println(line2);
    }
    display.display();
    Serial.println(line1 + " " + line2);
}

void setup() {
    Serial.begin(115200);
    delay(500);
    Serial.println("=== Full Pin Test Starting ===");

    Wire.begin(13, 15);
    if (!display.begin(SSD1306_SWITCHCAPVCC, 0x3C)) {
        Serial.println("OLED: FAILED to init");
    } else {
        Serial.println("OLED: OK");
    }

    if (!pcf.begin()) {
        Serial.println("PCF8574: FAILED to init");
    } else {
        Serial.println("PCF8574: OK");
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

    showOnDisplay("Ready", "Test all inputs");
}

void readEncoder() {
    int msb = digitalRead(encoderCLK);
    int lsb = digitalRead(encoderDT);
    delayMicroseconds(50);
    int msb2 = digitalRead(encoderCLK);
    int lsb2 = digitalRead(encoderDT);

    if (msb != msb2 || lsb != lsb2) return; // unstable/bouncing, skip

    int encoded = (msb << 1) | lsb;
    int sum = (lastEncoded << 2) | encoded;

    if (sum == 0b1101 || sum == 0b0100 || sum == 0b0010 || sum == 0b1011) {
    encoderPosition--;
    showOnDisplay("ENCODER CCW", "pos: " + String(encoderPosition));
}
if (sum == 0b1110 || sum == 0b0111 || sum == 0b0001 || sum == 0b1000) {
    encoderPosition++;
    showOnDisplay("ENCODER CW", "pos: " + String(encoderPosition));
}

    lastEncoded = encoded;
}

void checkButtons() {
    static bool lastUp = HIGH, lastDown = HIGH, lastSelect = HIGH, lastExit = HIGH, lastEncClick = HIGH, lastShutter = HIGH;

    bool up = pcf.digitalRead(P0) == LOW;
    if (up && lastUp == HIGH) showOnDisplay("UP", "pressed");
    lastUp = up ? LOW : HIGH;

    bool down = pcf.digitalRead(P1) == LOW;
    if (down && lastDown == HIGH) showOnDisplay("DOWN", "pressed");
    lastDown = down ? LOW : HIGH;

    bool select = pcf.digitalRead(P2) == LOW;
    if (select && lastSelect == HIGH) showOnDisplay("SELECT", "pressed");
    lastSelect = select ? LOW : HIGH;

    bool exitBtn = pcf.digitalRead(P3) == LOW;
    if (exitBtn && lastExit == HIGH) showOnDisplay("EXIT", "pressed");
    lastExit = exitBtn ? LOW : HIGH;

    bool encClick = pcf.digitalRead(P4) == LOW;
    if (encClick && lastEncClick == HIGH) showOnDisplay("ENC CLICK", "pressed");
    lastEncClick = encClick ? LOW : HIGH;

    bool shutter = pcf.digitalRead(P5) == LOW;
    if (shutter && lastShutter == HIGH) showOnDisplay("SHUTTER", "pressed");
    lastShutter = shutter ? LOW : HIGH;
}

void loop() {
    readEncoder(); // every iteration, highest priority

    unsigned long now = millis();
    if (now - lastButtonCheck > 50) {
        lastButtonCheck = now;
        checkButtons();
    }
}