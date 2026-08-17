# Dennis.Phostop Android Printer App

This Android wrapper opens the live Dennis.Phostop POS and exposes a native classic Bluetooth SPP bridge for ESC/POS receipt printers such as MRBOSS E2000.

## Redmi Pad 2 setup

1. Install the latest `Dennis-Phostop-POS.apk` from the GitHub Android release.
2. Allow **Nearby devices** when Android asks.
3. Open Redmi **Settings > Bluetooth** and pair `MRBOSS E2000` once.
4. Open the POS app and log in as manager.
5. Open **Setup > Printer Setup**.
6. Press **Refresh Paired Devices**, choose MRBOSS E2000, then press **Connect & Test E2000**.
7. After payment, press **Android Print** on the receipt.

After the first successful connection, the app remembers that printer and reconnects it automatically when the POS starts or returns to the foreground. If the printer is off or busy, ordering remains available and **Connect & Test E2000** can be used to reconnect manually.

The app uses the live POS URL, so Firebase sales, KDS, staff clocking, and menu data remain synchronized with the other devices.
