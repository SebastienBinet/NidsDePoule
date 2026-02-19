#!/bin/bash
#
# NidsDePoule - Build and install on Android device
#
# Usage:
#   ./build-and-install.sh              # Build debug APK only
#   ./build-and-install.sh install      # Build + install via USB
#   ./build-and-install.sh server URL   # Set server URL before building
#
# Prerequisites:
#   1. Java 17+ (JDK, not just JRE)
#   2. Android SDK with:
#      - Build tools 34.0.0
#      - Platform android-34
#      - (install via sdkmanager, see below)
#   3. ANDROID_HOME environment variable set
#   4. For 'install': USB debugging enabled + phone connected via USB
#
# Quick setup (if you don't have Android SDK):
#   See the instructions printed by this script when ANDROID_HOME is not set.
#

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo -e "${GREEN}=== NidsDePoule Android Build ===${NC}"
echo ""

# Check Java
if ! command -v java &> /dev/null; then
    echo -e "${RED}Error: Java not found. Install JDK 17+${NC}"
    echo "  macOS:   brew install openjdk@17"
    echo "  Ubuntu:  sudo apt install openjdk-17-jdk"
    exit 1
fi

JAVA_VER=$(java -version 2>&1 | head -1 | awk -F '"' '{print $2}' | cut -d. -f1)
echo -e "Java version: ${GREEN}$JAVA_VER${NC}"

# Check ANDROID_HOME
if [ -z "$ANDROID_HOME" ]; then
    echo ""
    echo -e "${RED}Error: ANDROID_HOME is not set.${NC}"
    echo ""
    echo "You need the Android SDK command-line tools (no Android Studio required)."
    echo ""
    echo -e "${YELLOW}=== Quick Install on macOS ===${NC}"
    echo ""
    echo "  # Option A: Homebrew (easiest)"
    echo "  brew install --cask android-commandlinetools"
    echo "  export ANDROID_HOME=\$HOME/Library/Android/sdk"
    echo ""
    echo "  # Option B: Manual download"
    echo "  # 1. Download from: https://developer.android.com/studio#command-line-tools-only"
    echo "  # 2. Unzip to ~/android-sdk/cmdline-tools/latest/"
    echo "  export ANDROID_HOME=\$HOME/android-sdk"
    echo ""
    echo "  # Then install required SDK packages:"
    echo "  \$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager \\"
    echo "    'platform-tools' \\"
    echo "    'platforms;android-34' \\"
    echo "    'build-tools;34.0.0'"
    echo ""
    echo "  # Add to your ~/.zshrc or ~/.bash_profile:"
    echo "  echo 'export ANDROID_HOME=\$HOME/android-sdk' >> ~/.zshrc"
    echo "  echo 'export PATH=\$ANDROID_HOME/platform-tools:\$PATH' >> ~/.zshrc"
    echo ""
    echo -e "${YELLOW}=== Quick Install on Linux ===${NC}"
    echo ""
    echo "  mkdir -p ~/android-sdk/cmdline-tools"
    echo "  cd /tmp"
    echo "  wget https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
    echo "  unzip commandlinetools-linux-*.zip"
    echo "  mv cmdline-tools ~/android-sdk/cmdline-tools/latest"
    echo "  export ANDROID_HOME=\$HOME/android-sdk"
    echo "  \$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager \\"
    echo "    'platform-tools' \\"
    echo "    'platforms;android-34' \\"
    echo "    'build-tools;34.0.0'"
    echo ""
    exit 1
fi

echo -e "ANDROID_HOME: ${GREEN}$ANDROID_HOME${NC}"

# Check required SDK components
MISSING=""
if [ ! -d "$ANDROID_HOME/platforms/android-34" ]; then
    MISSING="$MISSING platforms;android-34"
fi
if [ ! -d "$ANDROID_HOME/build-tools/34.0.0" ]; then
    MISSING="$MISSING build-tools;34.0.0"
fi

if [ -n "$MISSING" ]; then
    echo ""
    echo -e "${YELLOW}Missing SDK components. Installing...${NC}"
    SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
    if [ ! -x "$SDKMANAGER" ]; then
        echo -e "${RED}sdkmanager not found at $SDKMANAGER${NC}"
        echo "Install command-line tools first (see instructions above)"
        exit 1
    fi
    yes | $SDKMANAGER $MISSING
fi

# Handle server URL override
if [ "$1" = "server" ] && [ -n "$2" ]; then
    echo ""
    echo -e "Setting server URL to: ${GREEN}$2${NC}"
    # Update the debug server URL in build.gradle.kts
    sed -i.bak "s|\"http://10.0.2.2:8000\"|\"$2\"|" app/build.gradle.kts
    shift 2
fi

# Build
echo ""
echo -e "${YELLOW}Building debug APK...${NC}"
echo ""
./gradlew assembleDebug

APK_PATH="app/build/outputs/apk/debug/app-debug.apk"

if [ -f "$APK_PATH" ]; then
    APK_SIZE=$(du -h "$APK_PATH" | cut -f1)
    echo ""
    echo -e "${GREEN}Build successful!${NC}"
    echo -e "APK: ${GREEN}$APK_PATH${NC} ($APK_SIZE)"
else
    echo -e "${RED}Build failed - APK not found${NC}"
    exit 1
fi

# Install if requested
if [ "$1" = "install" ]; then
    echo ""
    ADB="$ANDROID_HOME/platform-tools/adb"
    if [ ! -x "$ADB" ]; then
        ADB=$(command -v adb 2>/dev/null)
    fi

    if [ -z "$ADB" ]; then
        echo -e "${RED}adb not found. Install platform-tools:${NC}"
        echo "  sdkmanager 'platform-tools'"
        exit 1
    fi

    DEVICES=$($ADB devices | grep -c "device$")
    if [ "$DEVICES" -eq 0 ]; then
        echo -e "${RED}No Android device connected.${NC}"
        echo ""
        echo "Connect your phone via USB and enable USB Debugging:"
        echo "  1. Settings > About Phone > tap 'Build Number' 7 times"
        echo "  2. Settings > Developer Options > enable 'USB Debugging'"
        echo "  3. Reconnect USB and accept the prompt on your phone"
        echo ""
        echo "Or use wireless debugging (Android 11+):"
        echo "  1. Settings > Developer Options > Wireless Debugging > enable"
        echo "  2. Tap 'Pair device with pairing code'"
        echo "  3. $ADB pair <ip>:<port>"
        echo "  4. $ADB connect <ip>:<port>"
        exit 1
    fi

    echo -e "${YELLOW}Installing on device...${NC}"
    $ADB install -r "$APK_PATH"
    echo ""
    echo -e "${GREEN}Installed! Look for 'NidsDePoule' on your phone.${NC}"
fi

# Restore build.gradle.kts if we modified it
if [ -f "app/build.gradle.kts.bak" ]; then
    mv app/build.gradle.kts.bak app/build.gradle.kts
fi

echo ""
echo -e "${YELLOW}--- What's next? ---${NC}"
if [ "$1" != "install" ]; then
    echo "  ./build-and-install.sh install          # Install via USB"
    echo ""
    echo "  Or transfer the APK to your phone:"
    echo "    $APK_PATH"
fi
echo ""
echo "  To point to your server:"
echo "  ./build-and-install.sh server http://YOUR_IP:8000 install"
echo ""
