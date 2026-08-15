# Architecture Decision Record
## Title: FRP Lock via DeviceAdmin
- **Status**: Accepted
- **Decision**: Use Android DeviceAdmin API to enable Factory Reset Protection lock
- **Rationale**: Prevents thief from factory-resetting the device to disable the anti-theft system
- **Consequences**: Requires user to grant Device Admin permissions; app cannot be easily uninstalled
