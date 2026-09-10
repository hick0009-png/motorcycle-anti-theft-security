# Architecture Decision Record
## Title: FRP Lock via DeviceAdmin
- **Status**: Superseded — never shipped, code removed 2026-09-11
- **Original decision**: Use Android DeviceAdmin API to enable Factory Reset Protection lock
- **Original rationale**: Prevents thief from factory-resetting the device to disable the anti-theft system
- **Why it was withdrawn**: The receiver (`security/DeviceAdminController`) and its manager (`security/AntiTamperKioskManager`) were written and registered in the manifest, but nothing in the app ever fired `ACTION_ADD_DEVICE_ADMIN` to ask the owner for the grant. Without that grant the receiver can never be enabled, so the whole path was unreachable — the ADR claimed protection the build did not have. Kiosk/lock-task and `setGlobalSetting(ADB_ENABLED, 0)` on top of it need Device *Owner*, which requires provisioning a fresh or factory-reset device: out of reach for an app the rider installs from a store.
- **Consequences**: There is no factory-reset protection. Deterrence rests on what still works after a reset — the Telegram incident trail and black box already written off-device before the reset lands. Revisit only with a real answer for how the owner grants admin, and expect the uninstall-blocking side effect to need its own disarm path.
