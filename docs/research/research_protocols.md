# Reaching every ECU through an ELM327-class adapter: a brand-by-brand protocol reference

Compiled 2026-10-16 from public sources only: the ELM327 and STN datasheets, Wikipedia, open-source projects (comma.ai opendbc, EdiabasLib/Deep OBD, DDT4ALL/pyren, arduino-psa-diag, OBDb, Volvo-VIDA, MQB-sniffer, gocan) and forums. No closed apps were decompiled.

**Confidence tags used throughout**
- **[DOC]**: stated directly in a primary or authoritative source, such as a datasheet, source code of a working open-source tool, or a captured trace published online.
- **[COMM]**: consistent community or forum knowledge, or a single secondary source.
- **[INF]**: my inference from the sources, or general knowledge I could not tie to a source. Verify on a car.
- **[UNC]**: uncertain or conflicting. Treat it as a hypothesis for discovery scans, not as a constant.

---

## 0. Executive summary: what probably broke in the field tests

1. **BMW F01, where only OBD answered.** BMW D-CAN (E-series from about 2007, and all F/G) does **not** use normal 11-bit ISO-TP. The tester always transmits on **CAN ID 0x6F1**. The **first data byte is the target ECU address** (ISO-TP *extended addressing*). Each ECU answers on **0x600 + its address**, with first byte **0xF1**. A scan of 0x7E0–0x7E7 and the other 0x7xx pairs finds only the OBD-legislated responder (DME via 0x7E0/0x7E8). With an ELM327 you need `ATSH6F1`, `ATCEA <addr>`, `ATCRA 6<addr>`, and a manual flow control that carries the address byte (§3). [DOC: EdiabasLib EdElmInterface.cs; ELM327 DS p.61]
2. **Older Audi A8 (D3/PQ-MLB era), where only engine and gearbox answered.** On pre-MQB VAG, only the emissions-relevant ECUs speak ISO-TP on 0x7E0/0x7E1. Everything else is behind **VW TP2.0**: a channel setup on 0x200, then dynamic IDs, ACK-based segmentation, and KWP2000 inside. This needs `ATCAF0` raw frames and an app-side TP2.0 state machine (§2.3). [DOC: jazdw TP2.0 write-up]
3. **Response-ID offsets are not always +8.** If the app sets the receive filter to request+8, it silently misses whole brands:
   - VAG MQB UDS: **+0x6A** (0x714 to 0x77E).
   - PSA: about **−0x20** (0x6A8 to 0x688, 0x752 to 0x652).
   - Renault/Nissan/Dacia/Lada-Renault: **+0x20** (0x745 to 0x765).
   - GM GMLAN: **+0x400** (0x241 to 0x641).
   - Mitsubishi: at least one module at **+1** (0x784 to 0x785).
   - Toyota and BMW: extended addressing (0x750+sub-address, 0x6F1+address).

   Discovery should **open the receive filter to a range (`ATCF`/`ATCM`) with headers on (`ATH1`)** and learn the pair. [DOC: opendbc VOLKSWAGEN_RX_OFFSET=0x6a, GM_RX_OFFSET=0x400, NISSAN_RX_OFFSET=0x20; PSA ECU_LIST; DDT4ALL tables]
4. **Automatic flow control is only safe for standard +8 pairs.** For any non-OBD pair, set `ATFCSH <requestID>`, `ATFCSD 30 00 00` (or `<ext> 30 00 00`), and `ATFCSM1`. DDT4ALL and EdiabasLib both always do this. [DOC: DDT4ALL elm.py `set_can_addr`; EdiabasLib]
5. **Some buses are not on pins 6/14 at all.**
   - Ford MS-CAN (pins 3/11, 125 kbit/s).
   - GM single-wire CAN (pin 1, 33.3 kbit/s).
   - Volvo low-speed CAN.
   - K-line-only modules.

   A stock ELM327 cannot reach them. Ford needs a hardware-switched clone; Ford and GM work on STN11xx/21xx/22xx (OBDLink) chips. [DOC: STN FRPM; FORScan forum]
6. **"ELM327 v1.5" does not exist as a genuine Elm product.** Clones copy v1.4 functionality. Commands added in v2.0 (4-byte `ATSH wwxxyyzz`, `ATCRA` with X wildcards) are unavailable on them. Use `ATCP`+`ATSH xxyyzz` and `ATCF`/`ATCM` instead. `ATCEA` (v1.4) and `ATFC*` (v1.1) are usually present on decent PIC18F25K80 clones, but **probe every command and check for `?`**. [DOC: Wikipedia ELM327; ELM327 DS version history]

---

## 1. Transport and ELM327 fundamentals (cross-brand)

