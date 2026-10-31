# Security policy

This project controls physical exercise equipment. Bugs that could make a belt move, speed up or keep running
unexpectedly are treated as security issues, alongside the usual software vulnerabilities.

## Supported versions

| Component | Supported |
|---|---|
| Go library, latest minor release | ✅ |
| Older releases | ❌ |

## Reporting a vulnerability

Please **do not open a public issue.** Report privately through GitHub:
[Security → Report a vulnerability](https://github.com/drymek/treadmill/security/advisories/new).

Include what you observed, the steps to reproduce it and, for treadmill behaviour, a probe log if you have one.

You can expect an acknowledgement within 7 days. We will keep you updated on the fix and credit you in the
advisory unless you prefer otherwise.

## Scope notes

- The treadmill's BLE interface has **no authentication**: anyone within radio range can connect and send
  commands while no other client is connected. This is a property of the device, not of this project, and cannot
  be fixed here. Turn the treadmill off or remove the safety key when it is not in use.
- Losing the Bluetooth connection does not stop the belt. This is documented device behaviour. Applications
  must not rely on the connection for safety.
