# CubClone docs

## screenshots/

Real-device UI captures taken during development and verification. File names keep
the original capture prefix:

| Prefix | Source |
|---|---|
| `esp-*`, `desk-*`, `new-ui*`, `settings-*`, `theme-*`, `ui-*`, `v14-*` | UI iterations on a test phone |
| `redmi-*` | second test device, Android 16 |
| `open-icon*`, `reuse*`, `order-check`, `shot-*` | single-feature verification shots |

## 同类项目调研.md

Survey of how app cloning is implemented in the wild: work profiles
(Island / Shelter / Insular), native multi-user (the route this project takes),
and virtualization engines (VirtualApp / BlackBox).

## Not in this repository

Reference material gathered while studying OEM implementations — original APKs,
decompiled output, UI dumps and firmware — is **not** distributed here; it belongs
to its vendors. Those paths are listed in `.gitignore` and stay local only.