### 1.1 ELM327 protocol numbers (`ATSP h`)
[DOC: ELM327 datasheet, https://www.elmelectronics.com/wp-content/uploads/2016/07/ELM327DS.pdf; https://en.wikipedia.org/wiki/ELM327]

| h | Protocol |
|---|---|
| 1 | SAE J1850 PWM 41.6k |
| 2 | SAE J1850 VPW 10.4k |
| 3 | ISO 9141-2 (5-baud init) |
| 4 | ISO 14230-4 KWP (5-baud init) |
| 5 | ISO 14230-4 KWP (fast init) |
| 6 | ISO 15765-4 CAN 11-bit 500k |
| 7 | ISO 15765-4 CAN 29-bit 500k |
| 8 | ISO 15765-4 CAN 11-bit 250k |
| 9 | ISO 15765-4 CAN 29-bit 250k |
| A | SAE J1939 29-bit 250k |
| B | USER1 CAN (set by `ATPB xx yy`; default 11-bit, 125k) |
| C | USER2 CAN (default 11-bit, 50k) |

**`ATPB xx yy`** (v1.4+) sets protocol B options. `xx` is the PP 2C option byte:

| Bit | Meaning |
|---|---|
| b7 | 1 = 11-bit TX ID, 0 = 29-bit |
| b6 | 1 = variable DLC, 0 = fixed 8 |
| b5 | 1 = receive both 11- and 29-bit |
| b4 | baud ×8/7 |
| b2..b0 | 000 = no formatting, 001 = ISO 15765-4, 010 = J1939 |

`yy` is the baud divisor: rate = 500/yy kbit/s. [DOC: ELM327 DS, PP 2C/2D table]

Examples:
- `ATPB C0 01`: 11-bit, variable DLC, raw, 500k. EdiabasLib uses this for BMW. [DOC]
- `ATPB A1 04`: 11-bit, fixed DLC 8, ISO 15765, 125k. Suitable for Ford MS-CAN behind a hardware switch. [INF: derived from the bit table]

### 1.2 CAN-related AT commands and minimum genuine-ELM version
[DOC: ELM327 DS "Version History" pp.90–91; the same table is reproduced in DDT4ALL `constants.py`, https://github.com/cedricp/ddt4all/blob/master/src/ddt4all/core/elm/constants.py]

| Command | Purpose | Since |
|---|---|---|
| `ATSH xyz` | 11-bit CAN ID | 1.0 |
| `ATSH xxyyzz` | 3 header bytes. For 29-bit: the low 24 bits (`DA 10 F1`) | 1.0 |
| `ATCP hh` | Top 5 bits of a 29-bit ID (priority). Default `18` | 1.0 |
| `ATSH wwxxyyzz` | Full 29-bit ID in one command | **2.0** (absent on most "v1.5" clones) |
| `ATCAF0/1` | CAN auto-formatting (PCI insertion and removal) off/on | 1.0 |
| `ATCF hhh`/`hhhhhhhh`, `ATCM …` | Receive filter and mask | 1.0 |
| `ATCFC0/1` | Automatic flow control off/on | 1.0 |
| `ATFC SH`, `ATFC SD`, `ATFC SM 0/1/2` | User-defined flow control: header, data, mode | 1.1 |
| `ATAT0/1/2`, `ATSR` | Adaptive timing; set receive address | 1.2 |
| `ATIIA hh`, `ATKW0` | 5-baud init address; disable key-word check | 1.2 |
| `ATCRA hhh`/`hhhhhhhh` | Receive-address filter | 1.3 |
| `ATD1`, `ATV0/1`, `ATRTR` | Show DLC; variable DLC; send RTR | 1.3 |
| **`ATCEA hh`**, `ATCEA` | CAN extended address on/off | **1.4** |
| `ATPB xx yy`, `ATFI`, `ATSI`, `ATTA hh`, `ATIB48` | Protocol B params; manual inits; tester address; 4800 baud | 1.4 |
| `ATCSM0/1` | Silent monitoring off/on | 1.4b |
| `ATCRA` with X wildcards, `ATSH` 4-byte | | 2.0 |
| `ATCTM1/5` | CAN timer multiplier | 2.1 |

**Key semantics** [DOC: ELM327 DS pp.13–17, 40, 60–61]:
- **CAF0.** "You must provide all of the required data bytes exactly as they are to be sent". The ELM327 adds no PCI byte but still pads to 8 bytes unless variable DLC (`ATV1` or PB b6) is set. This is needed for TP2.0, Volvo VIDA, and any manual ISO-TP.
- **H1 with CAF1.** With headers on, received data is shown much like CAF0, as raw frames. Sending still auto-formats.
- **`ATCEA hh`.**
  - The ELM327 inserts `hh` as the first data byte of every CAN frame it sends.
  - It adds a receive filter that passes only frames whose **first byte equals the Tester Address**. That address defaults to F1 and is changed with `ATTA`.
  - Single frames carry at most 6 payload bytes.
- **Flow control under extended addressing.** Automatic flow control does not insert the extended byte. The datasheet example uses `ATFC SH 7B0`, `ATFC SD 04 30 FF 00`, `ATFC SM1`, `ATCEA 04`, `ATSP B`, `ATBI`.
- **FC modes.** Mode 0 is fully automatic. Mode 1: the user sets ID and data. Mode 2: the user sets data, and the ID is taken from the first frame.
- **ISO 15765-4 29-bit header.** It is `[prio 18][type DA physical / DB functional][target][source F1]`. Functional OBD is `18 DB 33 F1`.

**Clones** [DOC: https://en.wikipedia.org/wiki/ELM327]:
- "Elm Electronics never made a v1.5." Clones copy v1.4 and "may falsely report the version number".
- The vagscan project reports that `ATMA`, `ATCAF0` and `ATCRA` are "commonly missing" on cheap clones, and that v1.5-labelled clones are usually better than "v2.1" ones. [COMM: https://github.com/cquiroga-arch/leontest-01]
- Deep OBD recommends ELM327 "1.4b, 1.5 and origin 2.1" for BMW D-CAN. [DOC: https://github.com/uholeschak/ediabaslib/blob/master/docs/Deep_OBD_for_BMW_and_VAG.md]
- **Action:** probe-and-cache capabilities per adapter, for example `ATCEA` then `ATCEA` off, `ATFCSM0`, `ATCP18`, `ATPB C0 01`, and `ATV1`. Treat a `?` answer as "unsupported".

### 1.3 STN (OBDLink) chips: the only common way to reach MS-CAN and SW-CAN
[DOC: OBDLink FRPM, https://www.scantool.net/scantool/downloads/98/stn11xx21xx_frpm-c.pdf; https://www.obdsol.com/solutions/chips/stn1170/]

- `STP 33/34`: HS-CAN ISO 15765, 11- or 29-bit, 500k. `35/36`: 250k.
- **`STP 51–54`: MS-CAN on OBD pins 3/11**, 125k. 53 = ISO 15765 11-bit, 54 = 29-bit, 51/52 = raw ISO 11898.
- **`STP 61–64`: GM SW-CAN on pin 1**, 33.3k.
- `STPBR` changes the baud rate. DDT4ALL uses `STP 53` plus `STPBR 500000` for re-wired adapters. [DOC: DDT4ALL elm.py]
- Chips with MS-CAN: STN1170/2120 (obsolete), STN2232 (OBDLink EX), STN2255/2256 (OBDLink MX+). Chips with SW-CAN: STN1170/2120/2255/2256.

Ford MS-CAN with a non-STN ELM327 needs a "modified" clone with a hardware HS/MS switch. These are usually PIC18F25K80 boards that physically swap pins 6/14 with 3/11. [COMM: https://forum.forscan.org/viewtopic.php?p=4]

### 1.4 ISO-TP addressing modes you will meet
[DOC: https://en.wikipedia.org/wiki/ISO_15765-2; ELM327 DS]

| Mode | Where used | ELM327 recipe |
|---|---|---|
| 11-bit normal (pairs) | OBD, VAG MQB, Ford, Toyota (most), Hyundai/Kia, PSA, Renault/Nissan, Subaru, Mazda, Chrysler (older), Chinese brands | `ATSP6`, `ATSH <req>`, `ATCRA <resp>`, manual FC |
| 29-bit normal-fixed `18DA<TA><SA>` | Honda, Renault/Nissan (newer ECUs), Chrysler/Stellantis (newer), some Hyundai/GM/Ford | `ATSP7`, `ATCP18`, `ATSH DA<TA>F1`, `ATCRA 18DAF1<TA>`, `ATFCSH 18DA<TA>F1` |
| 11-bit **extended** (first byte = target) | **BMW** (0x6F1); **Toyota** 0x750 sub-addressed ECUs; GMLAN functional (0x101 + 0xFE) | `ATSH`, `ATCEA <addr>`, `ATCRA`, `ATFCSD <addr> 30 00 00`, `ATFCSM1` |
| VW TP2.0 (non-ISO, dynamic IDs) | VAG PQ/MLB/older (about 2003–2012), non-OBD modules | `ATCAF0`, raw frames, app-side state machine |
| Volvo VIDA raw (29-bit, non-ISO-TP) | Volvo P1/P2/P3 (about 2004–2015) | `ATCAF0`, `ATCP00`, `ATSH0FFFFE`, `ATCF 00400000`, `ATCM 1FFF0000` |
| K-line ISO 14230 KWP2000 | Lada (Январь 7.x, M7.9.7, M73/74), older European/Asian, Renault ISO8 | `ATSP5` (fast) or `4` (5-baud), `ATIB10`, `ATSH 81 <addr> F1`, `ATWM … 3E` |
| K-line proprietary (VAG KW1281, BMW DS2, Honda, Subaru SSM-K, Mercedes per-module K) | Pre-2005 | **Not possible with stock ELM327** |

### 1.5 Diagnostic services for reading DTCs (read-only)
[DOC: https://en.wikipedia.org/wiki/Unified_Diagnostic_Services; ISO 14229 summaries; brand sources below]

| Protocol | Request | Positive response | Entry format |
|---|---|---|---|
| OBD-II | `03`, `07`, `0A` | `43/47/4A` | 2-byte SAE J2012 |
| UDS | `19 02 <mask>`. Use FF, or 0C for pending+confirmed, 08 for confirmed only, 09 per PSA practice, AF/2F per OEM | `59 02 <availMask>` + n × `[DTC_H DTC_M DTC_L status]` | 3-byte DTC + status |
| UDS | `19 01 <mask>` (count) / `19 0A` (supported) | `59 01 <avail> <fmt> <cnt_H cnt_L>` | Format byte 00 = ISO 15031-6 (SAE), 01 = ISO 14229-1 (OEM), 02 = J1939, 04 = ISO 11992-4 [INF] |
| KWP2000 | `18 02 FF 00` (readDTCByStatus, all groups). Variants: `18 00 FF 00` (Lada, Mitsubishi), `18 02 FF FF` (BMW E) | `58 n` + n × `[H L status]` | 2-byte + status |
| KWP2000 | `17 FF 00` (readStatusOfDTC; PSA, Renault) | `57 …` | 2-byte + status |
| GMLAN | `A9 81 <mask>` (readStatusOfDTCByStatusMask; e.g. mask 0x12) | `E9`/UUDT frames (§6) | 2-byte + FTB + status |
| Volvo VIDA | `AE 11` | `EE 11` + 2-byte DTCs | 2-byte |
| VAG KW1281 | block title `07` (on K-line, not ELM) | — | 5-digit decimal |

**UDS status byte** (ISO 14229-1) [INF, standard knowledge]:

| Bit | Meaning |
|---|---|
| b0 | testFailed |
| b1 | testFailedThisOperationCycle |
| b2 | pending |
| b3 | confirmed |
| b4 | testNotCompletedSinceLastClear |
| b5 | testFailedSinceLastClear |
| b6 | testNotCompletedThisOperationCycle |
| b7 | warningIndicatorRequested |

**Sessions and security just to read codes.** No security access is needed anywhere I found. A session is usually not needed either: the default session supports 0x19 and 0x18. Exceptions and habits:
- Some Mitsubishi and Nissan KWP ECUs want a session first (`10 92`; `10 81`/`10 C0`/`10 DA`).
- PSA KWP needs `81` (StartCommunication) or `10 C0`.
- Renault tools send `10 C0`.
- VAG TP2.0 tools send `10 89`.
- BMW F-series and VAG MQB answer 0x19 in the default session. OBDeleven sends `10 03` first. [DOC: MQB-sniffer trace]

**Rule:** try the read in the default session. On NRC 0x7F/0x11/0x12/0x22/0x7E/0x7F, open the OEM-typical session and retry. Send tester present (`3E 00`, or `3E 80` for no response) only while you hold a non-default session. Handle **NRC 0x78** (response pending) by waiting up to P2* (5 s). ELM327 v2.1 does this itself; clones may not. [DOC: ELM DS v2.1 feature list; klartext README]

**Never send** these during a "read" scan: `11` (reset), `14` (clear), `27` (security), `28` (communication control), `85` (DTC setting), `31` (routines), `2E`/`3B` (writes). Several sources warn that `14FF00` clears codes. [COMM: drive2 Mitsubishi post]

### 1.6 DTC display formats

| Format | How to display it |
|---|---|
| SAE 2-byte (OBD, KWP-on-CAN Asian, GMLAN, Ford pre-UDS) | Top 2 bits = P/C/B/U. Then 14 bits as 4 hex nibbles, e.g. `E1 03` = U2103. [DOC: gocan `DecodeDTC`, https://pkg.go.dev/github.com/roffe/gocan/pkg/gmlan] |
| UDS 3-byte with FTB (format 00) | First 2 bytes = SAE code, byte 3 = Failure Type Byte (SAE J2012-DA). Display as `P0300-00` or similar. |
| UDS 3-byte, OEM-defined (format 01) | **BMW F/G** shows the 24-bit value as 6 hex digits. Your "130508" from the BMW DME is exactly that: a raw 3-byte UDS DTC printed in hex. [INF, consistent with klartext: "the raw DTC is 3 bytes … matches ISTA's 24-bit fault code"] |
| BMW E-series KWP | 2-byte hex, e.g. `2A82`. [COMM] |
| VAG | KW1281 and KWP2000 show **5-digit decimal** VAG codes. UDS modules show 3-byte DTCs that VCDS/ODIS map to VAG numbers ("the number of digits may vary" on UDS modules). P-codes appear as a secondary line, "if it exists". [DOC: https://www.ross-tech.com/vag-com/tour/dtc_screen.html] Example: KWP value 0x412C = 16684 = P0300, i.e. 16384 + the P-code read as a decimal number. [INF: well-known VAG mapping; verify] |
| Volvo VIDA | 2-byte module-specific, shown as `<module>-<hex>`. [DOC: OBDb issue] |

---

## 2. VAG (VW, Audi, Škoda, SEAT, Cupra)

### 2.1 Generations and transports
| Era/platform | Engine and gearbox | All other modules | Protocol |
|---|---|---|---|
| Pre-about-2000 (A4 B5, Golf 3/4 early) | K-line | K-line | **KW1281**, 5-baud init per module address. ELM327 cannot do this. Needs replacement firmware or a KKL cable. [DOC: Deep OBD supports "KWP1281" only with replacement firmware] |
| About 2000–2004 | K-line KWP2000 | K-line KW1281/KWP2000 | Same |
| PQ35/PQ46/MLB/D3 (about 2004–2012/13): Golf 5/6, Passat B6/B7, A3 8P, A4 B8, **A8 D3/D4 early** | ISO-TP 0x7E0/0x7E8, 0x7E1/0x7E9 (KWP2000 or UDS) | **VW TP2.0** on the diagnostic CAN via the gateway, with KWP2000 inside | Your A8 symptom matches this. [DOC: jazdw; user field test] |
| **MQB/MLBevo/MEB** (2012/13+) | UDS 11-bit | **UDS 11-bit, response = request + 0x6A** | [DOC: opendbc `VOLKSWAGEN_RX_OFFSET = 0x6a`; MQB-sniffer 0x714 to 0x77E] |
| MQB 2020+ with SFD | Same | Same; SFD gates writes/coding | [UNC: reading DTCs is reportedly not blocked by SFD] |

### 2.2 MQB UDS address table (11-bit, response = request + 0x6A)

| VAG addr | Module | Request | Response | Confidence |
|---|---|---|---|---|
| 01 | Engine | 0x7E0 | 0x7E8 | [DOC opendbc] (OBD pair, +8) |
| 02 | Transmission | 0x7E1 | 0x7E9 | [DOC opendbc; MQB-sniffer] |
| 15 | Airbag | 0x715 | 0x77F | [DOC opendbc 0x715 + 0x6A] |
| 44 | Steering assist (EPS) | 0x712 | 0x77C | [DOC opendbc] |
| 13 | ACC radar | 0x757 | 0x7C1 | [DOC opendbc] |
| A5 | Front camera | 0x74F | 0x7B9 | [DOC opendbc extra_ecus] |
| 17 | Instrument cluster | 0x714 | 0x77E | [DOC MQB-sniffer trace] |
| 19 | Gateway | 0x710 | 0x77A | [COMM/UNC] |
| 03 | ABS/ESC | 0x713 | 0x77D | [UNC] |
| 09 | Central electrics (BCM) | 0x70E | 0x778 | [UNC] |
| 08 | HVAC | 0x746 | 0x7B0 | [UNC] |
| 16 | Steering column electronics | 0x70C | 0x776 | [UNC] |
| 5F | Information electronics (head unit) | 0x773 | 0x7DD | [UNC] |

Sources: https://raw.githubusercontent.com/commaai/opendbc/master/opendbc/car/volkswagen/values.py, …/volkswagen/fingerprints.py, https://github.com/mrfixpl/MQB-sniffer

- **Do not hardcode MQB IDs.** Sweep request IDs 0x700–0x7DF. Accept a response on request+0x6A **or** request+8, by opening the filter with `ATCF 700`/`ATCM 700` and `ATH1`.
- **Padding:** the tester pads with 0x55 and ECUs pad with 0xAA, for example `714 8 02 10 03 55 55 55 55 55` and `77E 8 06 50 03 00 32 00 C8 AA`. ELM327 padding (0x00 by default) is accepted by MQB in practice. [DOC: MQB-sniffer; INF on acceptance]
- **Gateway installation list:** UDS `22 F1 9E`/`22 04 xx`-style DIDs on 0x710 would give the authoritative module list. [UNC: exact DIDs not found publicly]

**ELM327 sequence (MQB cluster example)**
```
ATZ / ATE0 / ATL0 / ATS0 / ATH1 / ATAL / ATSP6
ATSH714            ; request ID
ATCRA77E           ; response ID (+0x6A)
ATFCSH714          ; FC must go to 0x714, not 0x776
ATFCSD300000
ATFCSM1
ATST19             ; ~100 ms; raise to FF for slow modules
1902FF             ; (optionally 1003 first if NRC 7F)
```

### 2.3 VW TP2.0 (PQ/MLB/D3, non-OBD modules)
[DOC: https://jazdw.net/tp20 (reverse-engineered; the author states it is not official VW documentation). Official spec: SAE J2819, not public.]

**Frame types**
- **Channel setup** is 7 bytes, sent to **0x200**. The response comes on **0x200 + destination logical address** (engine 0x01 answers on 0x201).
  - Request: `[dest] C0 [rxID_lo] [rxID_hi|valid] [txID_lo] [txID_hi|valid] 01`
  - Example: `200: 01 C0 00 10 00 03 01` gets `201: 00 D0 00 03 40 07 01`. The tester now sends on **0x740** and the ECU sends on **0x300**.
  - Opcodes: 0xC0 = request, 0xD0 = positive, 0xD6–0xD8 = negative.
- **Channel parameters**:
  - `A0 BS T1 FF T3 FF` = request; `A1 …` = response.
  - `A3` = channel test (keep-alive, about every 1 s). [DOC: NI VWTP docs]
  - `A4` = break; `A8` = disconnect.
  - Example: `740: A0 0F 8A FF 32 FF` gets `300: A1 0F 8A FF 4A FF`.
- **Data frames**: the first byte's high nibble is the opcode, the low nibble is the sequence number (0–F).
  - `0x` = wait for ACK, more follow.
  - `1x` = wait for ACK, last.
  - `2x` = no ACK, more follow.
  - `3x` = no ACK, last.
  - ACK `Bx` = ready; `9x` = not ready.
  - The first packet starts with a 2-byte length, then KWP2000.
  - Example: `740: 10 00 02 10 89` gets `300: B1`, then `300: 10 00 02 50 89`, then you send `740: B1`.
- **Gateways** reportedly allow only one open channel at a time. Close with `A8` before the next module. [COMM: OVMS dev list, https://lists.openvehicles.com/archives/list/ovmsdev@lists.openvehicles.com/thread/FDVRIIZNUR765CESHVMECH73E4DF4EOC/]

**Logical (destination) addresses.** These are commonly equal to the VCDS module address. Only 0x01 is confirmed in the public write-up; the rest are [UNC]:

| Addr | Module |
|---|---|
| 0x01 | Engine |
| 0x02 | Auto trans |
| 0x03 | ABS |
| 0x08 | HVAC |
| 0x09 | Central electrics |
| 0x15 | Airbag |
| 0x16 | Steering wheel |
| 0x17 | Instruments |
| 0x19 | CAN gateway |
| 0x44 | Steering assist |
| 0x46 | Central convenience |
| 0x56 | Radio |
| 0x5F | Information electronics |

Practical approach: **sweep dest 0x01–0x7F**. Any `D0` response means a module is present.

**ELM327 sequence** [COMM: dieselpower.cz thread summary (page was 403 to my fetch; steps quoted via search snippet); vagscan README; INF for details]
```
ATZ / ATE0 / ATL0 / ATS0 / ATH1 / ATAL
ATSP6  (or ATPB C0 01 + ATSPB for variable-DLC raw 500k, as EdiabasLib does)
ATCAF0              ; raw frames, no PCI
ATV1                ; variable DLC so 1-byte ACKs are not padded (v1.3+) [INF]
ATSH200 / ATCRA201
01 C0 00 10 00 03 01        -> 201 00 D0 00 03 40 07 01   (TX=0x740, RX=0x300)
ATSH740 / ATCRA300
A0 0F 8A FF 32 FF           -> 300 A1 0F 8A FF 4A FF
10 00 02 10 89              -> 300 B1 ; 300 10 00 02 50 89     (then send B1)
B1
11 00 04 18 02 FF 00        -> ECU ACK + 58 nn [DTC_H DTC_L status]...  (KWP readDTCByStatus)
...ACK each block (Bx with next expected seq)...
A8                          ; disconnect before next module
```
**Pitfalls**
- Every ACK is a separate ELM command over Bluetooth, so the T1 ACK timeout (tens of ms) is tight. ELM327 cannot ACK automatically. Use `ATST` small, `ATAT0`, and reply immediately.
- Clones without working `ATCAF0`/`ATCRA` cannot do TP2.0.
- Deep OBD supports TP2.0 only with its **replacement ELM firmware**, which suggests stock-ELM TP2.0 is fragile. [DOC: Deep OBD doc, https://github.com/uholeschak/ediabaslib/blob/master/docs/Replacement_firmware_for_ELM327.md: "Support for the VAG protocols KWP2000, KWP1281, TP2.0 (cars until 4.2012)"]
- Send `A3` every second while idle. [DOC: NI VWTP docs, https://www.ni.com/docs/en-US/bundle/automotive-diagnostic-command-set-toolkit-labview-api-ref/page/vwtp-connect-vi.html]
- Reference C implementation: https://github.com/seishuku/teensycanbusdisplay/blob/master/vwtpkwp2k.c

### 2.4 KW1281 (very old VAG)
5-baud init to the module address, then byte-wise complement echo at 10400/9600/4800 baud. The stock ELM327 cannot do it: `ATSP3`/`ATIIA` give only ISO 9141 framing. Use EdiabasLib replacement firmware or a KKL/FTDI cable. [DOC: Deep OBD doc; INF]

---

## 3. BMW

### 3.1 Generations
| Era | OBD pins | Protocol | ELM327 |
|---|---|---|---|
| E-series ≤ about 2006/07 | K-line (pin 7, plus pin 8 on some) | **DS2** (9600 8E1, non-ISO) and BMW-FAST/KWP2000* | **No** with stock ELM. Replacement firmware is needed. "E36, E38, E39, E46, E52, E53, E83, E85 and E86 additionally require a connection between OBD pin 7 and 8". [DOC: EdiabasLib replacement-firmware doc] |
| E-series about 2007+ (E60 LCI, E90, E70…) | **D-CAN**, pins 6/14, 500k | KWP2000 on ISO-TP **extended addressing**, tester 0xF1 | Yes, v1.4b+ [DOC: Deep OBD "1.4b, 1.5 and origin 2.1 … D-CAN protocol only"] |
| F-series (2008–2019) and G-series | D-CAN on 6/14 (and ENET on pins 3/11/12/13) | **UDS** on the same D-CAN extended addressing, routed by **ZGW/FEM-GW** | Yes for reading. Car Scanner's 105 modules on your F01 confirm this. Deep OBD recommends ENET for F-models (speed/coding). [DOC] |
| G-series late / CAN-FD internal | D-CAN still on the OBD port via gateway | UDS | [UNC] |

### 3.2 Addressing scheme
[DOC: EdiabasLib `EdElmInterface.cs`, https://github.com/uholeschak/ediabaslib/blob/master/EdiabasLib/EdiabasLib/EdElmInterface.cs]

- Request CAN ID = **0x600 | tester address**, i.e. **0x6F1** (tester 0xF1). Init uses `ATSH6F1`, `ATCF600`, `ATCM700`, `ATPBC001`, `ATSPB`, `ATAT0`, `ATSTFF`, `ATAL`, `ATH1`.
- "The target ECU address … is placed as the first data byte of each CAN frame". In full-transport mode the code sends `ATSH 6F1`, `ATFCSH 6F1`, `ATFCSD <target>30<BS><STmin>`, `ATCEA <target>`, `ATFCSM1` per request.
- Response CAN ID = **0x600 + ECU address**, with **first data byte = 0xF1**. The 6F1/612 pair for the DME is also seen in forum traces. [COMM: https://www.bimmerforums.com/forum/showthread.php?p=29800650]
- Accepting all ECUs: filter `ATCF600`/`ATCM700` (0x600–0x6FF), headers on.
- Functional/broadcast address: EdiabasLib handles functional addressing (mask 0xC0 in its BMW-FAST header) by expanding into each ECU address. A raw broadcast address on D-CAN (often quoted as 0xDF for UDS) is [UNC]. **Prefer a physical sweep of 0x00–0xFF.**

### 3.3 ECU addresses (F-series)
Partial list from a MY2016 F-series: https://github.com/packetpilot/bmw-f. That source says the list is incomplete, so these are [DOC-partial]. Responses come on 0x600+addr.

| Addr | ECU | | Addr | ECU |
|---|---|---|---|---|
| 0x01 | ACSM (airbag) | | 0x40 | FEM_BODY (FRM/CAS/JBBF combined; on F01 this is CAS) |
| 0x06 | TRSVC (surround camera) | | 0x41/0x42 | TMS (headlight secondary) |
| 0x08 | HC2 (lane-change warning) | | 0x43/0x44 | LHM (LED headlight main) |
| 0x0F | GHAS (rear diff) | | 0x56 | FZD (roof function centre) |
| **0x10** | **FEM_GW / ZGW gateway** (klartext also uses 0x10 as gateway) | | 0x5D | KAFAS (camera) |
| **0x12** | **DME** (engine) | | **0x60** | **KOMBI** (cluster) |
| 0x13 | DME2 on some bi-engine variants [UNC] | | 0x61 | ATM |
| 0x17 | EKPS (fuel pump) | | 0x63 | Head unit (NBT/CIC) |
| **0x18** | **EGS** (gearbox) [COMM; listed "XX" in packetpilot] | | 0x67 | ZBE (iDrive controller) |
| 0x1C | ICM (integrated chassis management) | | 0x6D/0x6E | SM (seat modules) |
| **0x29** | **DSC** | | **0x72** | REM (rear electronics; on F01 FRM is about 0x72) [UNC] |
| 0x30 | EPS | | 0x76 | VDC/VDM |
| 0x35 | TBX (touch box) | | **0x78** | **IHKA** (climate) |
| 0x37 | AMPT (amplifier) | | 0x3F | ASD (active sound) |

klartext notes "the gateway lists 30+ configured addresses". The authoritative list is the gateway's SVT/ECU list. [DOC: https://github.com/HadiCherkaoui/klartext] **For the app, sweep 0x00–0xFF** (about 256 probes × 50–100 ms) and cache per VIN.

E-series D-CAN uses the same scheme. Classic addresses (DME 0x12, EGS 0x18, DSC 0x29, KOMBI 0x60, CAS 0x40, FRM 0x72, IHKA 0x78, airbag 0x01, JBBF 0x00) are [COMM].

### 3.4 Reading DTCs
- **F/G (UDS):** `19 02 FF` (or `19 02 0C`). Each record is 4 bytes = 3-byte DTC + status, "confirmed on an F25". [DOC: klartext] Display the DTC as 6 hex digits (e.g. `130508`).
- **E-series D-CAN (KWP2000):** `18 02 FF FF` (readDTCByStatus). 2-byte BMW hex code + status. [COMM/UNC: exact group bytes]
- **Session:** none needed for reading. If you open `10 03`, keep it alive with `3E 80` about every 2 s. [DOC: klartext] Handle NRC 0x78. P2* is up to 5 s. [DOC: klartext]

**ELM327 sequence (F01 DME)**
```
ATZ / ATE0 / ATL0 / ATS0 / ATH1 / ATAL
ATSP6                    ; (EdiabasLib uses ATPB C0 01 + ATSPB; ATSP6 also works for 11-bit/500k)
ATSH6F1
ATCEA12                  ; first byte 0x12 = DME   (v1.4+)
ATCRA612                 ; response 0x600+0x12
ATFCSH6F1
ATFCSD12300000           ; FC also needs the extended address byte!
ATFCSM1
ATST32                   ; 200 ms
1902FF
-> 612 F1 10 xx 59 02 ...   (first byte F1 = tester; ELM strips/filters per CEA)
```
**Pitfalls**
- Single-frame payload is at most 6 bytes.
- With `ATCEA` on, the ELM327 drops any reply whose first byte is not the tester address. That is fine for BMW, because replies start with F1.
- Some clones accept `ATCEA` but mishandle multi-frame reception. EdiabasLib's "standard mode" therefore does its own ISO-TP via `ATMA` monitoring.
- Expect long multi-frame `59 02` responses. Use `ATAL` and a 4095-byte buffer.

---

## 4. Mercedes-Benz  [mostly UNC: no good public ID table found]

**Known**
- Pre-about-2002 cars (W210/W202/W220 early) have many **per-module K-lines** on OBD pins (EZS, engine, ABS, transmission K-lines on different pins). Generic ELM reaches only pin 7. [DOC: pinout list, https://pinoutguide.com/CarElectronics/mercedes_obd2_diag_pinout.shtml]
- About 2002–2009 (W203/W211/W221/W164…) use **KWP2000 on CAN** (ISO-TP 11-bit) through the diagnostic CAN/gateway (EZS/EIS acts as gateway on many). Drive-train CAN is 500k; interior/telematics CAN is 125k. [COMM: https://mbworld.org/forums/s-class-w221/795905-w221-can-bus-thread.html]
- About 2010+ (W204 facelift, W212, W166, W205/W222 onwards) moved to **UDS**. From about 2017 (W213/W222 MOPF), DoIP is used for full diagnostics, but CAN reading via OBD pins 6/14 remains. [COMM]

**What I could not verify publicly:** a reliable request/response CAN ID table. Commonly quoted: engine 0x7E0/0x7E8 and transmission 0x7E1/0x7E9 (OBD-legislated), body ECUs in 0x4E0–0x4FF / 0x5xx / 0x6xx ranges. All of these are [UNC].

**DTC services:** KWP `18 02 FF 00` (or `18 02 FF FF`); UDS `19 02 xx`.

**Recommendation:** treat Mercedes purely by **discovery**:
1. 11-bit sweep of request 0x400–0x7FF with an open filter (`ATCF 000`/`ATCM 000` is too noisy, so use ranges `ATCF 400`/`ATCM 400`, then `600`/`600`).
2. 29-bit `18DAxxF1` sweep for newer cars.

---

## 5. Ford / Lincoln / Mazda

### 5.1 Buses
- **HS-CAN** on pins 6/14, 500k: PCM, TCM, ABS, RCM, PSCM, radar, camera, sometimes BCM. **MS-CAN** on pins **3/11**, 125k: IPC, BCM (often on both), HVAC, APIM/SYNC, ACM, DDM/PDM, etc. [DOC: https://forum.forscan.org/viewtopic.php?p=4: "MS CAN bus is not supported by the stock ELM327, because MS CAN is a manufacturer-specific solution and located on non-OBD2 pins"]
- Some trucks (for example a 2004 F-150) use single-wire **UBP** on pin 3 instead, which is not readable. [COMM: https://forum.forscan.org/viewtopic.php?p=13984]
- Adapters that can switch:
  - Modified ELM327 clones with an HS/MS toggle (PIC18F25K80).
  - STN11xx/21xx/22xx (OBDLink EX, MX+), via `STP 53` (MS-CAN ISO 15765 11-bit 125k). FORScan supports STN MS-CAN since 2.1.0. [DOC: FORScan forum; STN FRPM]
  - Some "auto-switch" clones use relay commands. [UNC]
- **2020+ Ford** has a GWM (gateway). opendbc notes that for the PCM at 0x7E0 "we are unlikely to get a response from behind the gateway" on its tap point. That is an openpilot-specific bus position; from the OBD port the PCM normally answers. [DOC/INF: https://raw.githubusercontent.com/commaai/opendbc/master/opendbc/car/ford/values.py]

### 5.2 Addresses (11-bit; response = request + 8)
| Module | Req/Resp | Bus | Confidence |
|---|---|---|---|
| PCM | 0x7E0/0x7E8 | HS | [DOC opendbc] |
| TCM | 0x7E1/0x7E9 | HS | [DOC mazda fp] |
| ABS | 0x760/0x768 | HS | [DOC opendbc `STANDARD_VIN_ADDRS` comment "Ford abs"; mazda fp] |
| PSCM (EPS) | 0x730/0x738 | HS | [DOC mazda fp] |
| GSM (shift-by-wire) | 0x732/0x73A | HS | [DOC opendbc ford] |
| CCM/radar | 0x764/0x76C | HS | [DOC mazda fp] |
| IPMA camera | 0x706/0x70E | HS | [DOC mazda fp] |
| **APIM (SYNC)** | 0x7D0/0x7D8 | MS (HS on newer) | [DOC opendbc; as-built] |
| **IPC** | 0x720/0x728 | MS | [COMM: "every address in IPC will begin with 720"] |
| **BCM/BdyCM** | 0x726/0x72E | MS/HS | [COMM: same source] |
| ACM (audio) | 0x727/0x72F | MS | [COMM as-built] |
| RCM (airbag) | 0x737/0x73F | HS | [UNC] |
| HVAC | 0x733/0x73B | MS | [UNC] |
| DDM/PDM | 0x740/0x741 | MS | [UNC] |
| GWM | 0x716 | HS | [UNC] |

Source for IPC/BCM/APIM prefixes: https://www.ecoboostownerforums.com/topic/2045-accessing-advanced-ford-pids-with-a-scan-tool/

**DTC reading:**
- 2010+ UDS: `19 02 FF`. 4-byte records = 2-byte SAE + FTB + status.
- 2005–2010 CAN Ford (pre-UDS, "KWP-like" Ford diag): try `19 02 FF`, then `18 00 FF 00`. [UNC]

**ELM327 sequence, MS-CAN with a switch-modified ELM327**
```
ATZ / ATE0 / ATH1 / ATAL / ATS0
ATPB A1 04 / ATSPB        ; 11-bit, DLC8, ISO15765, 125k   [INF from datasheet bits]
ATSH726 / ATCRA72E / ATFCSH726 / ATFCSD300000 / ATFCSM1
1902FF
```
**STN:** `STP 53`, then the same `ATSH`/`ATCRA`. **Mazda** shares Ford addressing for ABS, EPS, radar, camera and TCM. [DOC: https://raw.githubusercontent.com/commaai/opendbc/master/opendbc/car/mazda/fingerprints.py]

---

## 6. GM (Chevrolet, Opel/Vauxhall pre-PSA, Cadillac, Buick, Holden)

- **HS-GMLAN** on pins 6/14, 500k, 11-bit. ECM and TCM answer OBD on 0x7E0/0x7E8 and 0x7E2/0x7EA. Other modules use **USDT physical request 0x24x and response 0x64x** (opendbc `GM_RX_OFFSET = 0x400`; e.g. camera at 0x24B). [DOC: https://raw.githubusercontent.com/commaai/opendbc/master/opendbc/car/gm/values.py]
  - Commonly cited: BCM 0x241/0x641, EBCM 0x243/0x643, SDM 0x247/0x647, IPC 0x24C/0x64C. [UNC]
  - **UUDT** responses (single frames used for DTC and DPID data) come on **0x5xx**, e.g. 0x5E8 for the ECM. [UNC; GMW3110 not public]
- **Functional all-nodes request:** "AllNodes functional diagnostic request CANId ($101) and the AllNodes extended address ($FE)". This is extended addressing on 0x101. [DOC: https://pkg.go.dev/github.com/roffe/gocan/pkg/gmlan]
- **LS-GMLAN / SW-CAN** on pin 1, 33.3k: body, radio and HVAC on many 2006–2016 cars. **STN only** (`STP 61–64`). [DOC: STN FRPM]
- **Global B (about 2020+):** 29-bit UDS and a secure gateway. [UNC]
- **DTC (GMLAN):** `A9 81 <mask>` (readStatusOfDTCByStatusMask; e.g. mask `12` = history+current). Records are 2-byte DTC + FTB + status. ReadDataByIdentifier is `1A xx` (response `5A`). [DOC: gocan constants `LEV_RSDTCBS = 0x81`; opendbc `1A C1` and others] Global B uses UDS `19 02`.

**ELM327 sequence (BCM)**
```
ATSP6 / ATH1 / ATAL
ATSH241 / ATCF 500 / ATCM 500      ; accept 0x5xx (UUDT) and 0x6xx? -> use ATCF 400 ATCM 400 to see both 0x5xx and 0x6xx [INF]
ATFCSH241 / ATFCSD300000 / ATFCSM1
A9 81 12
```
Functional broadcast: `ATSH101`, `ATCEA FE`, then `3E 00` or `A9 81 12`. Replies arrive from many 0x5xx/0x6xx IDs. [INF from gocan]

---

## 7. Toyota / Lexus

[DOC: https://raw.githubusercontent.com/commaai/opendbc/master/opendbc/car/toyota/values.py, …/toyota/fingerprints.py]
- 11-bit ISO-TP, **response = request + 8**. Older ECUs speak **KWP2000-on-CAN**; newer ones (TSS2/TNGA about 2018+) speak **UDS**.
- Toyota KWP version read: `1A 88 01` (response `5A 88 01`).

| ECU | Req | Notes |
|---|---|---|
| Engine | 0x7E0 (also 0x700 on some) | |
| Transmission | 0x7E1 / 0x701 | |
| Hybrid | 0x7E2 (KWP) or 0x7D2 (UDS) | |
| ABS | 0x7B0 (0x7D1 on Mirai) | |
| 2nd ABS | 0x730 (UDS) | |
| EPS | 0x7A1 (0x7A0) | |
| SRS airbag | 0x780; 2nd SRS 0x784 | |
| Steering angle | 0x7B3 | |
| Combination meter | 0x7C0 (UDS) | |
| HVAC | 0x7C4 | |
| DSU | 0x791 | |
| HV battery | 0x713/0x747 | |
| Motor generator | 0x716/0x724 | |

**Extended addressing on 0x750 (sub-address = first byte):**

| Sub | ECU |
|---|---|
| 0x0F | Radar |
| 0x2C | EPB |
| 0x40 | Body control module (KWP `1A 88 81`) |
| 0x5F | Central gateway |
| 0x6D | Camera |
| 0xC7 | Telematics |

The response comes on **0x758** with the first byte = sub-address. [DOC for sub-addresses; INF for the 0x758 response and its first byte, consistent with the ELM datasheet example and opendbc's (addr, subaddr) model]

**DTC:**
- UDS ECUs: `19 02 FF`.
- KWP ECUs: try `18 00 FF 00` / `18 02 FF 00`, then KWP `13` (readDTC). [UNC: Toyota-specific service not publicly confirmed]

**ELM327 for the 0x750 sub-addressed BCM**
```
ATSP6 / ATH1 / ATAL
ATSH750 / ATCEA40 / ATCRA758
ATTA40              ; [UNC] ELM CEA filter passes only frames whose 1st byte == tester addr;
                    ; Toyota replies with the sub-address (0x40) there -> set TA to it, or use CAF0/manual
ATFCSH750 / ATFCSD40300000 / ATFCSM1
...
```

---

## 8. Renault / Dacia / Nissan / Infiniti / Lada (Renault-based: Largus, XRAY; Vesta partially)

### 8.1 Renault/Dacia (DDT2000/pyren addressing)
[DOC: DDT4ALL `can_addressing.py` (tables extracted from pyren3 `mod_elm.py`), https://github.com/cedricp/ddt4all/blob/master/src/ddt4all/plugins/cmd_macros_lib/can_addressing.py; ELM sequences in `src/ddt4all/core/elm/elm.py`]

The logical address is the DDT "address byte". The 11-bit response is mostly request + 0x20. Some ECUs are 29-bit `18DA<addr>F1` with response `18DAF1<addr>`. Module names are [COMM/UNC]; IDs are [DOC].

| Addr | Req | Resp | Typical module (UNC names) |
|---|---|---|---|
| 7A | 7E0 | 7E8 | Engine injection |
| 6E | 7E1 | 7E9 | Auto gearbox |
| 01 | 740 | 760 | ABS/ESP |
| 04 | 742 | 762 | EPS (DAE) |
| 51 | 743 | 763 | Instrument cluster (TdB) |
| 29 | 744 | 764 | HVAC |
| 26 | 745 | 765 | UCH (BCM) |
| 58 | 747 | 767 | Navigation/multimedia |
| 2C | 752 | 772 | Airbag |
| 0D | 755 | 775 | EPB |
| 0E | 74E | 76E | UCT/keyless |
| 93 | 79B | 7BB | EV battery (LBC) |
| 41 / D0 | 18DAD0F1 | 18DAF1D0 | Gateway (CGW) |
| E7 / E8 | 7E4 / 644 | 7EC / 5C4 | SCR / surround view (from code comment) |

The full table has 165 entries. Copy it from the source file.

- **CAN bit rate per ECU:** 500k or 250k. DDT4ALL tries 250k then 500k on "double BRP" ECUs, detecting `CAN ERROR`.
- **Init for each ECU:** `AT SH <TX>`, `AT FC SH <TX>`, `AT FC SD 30 00 00`, `AT FC SM 1`, `AT ST FF`, `AT AT 0`, `AT SP 6|8` (`7|9` for 29-bit, with `AT CP <first 2 hex>` + `AT SH <rest>`), `AT CAF0`, `AT S0`, `AT AL`, `AT AT 1`, `AT CRA <RX>`.
- DDT4ALL re-applies `CAF0`/`S0`/`AL` after `AT SP` "because some ELM327 clones (including VLinker FS) reset CAF/S/AL to defaults when the protocol changes". [DOC]
- **K-line (ISO8/KWP):** `AT SH 81 <addr> F1`, `AT SW 96`, `AT WM 81 <addr> F1 3E`, `AT IB10`, `AT ST FF`, `AT SP 4` + `AT IIA <slowInitAddr>` + `AT SI`, or `AT SP 5` + `AT FI`, then `81` (StartCommunication). [DOC]
- **Sessions/DTC:** DDT4ALL probes with `10 C0` (Renault extended session). DTCs: KWP `17 FF 00` [COMM, same as PSA]; UDS `19 02 xx`.

### 8.2 Nissan / Infiniti
[DOC: https://raw.githubusercontent.com/commaai/opendbc/master/opendbc/car/nissan/values.py, …/nissan/fingerprints.py]
- `NISSAN_RX_OFFSET = 0x20`. Engine is at 0x7E0, responding on 0x7E8 or (some Altima) "normal rx offset".

| ECU | Req/Resp |
|---|---|
| ABS | 0x740/0x760 |
| EPS | 0x742/0x762 |
| Combination meter | 0x743/0x763 |
| Camera | 0x707/0x727 |
| Gateway (29-bit) | 0x18DAD0F1 |

  These match the Renault table above: an alliance-wide scheme.
- Consult-III-style **KWP2000-on-CAN** sessions are `10 81`, `10 C0`, or `10 DA` ("Rogue's engine solely responds to this"). Version read is `21 83`.
- **Leaf** examples: LBC 0x79B/0x7BB; 11-bit, 500k on Car-CAN/EV-CAN. [DOC: https://leaf-obd.readthedocs.io/en/latest/diagnostic-connector.html; COMM: https://mynissanleaf.com/goto/post?id=269461]
- BCM is commonly 0x745/0x765. [UNC]
- **DTC:** UDS `19 02 xx` on newer ECUs; KWP-style on Consult ECUs (try `18 02 FF 00`, then `17 FF 00`). [UNC]
- Older Nissan (pre-2007) has a **Consult-I/II K-line** proprietary protocol, which the ELM327 cannot do. [COMM]

### 8.3 Lada and other Russian vehicles
**Lada ECUs on K-line** [COMM: https://www.drive2.ru/l/665745495599113935/; 4pda thread https://4pda.to/forum/index.php?showtopic=510170&st=240; init strings https://kodyoshibok01.ru/stroki-inicializacii-elm327/]
- Январь 7.2, Bosch M7.9.7, Itelma M73/M74/M75 use **KWP2000 on K-line**: ECU address **0x10**, tester 0xF1, fast init, 10400 baud.
- Example raw frame: `84 10 F1 18 00 00 00 9D`. Read DTCs with `18 00 FF 00`; `58 00` means no errors.
- ELM: `ATSP5`, `ATAL`, `ATIB10`, `ATSH8110F1`, `ATST32`, `ATSW00`, `ATFI`, then `18 00 FF 00`.
- Январь 5.1 / VS 5.1 / Bosch M1.5.4: KWP-like on K-line, but reports of ELM success are mixed. [UNC]
- Other Lada modules (ABS, airbag, immobiliser АПС) have their own K-line addresses. [UNC: e.g. ABS Bosch 8.1 is reportedly reachable via KWP on K-line with a different address]

**Lada CAN cars**
- **Vesta / XRAY / Largus:** Largus and XRAY are Renault B0 platform, so use the Renault table and pyren. Vesta (M86 ECU) uses 11-bit CAN 500k, engine at 0x7E0. Other Vesta modules: sweep 0x700–0x7FF. [UNC]

**UAZ / GAZ** [UNC]
- Mikas 7.x/10.x/11 and Bosch ME17.9.7/M17.9.7 on ZMZ-409/Evotech/Cummins: KWP2000 on K-line (address 0x10/0x11) in older years. Gazelle Next / UAZ 2016+ use CAN ISO 15765 at 0x7E0.
- Treat these as "OBD + KWP K-line engine + sweep".

---

## 9. PSA (Peugeot, Citroën, DS, Opel post-2017)

[DOC: https://github.com/ludwig-v/arduino-psa-diag (README + `ECU_LIST.md`)]

- 11-bit ISO-TP, with **response = request − 0x20** in most cases. Two buses:
  - "IS" (inter-system) at 500 kbit/s: KWP2000 or UDS.
  - "HAB" (comfort) at 125 kbit/s: KWP2000.
- Whether HAB modules are reachable from OBD pins 6/14 through the BSI gateway is [UNC]. The project's README lists KWP HAB at 125 kbit.

| Family | Req:Resp | | Family | Req:Resp |
|---|---|---|---|---|
| INJ (engine) | 6A8:688 | | COMBINE (cluster) | 75F:65F |
| BOITEVIT (gearbox) | 6A9:689 | | CLIM (HVAC) | 76D:66D |
| ABRASR (ABS/ESP) | 6AD:68D | | AFFICHEUR (display) | 765:665 |
| AIRBAG | 744:644 | | AUTORADIO | 760:660 |
| BSI/BSM/BSR/BTC (body) | 752:652 | | TELEMAT (NAC/RCC/SMEG) | 764:664 |
| DIRECTN (EPS) | 6B5:695 | | DSG (TPMS) | 6AF:68F |
| HDC (column/COM2008) | 742:642 | | CORPRO (headlamps/AFS) | 6B7:697 |
| ARTIV (radar) | 6B6:696 | | PORTEC (tailgate) | 74B:64B |
| SPNEU (suspension) | 6B8:698 | | BSP (gateway, newer) | 720:728 |

The full list (>100 families, including EV CMUs) is in the source file. Note the exceptions: some pairs are +8, +1 or arbitrary (e.g. 622:484, 58B:58A).

- **DTC read:** UDS `19 02 09` (current faults); KWP `17 FF 00`. Clear is `14 FF FF FF` / `14 FF 00` (do not use).
- **Sessions:** UDS `10 03`; KWP-HAB `10 C0`; KWP-IS `81` (StartCommunication). Tester present is `3E 00` / `3E`.
- Unlock (`27`) is not needed for reading.
- **ELM:** same as 11-bit normal, with `ATSH6A8`, `ATCRA688`, `ATFCSH6A8`, `ATFCSD300000`, `ATFCSM1`.

---

## 10. Hyundai / Kia / Genesis

[DOC: https://raw.githubusercontent.com/commaai/opendbc/master/opendbc/car/hyundai/values.py, …/hyundai/fingerprints.py]
- 11-bit UDS (older about 2006–2012 cars: KWP-on-CAN [UNC]). **Response = request + 8.**

| ECU | Req |
|---|---|
| Radar/SCC | 0x7D0 |
| ABS/ESC | 0x7D1 |
| MDPS (EPS) | 0x7D4 |
| Camera | 0x7C4 |
| Cluster (CAN-FD cars) | 0x7C6 |
| HVAC | 0x7B3 |
| Parking ADAS | 0x7B1 |
| Corner radar | 0x7B7 |
| ADAS ECU | 0x730 |
| Engine/TCU | 0x7E0/0x7E1 |

  Community-known additional IDs: airbag 0x7D2, BCM/IPS 0x7A0, IGPM 0x770, smart key 0x7A5, BMS 0x7E4. [UNC] Your existing 0x7A0–0x7DF sweep is right.
- Version DIDs: `22 F1 00` and `22 F1 10`.
- **CAN-FD** (2021+ HDA2 platforms) uses CAN-FD internally; ELM327 cannot do CAN FD. Responses through the OBD port are classic CAN on most models [INF]. opendbc notes ECUs may send `30 08 0A AA…` flow control unexpectedly.
- 29-bit `18DAxxF1` is rare on Hyundai. [UNC]
- **DTC:** `19 02 FF`. Older KWP ECUs: `18 02 FF 00`. [UNC]

---

## 11. Honda / Acura

[DOC: https://raw.githubusercontent.com/commaai/opendbc/master/opendbc/car/honda/values.py, …/honda/fingerprints.py, `fw_query_definitions.py` (`STANDARD_VIN_ADDRS` includes 0x18DA10F1 "29-bit engine" and 0x18DA0EF1 "PGM-FI")]
- Modern Hondas (about 2013+, some from 2008) use **29-bit normal-fixed UDS**: request `18DA<TA>F1`, response `18DAF1<TA>`.

| TA | ECU |
|---|---|
| 0x10 | PGM-FI (engine; 0x0E on some) |
| 0x1E | Transmission |
| 0x28 | VSA (ABS) |
| 0x30 | EPS |
| 0x53 | SRS |
| 0x60 | Meter |
| 0x61 | HUD |
| 0xB0 | Radar |
| 0xB5 | Camera |
| 0xEF | Gateway |
| 0x0B | Shift-by-wire |
| 0x2B | Electric brake booster |

  A camera sub-address quirk is noted at 0xB3/0xB0.
- Some 2006–2012 models use 11-bit for OBD only, and the body is on **proprietary K-line**. Pre-2006 uses the Honda proprietary (HDS) K-line/"3-pin". ELM327: no. [COMM/UNC]
- **DTC:** `19 02 FF` on 29-bit UDS ECUs. [INF]

**ELM327 (v1.x-safe)**
```
ATSP7 / ATH1 / ATAL
ATCP18 / ATSH DA28F1          ; VSA
ATCRA 18DAF128
ATFCSH 18DA28F1 / ATFCSD300000 / ATFCSM1
1902FF
```

---

## 12. Volvo

**P1/P2/P3 (about 2004–2016): proprietary "VIDA" protocol on 29-bit CAN.** It is not UDS and not ISO-TP. [DOC: https://github.com/OBDb/Volvo-V50/issues/14; https://github.com/Tigo2000/Volvo-VIDA]
- **Request:** ID **0x000FFFFE**, frame `C8+n <module> <service> <data…>` padded to 8 bytes.
- **Responses:** `0x0040xxxx` (HS) / `0x0060xxxx` (LS, e.g. DIM 0x00600009).
- **Services:**
  - `A6` = read data (response `E6`).
  - **`AE 11` = read DTCs** (response `EE 11` + 2-byte DTCs).
  - `B9` = read block.
  - `7F` = negative response.
- **Multi-frame:** byte 0 bit7 = first frame, bit6 = last frame, bits 0–2 = valid byte count.
- **Modules:**
  - From the V50 diesel issue: ECM 0x11 (resp 00400021), CEM 0x50 (resp 00400003), SWM 0x49, ADM 0x0B; 0x01/0x30 are guesses.
  - From Volvo-VIDA (P2): ECM ME7 at 0x7A, DIM at 0x51.
  - Forum posts list CEM 0x40 on LS and 0x50 on HS. [COMM: https://www.matthewsvolvosite.com/forums/viewtopic.php?t=85611&start=940]
- **Buses:** HS 500k (pins 6/14). LS 125k for DIM and body (pins 3/11 on Volvo; **STN `STP52`** used in the issue). The CEM gateways some traffic.
- **ELM:** `ATCAF0`, `ATCP00`, `ATSH0FFFFE`, `ATST40`, `ATCF00400000`, `ATCM1FFF0000`, then `CB <mod> AE 11 00 00 00 00`. `ATCRA` gave NO DATA; an open mask floods the adapter. [DOC]
- **SPA/CMA (2015+ XC90 II onwards):** UDS (11-bit or 29-bit) plus DoIP. [UNC]

---

## 13. Subaru

[DOC: https://raw.githubusercontent.com/commaai/opendbc/master/opendbc/car/subaru/fingerprints.py]
- 11-bit, response + 8: engine 0x7E0, transmission 0x7E1 (0x7A2/0x7A3 on 2023 Ascent/Outback), ABS 0x7B0, EPS 0x746, camera 0x787.
- Engine and TCU also speak **SSM (Subaru Select Monitor)** on CAN at 0x7E0 (SSM service `A8` read address). Older cars use SSM on K-line (4800 baud), which the ELM327 cannot do. [COMM]
- **DTC:** UDS/OBD for engine; `19 02 FF` on newer ECUs. [UNC]

## 14. Mitsubishi

- 11-bit CAN. **One documented ABS pair is 0x784 → 0x785 (+1).** [COMM: https://www.drive2.ru/l/493430308171940054/, Pajero Sport 2G 2014]
  - Sequence: `ATSH784`, `ATFCSH784`, `ATFCSD30080A`, `ATFCSM1`, `ATCRA785`, session `10 92`, then `18 00 FF 00` gets `58 03 …`.
- Engine is 0x7E0/0x7E8. Other modules: [UNC]. Use a sweep that accepts any 0x7xx response.
- Mitsubishi-on-Renault/Nissan platforms (ASX 2023, Colt 2023) use the alliance tables. [INF]

## 15. Chrysler / Stellantis NA (for completeness)
[DOC: https://raw.githubusercontent.com/commaai/opendbc/master/opendbc/car/chrysler/fingerprints.py]
- 11-bit (+8):

| ECU | Req |
|---|---|
| Cluster | 0x742 |
| SRS | 0x744 |
| ABS | 0x747 |
| Radar | 0x753 |
| EPS | 0x75A / 0x761 |
| Engine/trans/hybrid | 0x7E0/0x7E1/0x7E2 |

- 29-bit (RAM DT and others): meter 18DA60F1, SRS 18DAC0F1, ABS 18DA28F1, EPS 18DA30F1, engine 18DA10F1, transmission 18DA18F1.
- 2018+ SGW reportedly blocks clears and writes, not reads. [UNC]

## 16. Chinese brands (Chery, Haval/GWM, Geely, Changan, BYD…)  [UNC throughout]
- No reliable public ID tables were found. Community consensus is **UDS on 11-bit 0x7xx**, often +8, with engine at 0x7E0/0x7E8. Many ECUs are Bosch/Delphi/UAES/MT20U/MT22U, and some older engine ECUs still use **KWP2000 on K-line** (e.g. MT20U, Delphi). [COMM: CarBit app notes, https://trashbox.ru/link/carbit-android]
- Some recent platforms (Geely SEA/CMA, Chery T1x) use 29-bit or CAN-FD internally behind a gateway. [UNC]
- **Recommendation:** run the generic 11-bit sweep (0x700–0x7FF, open filter), then a 29-bit `18DAxxF1` sweep. Use DTC `19 02 FF`, with fallback `18 02 FF 00`.

---

## 17. Recommended brand-wide design for the app

### 17.1 Transport abstraction
Implement these transports over one ELM session layer, with per-adapter capability flags:
1. `IsoTp11(req, resp, extAddr=null)`, including manual FC and optional `ATCEA` (BMW, Toyota 0x750, GMLAN functional).
2. `IsoTp29(ta, sa=F1)` using `ATCP`+3-byte `ATSH`. This works on v1.4 clones.
3. `VwTp20(dest)`, implemented over `ATCAF0`.
4. `VolvoVida(module)`, raw 29-bit with `ATCAF0`.
5. `KLineKwp(addr, init=FAST|SLOW)`.
6. Optional `StnBus(MS|SW)` when `STI` reports an STN chip.

### 17.2 Discovery, in VIN-WMI-driven order
1. Run standard OBD first (`0100`, `ATDPN`) to learn whether the car is 11-bit or 29-bit and responsive.
2. Pick the brand plan:
   - **BMW (WBA, WBS, WBY, WMW):** extended sweep `ATSH6F1`, `ATCEA xx` for xx = 0x00..0xFF (skip 0xF1), filter `ATCF600`/`ATCM700`. Probe with `22 F1 50`/`22 F1 00` or directly `19 02 FF`. Expect 50–110 ECUs on F01.
   - **VAG (WVW, WAU, TMB, VSS…):**
     - If OBD answers on 11-bit and any of 0x710–0x7DF answers on +0x6A, it is MQB.
     - Otherwise run a TP2.0 sweep (dest 0x01..0x7F, setup on 0x200, check 0x200+dest for `D0`).
     - Pre-2004 K-line: report "KW1281 not supported by adapter".
   - **Renault/Dacia/Nissan/Lada-Renault, PSA, Toyota, Honda, Hyundai, Ford, GM, Subaru, Chrysler:** probe the table addresses first, then the generic sweep.
   - **Ford/GM:** if `STI` reports STN, also sweep MS-CAN (`STP53`) or SW-CAN (`STP61`/`63`). Otherwise tell the user that MS-CAN modules need an HS/MS switch adapter.
3. **Generic 11-bit sweep:**
   - Send `ATCF 700`, `ATCM 700` (accept 0x700–0x7FF), or `ATCF 400`/`ATCM 400` for GM/PSA ranges.
   - Set `ATH1`, `ATST10`–`19`.
   - For each request ID, send a one-frame probe such as `3E 00` or `10 01`. `10 01` is better for KWP ECUs; avoid `3E 80`, which gets no reply.
   - **Record the responding ID: this learns the offset** (+8, +0x6A, +0x20, −0x20, +0x400, +1).
4. **Generic 29-bit sweep:** `ATCP18`, `ATSH DA xx F1` for xx = 00..FF, `ATCF 18DAF100` / `ATCM 1FFFFF00`.
5. **Cache the discovered (transport, req, resp, ext, bitrate, session-needed) per VIN.** A full sweep costs 30–60 s over Bluetooth.

### 17.3 DTC read policy per ECU
1. UDS `19 02 FF`.
2. On NRC 11/12/7F: KWP `18 02 FF 00`, then `18 00 FF 00`, then `17 FF 00` (PSA/Renault). GM uses `A9 81 12`, Volvo `AE 11`.
3. On NRC 22/7E/7F: open the OEM session and retry:
   - VAG/BMW: `10 03`
   - Renault: `10 C0`
   - PSA KWP: `10 C0`/`81`
   - Nissan: `10 C0`/`10 81`
   - Mitsubishi: `10 92`
4. Handle NRC 0x78 with a 5 s wait.
5. Parse by protocol: 3-byte UDS shows BMW as hex6, others as SAE + FTB; 2-byte KWP; VAG KWP as decimal-5.

### 17.4 Timing and robustness
- Set `ATAT1` (or `ATAT0` with explicit `ATST` per ECU).
- Use `ATST FF` for the first contact of slow ECUs (gateways, airbag). DDT4ALL and EdiabasLib both use `ATSTFF` initially.
- Re-send `ATCAF`, `ATS0` and `ATAL` after every `ATSP` (clone quirk).
- Keep the Bluetooth payload small: `ATS0`, `ATE0`, `ATL0`.
- Expect `BUFFER FULL` on large `59 02` responses over slow Bluetooth SPP. Use `ATAL` and, if supported, STN `STCSEGR`.

---

## 18. Biggest uncertainties (verify on cars)

1. **Mercedes CAN diagnostic IDs**: no trustworthy public table. Discovery only.
2. **VW TP2.0 module logical address mapping** (assumed equal to the VCDS address), and timing feasibility of app-side ACKs over Bluetooth ELM clones.
3. **MQB IDs beyond the 7 documented ones** (gateway 0x710, ABS 0x713, BCM 0x70E, HVAC 0x746…). The +0x6A rule is solid; the specific IDs are community knowledge.
4. **BMW EGS 0x18, ZGW vs FEM_GW (0x10 vs 0x40), FRM/REM 0x72**, and whether a D-CAN functional/broadcast address exists. Prefer a full 0x00–0xFF sweep.
5. **Toyota 0x750 sub-address replies vs the ELM `ATCEA` receive filter.** The tester-address check may need `ATTA <sub>` or a CAF0 manual path.
6. **GM UUDT response IDs (0x5xx) for `A9`**, and Global B (29-bit) addressing.
7. **Ford pre-UDS DTC service**, RCM/HVAC/DDM addresses, and per-model HS vs MS placement.
8. **Toyota KWP DTC service byte**: `13` vs `18`.
9. **Chinese brands, UAZ/GAZ, Lada Vesta body modules**: no public tables.
10. **Clone capability variance**: `ATCEA`, `ATFCSM1`, `ATCAF0`, `ATV1`, and `ATPB` must be probed per adapter.

---

## 19. Source index
- ELM327 datasheet (v2.x, includes version history): https://www.elmelectronics.com/wp-content/uploads/2016/07/ELM327DS.pdf
- ELM327 (Wikipedia): https://en.wikipedia.org/wiki/ELM327
- ISO 15765-2: https://en.wikipedia.org/wiki/ISO_15765-2
- UDS: https://en.wikipedia.org/wiki/Unified_Diagnostic_Services
- OBDLink/STN FRPM: https://www.scantool.net/scantool/downloads/98/stn11xx21xx_frpm-c.pdf
- STN1170: https://www.obdsol.com/solutions/chips/stn1170/
- EdiabasLib ELM interface: https://github.com/uholeschak/ediabaslib/blob/master/EdiabasLib/EdiabasLib/EdElmInterface.cs
- EdiabasLib replacement firmware doc: https://github.com/uholeschak/ediabaslib/blob/master/docs/Replacement_firmware_for_ELM327.md
- Deep OBD doc: https://github.com/uholeschak/ediabaslib/blob/master/docs/Deep_OBD_for_BMW_and_VAG.md
- BMW F ECU list: https://github.com/packetpilot/bmw-f
- klartext (BMW F UDS): https://github.com/HadiCherkaoui/klartext
- BimmerForums 6F1/612 trace: https://www.bimmerforums.com/forum/showthread.php?p=29800650
- VW TP2.0: https://jazdw.net/tp20
- NI VWTP connect: https://www.ni.com/docs/en-US/bundle/automotive-diagnostic-command-set-toolkit-labview-api-ref/page/vwtp-connect-vi.html
- OVMS dev list (TP2.0 single channel): https://lists.openvehicles.com/archives/list/ovmsdev@lists.openvehicles.com/thread/FDVRIIZNUR765CESHVMECH73E4DF4EOC/
- vagscan (TP2.0 over ELM, clone caveats): https://github.com/cquiroga-arch/leontest-01
- VW TP2.0 C reference: https://github.com/seishuku/teensycanbusdisplay/blob/master/vwtpkwp2k.c
- MQB sniffer: https://github.com/mrfixpl/MQB-sniffer
- Ross-Tech DTC screen: https://www.ross-tech.com/vag-com/tour/dtc_screen.html
- opendbc (VW, Toyota, Honda, Hyundai, Ford, GM, Nissan, Subaru, Mazda, Chrysler, fw_query_definitions): https://github.com/commaai/opendbc/tree/master/opendbc/car
- FORScan MS-CAN: https://forum.forscan.org/viewtopic.php?p=4
- FORScan UBP: https://forum.forscan.org/viewtopic.php?p=13984
- Ford address prefixes: https://www.ecoboostownerforums.com/topic/2045-accessing-advanced-ford-pids-with-a-scan-tool/
- GMLAN (gocan): https://pkg.go.dev/github.com/roffe/gocan/pkg/gmlan
- DDT4ALL (Renault): https://github.com/cedricp/ddt4all (files `src/ddt4all/core/elm/elm.py`, `src/ddt4all/plugins/cmd_macros_lib/can_addressing.py`, `src/ddt4all/core/elm/constants.py`)
- PSA: https://github.com/ludwig-v/arduino-psa-diag (+ `ECU_LIST.md`)
- Nissan Leaf: https://leaf-obd.readthedocs.io/en/latest/diagnostic-connector.html
- Mynissanleaf thread: https://mynissanleaf.com/goto/post?id=269461
- Volvo VIDA: https://github.com/OBDb/Volvo-V50/issues/14
- Volvo-VIDA project: https://github.com/Tigo2000/Volvo-VIDA
- Matthews Volvo site: https://www.matthewsvolvosite.com/forums/viewtopic.php?t=85611&start=940
- Mitsubishi ABS via ELM: https://www.drive2.ru/l/493430308171940054/
- Lada K-line: https://www.drive2.ru/l/665745495599113935/
- Lada 4pda thread: https://4pda.to/forum/index.php?showtopic=510170&st=240
- Lada init strings: https://kodyoshibok01.ru/stroki-inicializacii-elm327/
- Mercedes pinout: https://pinoutguide.com/CarElectronics/mercedes_obd2_diag_pinout.shtml
- Mercedes W221 CAN thread: https://mbworld.org/forums/s-class-w221/795905-w221-can-bus-thread.html
- CarBit (Chinese brands): https://trashbox.ru/link/carbit-android
