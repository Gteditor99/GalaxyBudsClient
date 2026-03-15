# Buds2 Pro Firmware Updater

A standalone Android app for loading firmware onto Samsung Galaxy Buds2 Pro earbuds.

This app replicates the firmware update functionality of [GalaxyBudsClient](https://github.com/ThePBone/GalaxyBudsClient), targeting exclusively the Galaxy Buds2 Pro (SM-R510).

## Features

- **Bluetooth SPP connection** to Galaxy Buds2 Pro using the dedicated UUID
- **Sideload firmware** from local `.bin` files
- **Download firmware** from the GalaxyBudsClient firmware server
- **FOTA protocol** implementation with fragmented message support
- **Real-time progress** tracking with segment/offset details
- **Model validation** - verifies SM-R510 pattern in firmware binaries

## Protocol Details

- **SPP UUID**: `2e73a4ad-332d-41fc-90e2-16bef06523f2`
- **Message format**: Non-legacy header (SOM `0xFD`, EOM `0xDD`)
- **CRC**: CRC16-CCITT checksums on all messages
- **MTU**: Negotiated with device, capped at 650 bytes
- **Firmware magic**: `0xCAFECAFE`

## Requirements

- Android 8.0 (API 26) or higher
- Bluetooth Classic support
- Galaxy Buds2 Pro paired via system Bluetooth settings

## Building

```bash
cd BudsFirmwareUpdater
./gradlew assembleDebug
```

## Usage

1. Pair your Galaxy Buds2 Pro in Android Bluetooth settings
2. Open the app and select your earbuds from the dropdown
3. Tap **Connect** to establish an SPP connection
4. Either **Sideload** a firmware `.bin` file or **Check for Updates** from the server
5. Review the firmware details and tap **Install Firmware**
6. Wait for the transfer to complete - do not disconnect the earbuds

## Architecture

```
com.galaxybuds.firmwareupdater/
  bluetooth/     - Bluetooth SPP client (RFCOMM connection, read/write)
  firmware/      - Firmware binary parsing, transfer manager, remote client
  protocol/      - SPP message encode/decode, CRC16, FOTA message handlers
  ui/            - Main activity with connection, firmware, and transfer UI
```
