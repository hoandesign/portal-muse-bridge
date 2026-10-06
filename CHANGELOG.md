# Changelog

## 1.1.0 — 2026-10-07

### Added
- **Pixel home screen** in Game Boy style:
  - An 8-bit robot that dances, blinks and smiles. Tap it to make it jump, throw hearts and switch routines.
  - A pixel clock, date and weather, and a month calendar.
- **Ring notes as dialog boxes:** each note is typed out letter by letter above the robot, with its delivery status. The robot "talks" while it types.
- **Seven color themes:** Classic, Pocket, Ice, Sunset, Sakura, Virtual Boy and Matcha. Tap empty space to switch.
- **Weather** from Open-Meteo. The city is detected from your IP (geojs.io), or you can set it yourself.
- **Portal screensaver** (`HomeDreamService`):
  - remembers and restores the previous screensaver;
  - re-applies itself on boot, every 5 minutes, and at once when another app changes it.
- **Settings page** with tabs:
  - **Status:** the original status screen, now showing the webhook and Tailscale URLs.
  - **Home screen:** weather, clock, calendar, robot, note bubbles, theme, night dimming, burn-in shift.
  - **Screensaver:** on/off, keep awake, permission status.
- Bundled Press Start 2P and VT323 fonts (OFL). VT323 covers Vietnamese.
- Unit tests for the month grid and weather parsing.

### Fixed
- The screensaver now survives launchers that switch screensavers off or wake the device right after a screensaver starts (seen with Immortal launcher).

## 1.0.0 — 2026-10-07

- First public release:
  - the Portal pairs as a Muse community gadget (pairing v5) and keeps a Noise XX link to the Muse cloud;
  - `/ingest` accepts the Pebble Index Webhook (multipart), JSON or plain text; `/api/mcp` serves MCP;
  - README covers the webhook setup and a Tailscale guide for using it away from home.
