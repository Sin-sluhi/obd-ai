# Open-source and public knowledge for "all-ECU" diagnostics over ELM327 adapters

Research date: 2026-10-16. Scope: open-source projects, public docs, forums and blogs only. No closed-source APKs were decompiled or analysed.
Licenses were checked through the GitHub API (`license.spdx_id`) on the research date. Repos marked NOASSERTION were checked by reading their LICENSE file.

Confidence markers:
- **[V]** = verified from a fetched source (URL given).
- **[K]** = well-known standard or industry knowledge, not re-verified here.
- **[H]** = hypothesis. Confirm it with field logs before shipping.

---

## 0. TL;DR

1. Nothing magic sits behind "all modules" scanning in Car Scanner, Torque or OBDeleven. They do the same three things:
   - (a) A **brand/model profile** gives them a list of (request ID, response ID, addressing mode, protocol, init) tuples.
   - (b) **Adapter capability detection** tells them whether the dongle can actually set headers, filters, extended addresses and flow control.
   - (c) A **per-protocol DTC service** reads the codes: UDS 0x19, KWP 0x18/0x17, KW1281 0x07, OBD Mode 03/07/0A.

   Car Scanner's own pages confirm the profile model and the adapter dependency [V]:
   - "select connection profile with your car brand + OBD-II" when the model isn't listed.
   - Bad adapters are "hard-coded to work with a standard ECU address".
   - Toyota and BMW need "CAN Extended addressing" support in the adapter firmware.
2. The best **permissively licensed** references for us (MIT/Apache, so code can be ported with attribution) are:
   - **comma.ai opendbc/openpilot** for multi-ECU firmware queries and per-brand ECU address tables.
   - **python-udsoncan + python-can-isotp** for the UDS and ISO-TP state machines.
   - **gallia** (Apache-2.0) and **Caring Caribou** (GPL, ideas only) for discovery sweeps.
   - **kw1281test** (MIT) for VAG KW1281 on K-line.
   - **OVMS3** (MIT, majority of code) for a poller design that mixes ISO-TP std/ext-addr/29-bit and **VW TP2.0**.
   - **ELMduino** (MIT) and **obd-java-api** (Apache) for ELM327 I/O idioms.
3. **GPL projects** (AndrOBD, python-OBD GPL-2, EdiabasLib/Deep OBD, ddt4all, pyren, freediag, vag-blocks, Caring Caribou, OpenVehicleDiag, ecu_diagnostics, WiCAN fw, arduino-psa-diag) are excellent for learning *how* things work. Do not copy their code or their bundled data files into a closed app.
4. **Data licenses are a separate problem**:
   - OBDb is CC-BY-SA-4.0. Share-alike applies to the data you derive from it.
   - Ircama ELM327-emulator is CC-BY-NC-SA-4.0. It is non-commercial, so use it at most as an internal test tool after legal review.
   - awesome-automotive-can-id is CC0 and free to use.
5. Critical engineering facts we must design for:
   - Toyota and BMW use ISO-TP **extended addressing**.
   - VAG MQB UDS responses come at **request ID + 0x6A**.
   - VAG pre-2012 uses **TP2.0** over CAN. Stock ELM327 handles it badly.
   - Ford MS-CAN and GM SW-CAN need **STN chips or switched adapters**.
   - Older Lada, VAG, Hyundai and Kia units use **K-line KWP2000/ISO 9141/KW1281**.
   - Clones answer "?" to commands such as ATCF/ATCM and truncate long responses.

   Hence: **probe the adapter's capabilities first, then pick strategies.**

---

## 1. Project-by-project analysis

### 1.1 Generic ELM327 / OBD-II stacks

| Project | License | What it is | Discovery approach | Reuse |
|---|---|---|---|---|
| **AndrOBD** — https://github.com/fr3ts0n/AndrOBD | GPL-3.0 [V] | Android OBD app for any ELM327 (BT/USB/WiFi). Reads and clears DTCs, freeze frame, vehicle info, live data. Has a plugin system (MQTT, GPS, sensors) through AndrOBD-libplugin. | OBD-II legislated services only: functional broadcast, then whoever answers 7E8..7EF. No manufacturer ECU lists. | Ideas only: the module split (`library` = protocol and ELM layer, `androbd` = UI, `plugin` = IPC). Its DTC description translations are GPL data and must not be bundled. |
| **python-OBD** — https://github.com/brendan-w/python-OBD | GPL-2.0 [V] | Python OBD-II library. | Connect sequence [V], read from `obd/elm327.py`: auto-baud (`\x7F\x7F\r`, wait for `>`), ATZ, ATE0, **ATH1** (headers on so each line carries the ECU source), ATL0, AT RV (below 6 V means no power), ATSP0 + `0100`, then ATDPN. If the protocol is unknown, it loops `ATTP n` + `0100`. Builds an `ecu_map` from the responding headers of `0100` and maps the engine and transmission by tx_id. | Ideas only. The init sequence and the "headers-on, then group lines by sender" technique are standard practice. |
| **ELMduino** — https://github.com/PowerBroker2/ELMduino | MIT [V] | Arduino ELM327 client. | OBD-II only. | MIT, so the parsing of the ELM `>` prompt and the timeout handling can be ported with attribution. |
| **obd-java-api (pires)** — https://github.com/pires/obd-java-api | Apache-2.0 [V] (inactive since 2017) | Java command and response classes for OBD-II. | OBD-II only. | Apache-2.0, so its structure can be used. Its DTC parsing is legislated-only. |
| **freediag** — https://github.com/fenugrec/freediag | GPL-3.0 [V] | C scantool. Clean layered design: L0 hardware drivers (ELM, DUMB K-line), L1, L2 (ISO 14230, ISO 9141, J1850, VAG KW1281, MB1), L3 (J1979, KWP2000). | Mostly OBD-II. Has VAG and Mercedes "MB1" L2 drivers. | **Ideas only**, but the **L0/L1/L2/L3 layering is the best public model** of K-line plus CAN stacking. |
| **Ircama ELM327-emulator** — https://github.com/Ircama/ELM327-emulator | **CC-BY-NC-SA-4.0** [V] | Python ELM327 emulator. Emulates multiple ECUs at once, ISO-TP flow control and KWP2000. Ships a Toyota Auris Hybrid scenario. Its `obd_dictionary` tool records a car. | — | Useful for **internal** testing only (non-commercial clause). Get legal sign-off before using it inside a commercial company's CI. Shipping it or using derivatives is not allowed. |
| **Linux can327 driver docs** — https://docs.kernel.org/networking/device_drivers/can/can327.html | GPL kernel docs | Documents real ELM327 clone limitations. | — | Facts [V]: some "v1.5" clones cannot send RTR frames, and CSM is hard-wired on. Use this for our clone-quirk table. |

### 1.2 ISO-TP / UDS libraries (transport and service layers)

| Project | License | Notes |
|---|---|---|
| **python-udsoncan** — https://github.com/pylessard/python-udsoncan , docs https://udsoncan.readthedocs.io | **MIT** [V] | Complete ISO 14229 client: services, NRC handling, DID codecs, DTC (0x19) subfunction parsing, timing P2/P2*. **Can be ported to Kotlin with attribution.** The best model for our UDS layer. |
| **python-can-isotp** — https://github.com/pylessard/python-can-isotp | **MIT** [V] | ISO 15765-2 state machine. Supports Normal, Extended, Mixed, NormalFixed 11/29-bit addressing, plus FC/STmin/BS. Port the *addressing model* (its `Address` class covers every mode we need: Toyota and BMW extended, 29-bit 0x18DA). |
| **mdabrowski1990/uds** — https://github.com/mdabrowski1990/uds | MIT [V] | Python UDS. Its CAN addressing docs (https://uds.readthedocs.io/en/latest/pages/user_guide/can.html) explain Normal, NormalFixed (0x18DA/0x18DB), Extended and Mixed formats with examples [V]: functional 0x7DF, physical 0x7E0, 29-bit functional 0x18DB33F1. |
| **driftregion/iso14229** — https://github.com/driftregion/iso14229 | MIT [V] | Embedded C UDS client and server. Good for a test ECU simulator (for example on an ESP32 or STM32 bench). |
| **ecu_diagnostics / OpenVehicleDiag (rnd-ash)** — https://github.com/rnd-ash/ecu_diagnostics , https://github.com/rnd-ash/OpenVehicleDiag | GPL-3.0 [V] | Rust UDS/KWP2000 plus a Mercedes-focused diagnostics app over J2534 passthru. It parses Mercedes CBF/SMR-D database files, but those files are Daimler-proprietary and we cannot use them. **Ideas only.** Good reading for how KWP2000 on CAN differs from UDS (0x1A identification, 0x18 DTCs, 0x21 local IDs). |

### 1.3 Discovery / scanning tools (security research, but directly relevant)

**Caring Caribou** — https://github.com/CaringCaribou/caringcaribou — **GPL-3.0** [V]. Docs: https://github.com/CaringCaribou/caringcaribou/blob/master/documentation/uds.md

Discovery algorithm [V, from `modules/uds.py`]:
- For each arbitration ID in [min, max], it sends `10 01` (DiagnosticSessionControl, default session) as a single frame. The default max is 0x7FF, or the 29-bit max when min is extended.
- Any frame on **any** ID with `data[1] ∈ {0x50, 0x7F}` counts as a candidate. A negative response also proves a UDS server exists.
- **Verification:** filter to the responding ID, then resend to the last 5 IDs ("backtrack") with an extra 0.5 s delay. This finds which TX ID really triggered the response.
- **Auto-blacklist:** listen for N seconds before the scan. IDs that "look like" responses in normal traffic are blacklisted.
- Default inter-message delay is 10 ms.

Reuse: **ideas only**, and the ideas are universal: probe payload choice, backtrack verification, and blacklisting periodic traffic.

**gallia (Fraunhofer AISEC)** — https://github.com/Fraunhofer-AISEC/gallia — **Apache-2.0** [V]. Docs: https://gallia.readthedocs.io/en/latest/uds/scan_modes.html

Scan phases [V]: Discovery, Session, Service, Identifier.
- ISO-TP endpoint discovery sweeps a CAN ID range and sends a user-defined PDU, `3E 00` (TesterPresent) by default.
- It **first observes the bus to filter regular non-diagnostic messages**.
- It optionally reads a DID to describe each ECU.
- Commands include `discover-can-ids`, `discover-iso-tp-addr` (discovers *extended-address* ISO-TP endpoints) and `discover-endpoints` (DoIP).

Reuse: Apache-2.0 allows porting with attribution and a NOTICE file. **The best reference for our generic sweep.**

**SavvyCAN** — https://github.com/collin80/SavvyCAN — **MIT** [V]. Qt CAN analyser with ISO-TP and UDS decoders. A desktop companion for analysing our field logs. We can export our raw logs to SavvyCAN's CSV/GVRET formats so engineers can open them there.

**awesome-canbus / awesome-automotive-can-id (iDoka)** — https://github.com/iDoka/awesome-canbus , https://github.com/iDoka/awesome-automotive-can-id — **CC0** [V]. Curated index of tools, plus a CC0 collection of CAN IDs per brand. Public domain, so the data is freely usable. It is mostly broadcast IDs rather than diagnostic IDs.

### 1.4 comma.ai openpilot / opendbc / panda — the strongest permissive "multi-ECU per brand" source

Repos (all **MIT** [V]): https://github.com/commaai/opendbc , https://github.com/commaai/openpilot , https://github.com/commaai/panda

openpilot must identify a car by querying firmware versions from **many ECUs on many brands** at boot. That is exactly our "discover modules" problem, minus DTC reading.

Data model [V] (`opendbc/car/fw_query_definitions.py`):
- `Request(request: list[bytes], response: list[bytes], whitelist_ecus, rx_offset=0x8, bus, logging, obd_multiplexing)`
- `FwQueryConfig(requests, extra_ecus=[(Ecu, addr, subaddr)], non_essential_ecus, fw_version_regex, match_fw_to_car_fuzzy)`
- Constants:
  - `TESTER_PRESENT_REQUEST = 3E 00`
  - `UDS_VERSION_REQUEST = 22 F1 81`
  - `OBD_VERSION_REQUEST = 09 04`
  - `OBD_VIN_REQUEST = 09 02`
  - `KWP_VIN_REQUEST = 21 81`
  - `GM_VIN_REQUEST = 1A 90`
  - `STANDARD_VIN_ADDRS = [0x7e0, 0x7e2, 0x760, 0x7c6, 0x18da10f1, 0x18da0ef1]`
- Each "ECU address" is the tuple **(tx_addr, sub_addr|None)**. A sub-address means ISO-TP extended addressing. `rx_offset` defaults to +8, and VW overrides it to 0x6A.

Per-brand facts [V]:

**Toyota** (`toyota/values.py`):
- KWP version request `1A 88 01` and UDS version request.
- Hybrid ECU at **0x7e2 (KWP) or 0x7d2 (UDS)** depending on platform.
- SRS 0x780, transmission 0x701 or 0x7e1, HVAC 0x7c4.
- Further responders listed in a comment: Combination meter 0x7c0, HV battery 0x713/0x747, MG 0x716/0x724, Brake/EPB 0x730, SAS 0x7b3, EPS 0x7a0/0x7a1, 2nd SRS 0x784.
- **Central Gateway (0x750, sub 0x5f), BCM (0x750, sub 0x40), Telematics (0x750, sub 0xc7), EPB (0x750, sub 0x2c)**. These confirm Toyota's 0x750 extended-addressing gateway.
- Matches the Prius forum note "BCM at 750 gateway 40, replies at 758" (https://priuschat.com/goto/post?id=2630693).

**Hyundai/Kia/Genesis** (`hyundai/values.py`):
- ADAS 0x730, parking ADAS 0x7b1, HVAC 0x7b3, corner radar 0x7b7, CAN-FD cluster 0x7c6.
- Comment: CAN-FD ECUs can respond with frames larger than 8 bytes, which **ELM327 cannot receive**.
- Some ECUs are lost without comma power, meaning they sit behind the gateway.

**Ford** (`ford/values.py`):
- PCM 0x7e0, with the note "unlikely to get a response from behind the gateway".
- Gear shift module 0x732, APIM 0x7d0.
- As-built blocks are read via `22 DE00+n`.

**VW** (`volkswagen/values.py`):
- UDS `22 F187`-style queries.
- **`VOLKSWAGEN_RX_OFFSET = 0x6a`**, so the response ID is request + 0x6A (e.g. gateway 0x710→0x77A, ABS 0x713→0x77D). Engine and transmission use the standard +8.
- Camera at 0x74f.

What to reuse: MIT, so we may **port the address tables and the request/response structure** with attribution. This is the most valuable legally reusable data for Toyota, Hyundai/Kia, VW, Ford, GM, Honda, Nissan, Mazda, Subaru, Chrysler and Tesla. Coverage is biased to ADAS-related ECUs (engine, ABS, EPS, radar, camera, SRS, transmission, HVAC), so it is a seed list, not complete.

### 1.5 VAG (VW/Audi/Skoda/Seat)

**jazdw TP2.0 write-up** — https://jazdw.net/tp20 [V]. Public reverse-engineered spec, also bundled into OVMS:
- **Channel setup** on CAN **0x200**: `[dest_logical_addr, C0, 00, 10, 00, 03, 01]`. The ECU replies on **0x200+addr** with `D0` and the TX ID to use (e.g. 0x740).
- **Parameters:** `A0 BS T1 FF T3 FF`, answered by `A1 …`.
- **Keepalive** `A3`, **break** `A4`, **disconnect** `A8`.
- **Data frames:** high nibble opcode (0 = more and ACK, 1 = last and ACK, 2 = more without ACK, 3 = last without ACK; B/9 = ACK or not-ready), low nibble sequence. The first frame carries a 2-byte length. The payload is **KWP2000** (e.g. `10 89` StartDiagnosticSession, `21 01` readDataByLocalId).
- **Module logical addresses** are the classic VAG "addresses" (01 engine, 02 transmission, 03 ABS, 08 HVAC, 09 central electrics, 15 airbag, 17 instruments, 19 gateway, 44 steering assist…) [K].

Projects:

| Project | License | Notes |
|---|---|---|
| **vag-blocks (jazdw)** — https://github.com/jazdw/vag-blocks | GPL-3.0, archived 2022 [V] | Proves TP2.0 can run over an ELM327. The author warns the ELM327 "raw CAN mode is limited", so it is unstable and reaches up to about 12 samples per second. Layers: `elm327.cpp` / `tp20.cpp` / `kwp2000.cpp`. Ideas only. |
| **OVMS3** — https://github.com/openvehicles/Open-Vehicle-Monitoring-System-3 | **MIT for the majority of code** [V, LICENSE file] | Vehicle poller entries are `{txid, rxid, type, pid, polltimes[], bus, protocol}` with protocol ∈ {ISOTP_STD, **ISOTP_EXTADR**, **ISOTP_EXTFRAME**, **VWTP_20**} [V, https://docs.openvehicles.com/en/latest/components/poller/docs/Intro.html + ovmsdev list]. For VWTP_20, txid = gateway base 0x200 and rxid = 8-bit logical ECU, and channels stay open between polls. BMW i3 example uses `0x6F1 + ext addr 0x07 → 0x607F1` with ISOTP_EXTADR. **A permissive design reference for a unified multi-transport poller.** |
| **I-CAN-hack pq-flasher** — https://github.com/I-CAN-hack/pq-flasher | MIT [V] | Python TP2.0 transport and KWP2000 libraries (PQ35 EPS). **Portable with attribution.** |
| **kw1281test** — https://github.com/gmenounos/kw1281test | **MIT** [V] | C#/.NET. KW1281 (5-baud "slow init") plus some KWP2000 over KKL cable: ReadIdent, ReadFaultCodes, ClearFaultCodes, **AutoScan**, coding, actuator tests. Does not work through an ELM327. **The best permissive reference for KW1281 framing (block counter, ACK blocks, fault code block 0x07/0xFC).** |
| **PyVCDS** — https://github.com/MartijnD92/PyVCDS | No clear license | TP2.0 + KWP2000 over socketCAN. Treat as all rights reserved: read only. |
| **NefMoto** — https://github.com/NefMoto/NefMotoOpenSource | GPL-3.0 [V] | ME7 KWP2000/KWP1281 flashing. Ideas only. |

Deep OBD's docs say VAG mode supports only KWP2000, KWP1281 and TP2.0, for cars up to about 2012/2017. It **needs the replacement ELM327 firmware** (PIC18F25K80) or their own D-CAN/K-line adapter [V]. Industry consensus is that a stock ELM327 cannot do TP2.0 or KW1281 reliably.

Modern MQB/MLB-evo cars use **UDS on 11-bit CAN** with the +0x6A response offset (gateway J533 at 0x710/0x77A) [V via opendbc]. These work fine on a good ELM327. Car Scanner's coding feature list covers MQB, PQ26 and MLB-EVO [V, https://www.carscanner.info/coding/], which matches that split.

### 1.6 BMW

**EdiabasLib / Deep OBD (Ulrich Holeschak)** — https://github.com/uholeschak/ediabaslib — **GPL-3.0** [V].
- A .NET interpreter for BMW EDIABAS `.PRG/.GRP` job files, plus the Deep OBD Android app.
- Protocols [V]: BMW D-CAN, BMW-FAST, KWP2000, DS2, DS1, Concept 1/2/3; VAG KWP2000, KW1281, TP2.0.
- Adapters [V]:
  - FTDI "INPA" K+DCAN cables.
  - ELM327 with PIC18F2480 (v1.4b/1.5/2.1 original), **D-CAN only**.
  - PIC18F25K80 ELM327 with **replacement firmware**: D-CAN plus K-line at 115200 baud.
  - ENET for F-series.
- ECU list: "Press Read and wait until the ECU list is populated" in the configuration generator. ECU identification is driven by the EDIABAS vehicle-series files.
- Reuse: **ideas only**. The PRG/GRP files are BMW-proprietary and not ours to redistribute. The docs on ELM327 limits and on adapter firmware are valuable reading.

BMW addressing facts [V, Quantex https://www.quantexlab.com/en/develop/elm327.html]:
- Tester CAN ID **0x6F1**, with ISO-TP **extended addressing** (first data byte = target ECU).
- ECU replies on **0x600+ECU** with the first byte = 0xF1.
- ELM327 recipe: `ATSH6F1`, `ATFCSH6F1`, `ATFCSD<ecu>30<bs><st>`, `ATFCSM1`, `ATCRA6<ecu>`, `ATCEA<ecu>`. **ATCEA can be changed on the fly**, so sweeping ECU 0x00..0xFF with ATCEA plus ATCRA is cheap.
- The functional address on BMW is 0xDF [K].
- DME = 0x12 [K/forum: https://racechrono.com/forum/d/2681-2681].

Car Scanner's adapter page warns that old Vgate iCar Pro BLE firmware (before 4.1.02) "does not correctly support CAN Extended addressing, which Toyota and BMW use" [V, https://www.carscanner.info/choosing-obdii-adapter/].

F/G-series cars from about 2013 also offer HSFZ/DoIP over ENET. Quantex documents ELM-style AT commands for DoIP (https://quantexlab.com/en/develop/elm327_doip.html) [V]. That is a future option, out of scope for ELM327 BLE.

### 1.7 Renault / Dacia / Nissan / Lada Largus and Vesta (Renault-Nissan components)

| Project | License | Notes |
|---|---|---|
| **ddt4all** — https://github.com/cedricp/ddt4all | GPL-3.0-or-later [V] | Python/Qt. Supports ELM327 (original and clone, PIC18F25K80 recommended), Vlinker FS, Vgate iCar Pro, OBDLink SX/EX, ELS27 and DoIP. Auto-scans ECUs. ECU definitions are XML in a ZIP derived from Renault's DDT2000 database, which is **proprietary and not ours to reuse**. A public issue shows scans failing when "it can't retrieve the functional address", which shows that its scan is driven by **per-model ECU lists with functional addresses** (https://github.com/cedricp/ddt4all/discussions/1120) [V]. |
| **pyren** — mirror https://github.com/emtee40/pyren-OBD2 | GPL, unclear [V partially] | Renault CLIP-like mode: `--scan` scans every ECU in the car, reads DTCs with descriptions, clears them, and runs configuration and actuator tests. Uses a CLIP/DDT2000 database. Ideas only. The database is proprietary. |

Takeaway: Renault uses its own ECU address table (KWP2000-on-CAN and UDS) per vehicle family.
- Open tools rely on proprietary Renault databases. **We must build our own address lists from field logs and public forum posts** [H].
- Lada Largus (Logan platform) and Lada Vesta/XRAY share many Renault/Nissan-derived or Bosch modules [H]. The same scanning machinery should cover both families.

### 1.8 PSA / Stellantis

**arduino-psa-diag (ludwig-v)** — https://github.com/ludwig-v/arduino-psa-diag — GPL-3.0 [V].
- Sends UDS and KWP frames over CAN to Peugeot, Citroen, DS and Opel.
- Its README and wiki document PSA diagnostic CAN ID pairs per ECU (BSI, CMM…).
- Ideas only for code. Diagnostic CAN IDs are **facts**, not copyrightable expression, so we may independently confirm and record them. They are still best re-verified from our own logs.

### 1.9 Ford / GM (MS-CAN and SW-CAN)

FORScan forum [V, https://forum.forscan.org/viewtopic.php?p=4]:
- HS-CAN uses OBD pins 6/14 and MS-CAN uses pins **3/11**.
- A DPDT switch-modded ELM327 or **STN11xx/STN2120** chips are needed for MS-CAN. Most modules (engine, ICU…) are on HS-CAN.
- FORScan only asks about the switch when the vehicle model is known to have MS-CAN modules. That is a profile-driven decision.
- FORScan's own forum keeps a list of "known problems with china clones" (https://forum.forscan.org/viewtopic.php?p=5601) [V].

ScanTool.net / OBD Solutions [V, https://www.scantool.net/stn2120/ , https://obdsol.com/solutions/chips/stn2120]:
- STN2120 and STN1170 add **SW-CAN (GM) and MS-CAN (Ford)**.
- The command set is shared across STN11xx/21xx, documented in the **OBDLink Family Reference and Programming Manual (FRPM)**.
- Proprietary GM/Ford codes "must be obtained independently".

Our design consequence: when the adapter reports `STI`/`STDI` (STN), enable `STP`-family protocol selection for MS-CAN and SW-CAN. Otherwise, mark those modules "requires MS-CAN-capable adapter". Exact STN command names should come from the FRPM PDF.

GM legacy (GMLAN) uses non-UDS DTC services (`A9` ReadDTCByStatus/packets). opendbc exposes `GM_VIN_REQUEST = 1A 90` [V]. Treat GM as a KWP-like variant in our service layer [K].

### 1.10 Hyundai / Kia

- Car Scanner documents Hyundai/Kia **service functions** (ABS bleed, AT adaptation reset, ECU adaptation reset, service reminder) [V, https://www.carscanner.info/hyundai-kia-service-functions/]. So brand-specific UDS routines on ABS, TCM and ECM are reachable through ELM327.
- Address seeds come from opendbc (MIT) [V, listed above] and from the EV community:
  - **JejuSoul/OBD-PIDs-for-HKMC-EVs** (https://github.com/JejuSoul/OBD-PIDs-for-HKMC-EVs) has no license, so treat it as facts only. It covers Torque PID files with headers such as 7E4 BMS and 7C6 cluster [K].
  - **evDash** (https://github.com/nickn17/evDash) is **MIT** [V]. It contains Hyundai/Kia EV ECU addresses and PIDs in code, so it is portable with attribution.
  - **EVNotify** has a custom license (NOASSERTION) and should be avoided.
- CAN-FD platforms (2021+ Ioniq 5, EV6, new Sportage and Tucson ADAS buses) send frames larger than 8 bytes on some buses [V, opendbc comment]. **ELM327 cannot read those.** The OBD port is usually still classic CAN through the gateway, but expect gaps.
- Older (pre-2010) Hyundai/Kia have K-line KWP2000 on some modules [K].

### 1.11 Toyota / Lexus

- Facts come from opendbc [V] (see 1.4): 0x7E0/7E1/7E2/7D2, 0x780, 0x7C0, 0x7B0-0x7B3, **0x750 + sub-address** for body, gateway and EPB.
- Toyota uses KWP2000-on-CAN (`1A 88 01` ident, `21` local ID) on many older modules and UDS on newer ones.
- Car Scanner: Toyota/Lexus CAN cars are supported for coding [V]. Toyota needs CAN extended addressing support in the adapter [V].
- Torque users do Toyota body modules with header `750` and extended byte `40`, which corroborates the above (https://priuschat.com/goto/post?id=2630693) [V].

### 1.12 Mercedes-Benz

- No good permissive open source exists. OpenVehicleDiag / ecu_diagnostics (GPL) target Mercedes via J2534 and the proprietary CBF/SMR-D files.
- Public facts: KWP2000-on-CAN on W203/W211-era cars, UDS on newer ones. Diagnostic IDs are in the 0x7xx and 0x6xx range, model-specific [H].
- Plan: **field logs plus a generic sweep**. There is no shortcut.

### 1.13 Lada (VAZ) and other Russian domestic brands (UAZ, GAZ)

- **OpenDiag Mobile** is closed source: Android, ELM327 or K-line USB. Its primary targets are VAZ/GAZ/UAZ/ZAZ, with paid Renault, Hyundai and Daewoo/Chevrolet modules [V, https://toolfox.ru/services/s/opendiag]. Its model shows that the Russian market expects **Январь/Bosch/Itelma ECU variants over K-line** to work through ELM327.
- Forum evidence [V, https://www.drive2.ru/l/8421611/ and the OpenDiag thread]:
  - Январь 7.2 on a VAZ-2110 works with ELM327 v1.5 in OpenDiag, while a "v2.1" clone failed.
  - Torque Pro does not work with Январь 7.2 out of the box.
- Protocol facts (ISO 14230 KWP2000 on K-line) [V, https://github.com/salvatoreBosco/KWP2000_arduino + ISO 14230]:
  - Fast init: 25 ms low / 25 ms high wake-up.
  - `StartCommunication` `81` with header format byte, target, source `F1`, checksum = sum mod 256.
  - Example `C1 33 F1 81 66` (functional 0x33), answered by `83 F1 10 C1 xx xx cs` from engine **0x10**.
  - ELM327 handles this with `ATSP5` (ISO 14230 fast init), `ATSH 81 10 F1` or `ATSH C1 33 F1`, `ATFI`, `ATIIA`, `ATKW0` and `ATWM` (wake-up message) [V, Quantex].
- Pre-2018 Lada ECUs (Январь 5.1/7.2, Bosch M7.9.7/ME17.9.7, Itelma M73/M74/M75) use K-line KWP2000 with mostly manufacturer-specific local-ID data and DTC services (`18`/`17`/`14`) [K/H].
- Vesta, XRAY and Granta FL ABS/SRS/EPS/BCM: K-line on older cars, CAN on newer ones [H]. **We need field logs here.** This is our biggest gap and also our biggest market. A commercial competitor (MotorData) advertises up to 25 ECUs on Vesta [V, https://apps.apple.com/ru/app/motordata-obd-elm-car-scanner/id1503589009], so it is feasible through ELM327.
- Russian K-line ECU address conventions to verify [H]: 0x10 engine, 0x28 ABS, 0x58 airbag, 0x40 BCM, 0x60 instruments.

### 1.14 Chinese brands (Haval/GWM, Chery/Omoda/Jaecoo/Exeed/Tenet, Geely/Belgee, Changan, Jetour, FAW, JAC/Moskvich)

- No meaningful open-source projects found.
- Car Scanner release notes show model-specific connection profiles being added: FAW Bestune T77, Kia Sportage NQ5 HEV/PHEV, Jetour X70/X90/X95 [V, apkmirror "What's new" pages, e.g. https://www.apkmirror.com/apk/car-scanner-llc/car-scanner-elm-obd2/car-scanner-elm-obd2-1-121-0-release]. So commercial apps build these per model from field data.
- Chinese platforms are mostly **UDS on 11-bit CAN, 500 kbit, with a central gateway** [K/H]. Many follow the Bosch/Continental/Delphi supplier norms (0x7E0/7E8 engine, 0x7E1/7E9 TCM, ABS/ESP 0x7xx). Some use 29-bit 0x18DAxxF1 [H].
- **This is where generic discovery pays off most.** Our sweep plus recording mode is the main strategy.

### 1.15 Community vehicle data

- **OBDb** — https://github.com/OBDb , browse at https://obdb.community — per-make/model JSON "signalsets" (headers, PIDs, scaling) and DTCs. Repos are **CC-BY-SA-4.0** [V]. Using the data obliges attribution and **share-alike for the derived dataset**. It does not cover our app code, but any modified data we ship would have to be CC-BY-SA. Consider using it only for reference or confirmation, or keep it as a separately licensed downloadable dataset.
- **evrenonur/obd2-elm327-pid-reference** — https://github.com/evrenonur/obd2-elm327-pid-reference — has no license, so it is read-only facts.
- **mytrile/obd-trouble-codes** — https://github.com/mytrile/obd-trouble-codes — MIT [V]. Generic SAE P/B/C/U code descriptions in JSON/CSV/SQLite. Usable as a seed for the generic DTC text database. Quality is variable.

---

## 2. How commercial ELM327 apps describe "all modules" (from their own public material)

**Car Scanner ELM OBD2**
- Sources: https://www.carscanner.info/faq/ , https://www.carscanner.info/choosing-obdii-adapter/ , https://www.carscanner.info/compatibility/ , app-store and apkmirror notes.
- **Connection profiles** per brand and model. When the model is missing, use "brand + OBD-II".
- Brand extras come from profiles: Toyota, Mitsubishi, GM/Opel/Chevrolet, Nissan/Infiniti, Renault/Dacia, Hyundai/Kia, Mazda, Ford, Subaru, VAG and others.
- The sensor list "is requested on each connection from your car ECU", which means dynamic discovery of supported PIDs and DIDs.
- Adapter quality gates multi-ECU: clones "hard-coded to a standard ECU address", request/response length limits, broken extended addressing, and dropped frames.
- Writing or coding "places special demands on the adapter".
- Generic ELM327 "can't access manufacturer mode" unless the app knows the manufacturer protocol. Manufacturer documentation is closed and costs $10,000–$100,000.

**Torque Pro**
- Multi-ECU work is user-driven: each custom PID has an "OBD Header to Use" (e.g. 7E0, 7E4, 750), with CSV PID packs per vehicle [V, forum threads e.g. https://www.t6forum.com/threads/vw-t6-custom-pid-codes-for-dpf.33964/post-469108].
- There is no brand profile engine. Generic DTC reading is OBD-II scope.

**OBD Auto Doctor**
- Officially "depending on your car, you might be able to talk to the transmission controller in addition to the engine controller" [V, https://obdautodoctor.com/features]. That means OBD-II functional scope, plus a large DTC description database (about 18k codes, https://www.obdautodoctor.com/blog/thousands-of-new-obd2-trouble-codes-added/).

**Deep OBD**
- ECU list per vehicle series from EDIABAS data [V].

**FORScan**
- Profile-driven: MS-CAN modules are probed only on models known to have them [V].

**OpenDiag Mobile / MotorData**
- Paid per-brand "blocks/plugins" [V].
- MotorData advertises dealer-like reading of P0/P1/P2/P3/B/C/U codes for up to 25 ECUs on Lada Vesta [V].

**Conclusion:** the mature apps that achieve "all modules" on ELM327 (Car Scanner, MotorData, OBDeleven with its own dongle, Carista with its own dongle, BimmerLink with ATCEA) all rely on **curated per-platform ECU tables plus manufacturer DTC services**. Generic OBD apps (Torque, OBD Auto Doctor, AndrOBD) stop at OBD-II scope.

---

## 3. Key protocol facts we must implement (reference sheet)

**OBD-II functional requests** [K, ISO 15765-4 / SAE J1979]:
- 11-bit: send to **0x7DF**, responses come from 0x7E8..0x7EF (physical request = response − 8).
- 29-bit: send to **0x18DB33F1**, responses come from 0x18DAF1xx (physical request 0x18DAxxF1).
- Services: 03 stored, 07 pending, 0A permanent, 04 clear, 09 02 VIN.
- On K-line, ISO 9141/14230 functional address is 0x33, tester 0xF1.

**UDS DTCs** [K, ISO 14229-1]:
- `19 02 <mask>` (reportDTCByStatusMask; mask 0xFF or 0x2F/0x09), `19 0A` (supported DTCs), `19 06/04` (extended data and snapshot), `14 FF FF FF` (clear).
- DTCs are 3 bytes + 1 status byte.
- Format identifier from `19 01` → `59 01 <availMask> <format> <count>`: 0x00 = ISO 15031-6 (J2012 2 bytes + FTB), 0x01 = ISO 14229-1, 0x02 = J1939, 0x04 = ISO 11992.
- Decode the first 2 bytes as P/C/B/U + 4 hex digits and the third byte as the **failure type byte** (J2012-DA).

**KWP2000 DTCs** [K, ISO 14230-3]:
- `18 00 FF 00` (readDTCByStatus, all groups) → `58 n [DTC_hi DTC_lo status]×n`.
- `17` (readStatusOfDTC), `13` (readDTC), `14 FF 00` (clear).
- Ident via `1A 80/86/87/88/9x`.
- Manufacturers vary the group parameter (0xFF00, 0x0000) and the status masks.

**KW1281 (VAG K-line)** [K, kw1281test]:
- 5-baud init at the module address, sync byte 0x55, key words.
- Block protocol with a counter and complement echo of every byte.
- Fault codes come in block title 0x07 → 0xFC with 3 bytes per code: VAG 5-digit decimal code (2 bytes) + elaboration (1 byte).
- **A stock ELM327 cannot do this.** It needs a KKL cable or an adapter with replacement or advanced firmware.

**VW TP2.0** [V, jazdw]: see 1.5. On a stock ELM327 it needs raw-CAN mode (`ATCAF0`, manual headers `ATSH 200`/`ATSH 7xx`, `ATCRA`) and ACK timing management from the phone. That is fragile. Prefer adapters with native support (OBDLink?, vLinker, firmware-modded ELM) and detect capability at runtime [H].

**Extended addressing (Toyota 0x750, BMW 0x6F1)**: `ATCEA hh` plus `ATCRA` plus flow-control `ATFCSH/ATFCSD/ATFCSM1` [V, Quantex]. Older ELM327 versions (before 1.4b/2.0 genuine) lack CEA [K]. Probe it.

**VW UDS**: response ID = request + 0x6A [V, opendbc].

**Ford HS-CAN and MS-CAN**: 0x7E0 PCM, 0x7E1 TCM, 0x760 ABS, 0x720 IPC, 0x726 BCM, 0x737 RCM, 0x7D0 APIM, 0x732 GSM [V for 0x7E0, 0x7D0, 0x732, 0x760 from opendbc; others K/H]. MS-CAN needs STN or a switch.

**Clone quirks to detect** [V, FORScan forum, kernel can327 doc, Car Scanner]:
- Unknown-command "?" for ATCF, ATCM, ATPPS, ATCEA and others.
- Truncated input after N chars.
- Freezes on long multi-frame responses.
- No RTR.
- Fixed CSM.
- Hard-coded headers (ATSH ignored).
- Fake versions ("v1.5" or "v2.1" strings are meaningless).

---

## 4. Licensing guidance for a closed-source commercial Kotlin app

| License | What we may do |
|---|---|
| **MIT / BSD / Apache-2.0** (opendbc/openpilot/panda, udsoncan, can-isotp, gallia, kw1281test, pq-flasher, OVMS3 main code, SavvyCAN, ELMduino, obd-java-api, evDash, iso14229, mdabrowski uds, mytrile DTC data) | Port and translate code and data into Kotlin. **Keep copyright notices** in an "Open-source licenses" screen. Apache-2.0 also requires preserving NOTICE files and marking modified files. Note that gallia carries Apache patent-grant terms. |
| **CC0** (awesome-automotive-can-id, awesome-canbus) | Free use. |
| **CC-BY-SA-4.0** (OBDb, jakka351 FG-Falcon) | You may use the data, but a shipped derived dataset must stay CC-BY-SA with attribution. Keep it as a separate downloadable data pack, or use it only for cross-checking our independently gathered data. |
| **CC-BY-NC-SA** (Ircama emulator) | Internal research only, and even that needs legal review. Never ship. |
| **GPL-2/3, LGPL** (AndrOBD, python-OBD, EdiabasLib/Deep OBD, ddt4all, pyren, freediag, Caring Caribou, vag-blocks, NefMoto, OpenVehicleDiag, ecu_diagnostics, WiCAN fw, arduino-psa-diag; LGPL jagl16/obd2) | **Do not copy code, structure-level translations or bundled data files.** Reading them to understand protocols, then writing our own implementation from the *standards and our own logs*, is legitimate (clean-room style). Bare facts are not copyrightable: a CAN ID, a service byte, a timing value. Even so, prefer to confirm such facts from permissive sources or our own captures and record the provenance. |
| **No license** (PyVCDS, JejuSoul PIDs, evrenonur reference) | All rights reserved. Facts only, after independent confirmation. |
| **Proprietary OEM databases** (EDIABAS PRG, Renault DDT2000/CLIP, Mercedes CBF, VAG ODX/label files) | Never ship or derive from them. |

Keep a `PROVENANCE.md` for every ECU table entry, with the source URL or log ID. This protects us if ownership of a data entry is ever questioned.

---

## 5. Recommended architecture

```
┌──────────────────────────────────────────────────────────────────────────┐
│ UI / Explanation layer (our DTC texts, severity, advice, LLM-assisted)   │
├──────────────────────────────────────────────────────────────────────────┤
│ Scan Orchestrator: "Quick (OBD)", "Full (profile)", "Deep (sweep)"       │
│   - builds a ScanPlan from VehicleContext + AdapterCaps + BrandProfile   │
│   - parallel-safe sequencing, retries, per-ECU timeouts, progress        │
├──────────────────────────────────────────────────────────────────────────┤
│ Diagnostic service layer (pure Kotlin, transport-agnostic)               │
│   ObdService (J1979)  UdsClient (14229)  KwpClient (14230-3 & on-CAN)    │
│   Kw1281Client (VAG)  GmlanClient (A9…)  — each exposes:                 │
│   identify(), readDtcs(), clearDtcs(), readFreeze(), readDid()           │
├──────────────────────────────────────────────────────────────────────────┤
│ DTC decoders: J2012 (P/C/B/U+FTB), UDS status bits, KWP status,          │
│   VAG 5-digit + elaboration, BMW hex fault codes, Toyota/Lada manuf.     │
├──────────────────────────────────────────────────────────────────────────┤
│ Transport layer (Kotlin, coroutine-based)                                │
│   IsoTpTransport(addressing = Normal11 | Normal29 | NormalFixed29 |      │
│                  Extended(sa,ta) | Mixed)  — delegates to adapter FC     │
│   VwTp20Transport (channel mgmt, ACK/keepalive)                          │
│   KLineTransport (ISO 9141 slow init, ISO 14230 fast init, KW1281 5-baud)│
│   GmSwCan / FordMsCan selectors (STN only)                               │
├──────────────────────────────────────────────────────────────────────────┤
│ Adapter driver layer                                                     │
│   ElmDriver (genuine/clone), StnDriver (ST commands), VlinkerDriver,     │
│   (future) DoIP/ENET, J2534/socketCAN for bench                          │
│   + AdapterCapabilityProbe  + CommandQueue (single in-flight, prompt '>')│
├──────────────────────────────────────────────────────────────────────────┤
│ Link layer: BT SPP, BLE GATT (various UUIDs), Wi-Fi TCP, USB serial      │
├──────────────────────────────────────────────────────────────────────────┤
│ Recorder (tap at link layer + structured events at every layer)          │
└──────────────────────────────────────────────────────────────────────────┘
Data (versioned, downloadable, signed): BrandProfiles/*.json, DtcTexts/*.db,
AdapterQuirks.json, ProvenanceIndex
```

### 5.1 Adapter capability detection (run once per adapter, cache by MAC or serial)

1. Reset and identify:
   - `ATZ`/`ATWS`, then `ATI`, `AT@1`, `STI`/`STDI` (STN), `ATPPS` (genuine ELM lists PPs).
   - `vti`/`ATI` patterns for vLinker and OBDLink.
2. Feature probe. Each command is tested with a harmless argument, and the result recorded as OK, "?" or no answer:
   - `ATSH 7E0`, `ATSH 18DB33F1`, `ATCP 18`
   - `ATCRA 7E8`, `ATCF 7E8`/`ATCM 7FF`, `ATCEA 40` (then `ATCEA`)
   - `ATFCSH 7E0`, `ATFCSD 30 00 00`, `ATFCSM 1`, `ATCAF0`, `ATAL`
   - `ATSP 5` + `ATFI` capability (only with ignition on and user consent)
   - `ATSI`, `ATIIA 10`, `ATWM`, `ATKW0`
   - STN: `STP 53`-style MS-CAN/SW-CAN protocols (consult FRPM)
3. Behavioural probes on the car:
   - A 64-byte+ multi-frame read (e.g. `09 02` VIN, `22 F1 90`) checks for truncation and freezes.
   - Header switching speed.
   - A max line-length test.
   - Ignored-header detection: send `ATSH 7E1`, request `3E 00`, and check whether the response appears from 7E9 or still from 7E8.
4. Output an `AdapterCaps` object, for example `{canStd, can29, extAddr, userFc, rawCan(CAF0), kLineFast, kLineSlow, kw1281, tp20, msCan, swCan, maxFrameLen, reliabilityScore}`. Show a clear UI message such as "Your adapter can't reach Toyota body modules; recommended: …", following Car Scanner's approach.

### 5.2 Transports

- **ISO-TP:** prefer the adapter's built-in ISO-TP (`ATCAF1`, auto FC) for standard and 29-bit. Use `ATCEA` + `ATFCSM1` for extended addressing.
- **Raw-CAN fallback** (`ATCAF0` + manual FC) only for adapters that pass the probe. Port the state machine model from python-can-isotp (MIT).
- **VW TP2.0:** implement from the jazdw spec. Enable only for adapters with proven raw-CAN timing (vLinker, OBDLink, firmware-modded ELM) or native TP2.0 support. Mark it beta. KWP2000 runs on top.
- **K-line:**
  - ISO 14230 fast init via `ATSP5`/`ATSH 81 xx F1`/`ATFI`.
  - ISO 9141 slow init via `ATSP3`/`ATIIA`/`ATSI`.
  - KW1281 only on adapters with special firmware or a USB KKL. Port framing ideas from kw1281test (MIT).
- **One CommandQueue:** exactly one command in flight, parse until the `>` prompt, per-command timeout, and a watchdog that resets the adapter (`ATWS`) after N consecutive "NO DATA" or "CAN ERROR" responses.

### 5.3 Diagnostic services

- UDS client modelled on udsoncan (MIT): services 10/11/14/19/22/27/2E/31/3E and the NRC table (0x78 responsePending loop, 0x21 busy, 0x11/0x12 unsupported).
- KWP2000 client: 10/1A/18/17/14/21/22/3E, on both K-line and CAN.
- OBD: 01/02/03/04/07/09/0A plus Mode 06.
- Each ECU gets an **`EcuDialect`** describing:
  - which DTC service works (`19 02 mask` / `18 00 FF 00` / `03` / VAG `07`);
  - which session it needs (default / `10 03` / `10 89` / `10 C0`);
  - which ident to use (`22 F190/F187/F18C`, `1A 87/9B`, `21 81`).

  Auto-negotiate the dialect: try UDS `19 02 FF`. On NRC 0x11 or no response, fall back to KWP `18 00 FF 00`, then to `13`.

### 5.4 Brand profiles (data, not code)

A JSON schema roughly like this:

```json
{
  "brand": "toyota", "platforms": ["tnga-k", "..."], "years": [2012, 2026],
  "bus": [{"id":"hs", "proto":"ISO15765_11_500"}],
  "gateways": [{"name":"CGW", "tx":"0x750", "sub":"0x5F"}],
  "ecus": [
    {"name":"Engine","tx":"0x7E0","rx":"0x7E8","addr":"normal","dialect":"uds|kwp","dtc":"19 02 2F"},
    {"name":"BCM","tx":"0x750","sub":"0x40","rx":"0x758","addr":"extended","dialect":"kwp","dtc":"18 00 FF 00"},
    {"name":"Airbag","tx":"0x780","rx":"0x788","addr":"normal","dialect":"kwp"}
  ],
  "vinRules": ["JT*", "..."],
  "sources": ["opendbc toyota/values.py MIT", "log#1234"]
}
```

- Vehicle identification: VIN (09 02, or `22 F190`) → WMI/VDS → brand/platform → profile.
- If the VIN is unknown, the user picks a profile, like Car Scanner's "brand + OBD-II" fallback.
- Profiles are **downloadable and updatable** without an app release, and signed.
- Seed data:

  | Brand | Seed sources |
  |---|---|
  | Toyota, Hyundai/Kia, VW, Ford, GM, Honda, Nissan, Mazda, Subaru | opendbc (MIT) |
  | Hyundai/Kia EVs | evDash (MIT) |
  | BMW | Quantex and public forum facts (0x6F1, ATCEA) |
  | VAG KWP/TP2.0 addresses | jazdw (public facts) |
  | Everything else | Our field logs |

### 5.5 Generic discovery sweeps (for unknown or Chinese or Lada cars, and to fill profile gaps)

1. **Passive listen** for 1–2 s (`ATMA` if supported, otherwise skip) to blacklist periodic IDs (gallia/Caring Caribou idea). Many cars' OBD gateways are silent, which is fine.
2. **Functional broadcast:**
   - `7DF: 01 00` and `7DF: 3E 00`/`10 01`, with headers on.
   - 29-bit `18DB33F1: 01 00`.
   - Collect every responder.
3. **11-bit physical sweep:**
   - Send `3E 00` (or `10 01`) to 0x700–0x7FF, step 1. Accept a response on tx+8, tx+0x6A, tx+0x20, tx+0x40 or any ID with `0x7E`/`0x50`/`0x7F` semantics.
   - To learn the true pairing, verify with backtrack and filter (Caring Caribou idea).
   - Timeouts: about 50–100 ms per ID through ELM (`ATST` tuned). A full sweep takes about 30 s.
   - Prioritise likely ranges per brand.
4. **Extended-addressing sweep:** on adapters with CEA, for gateways known to use it (0x750 Toyota, 0x6F1 BMW), set CEA 0x00..0xFF and look for `0x7E`/`0x50`/`0x7F`/`0x5A`/`0x61`.
5. **29-bit NormalFixed sweep:** `18DA<xx>F1` for xx = 0x00..0xFF, a 256-ID sweep.
6. **K-line sweep:**
   - ISO 14230 fast init to the physical addresses in a list: 0x10, 0x11, 0x17, 0x18, 0x28, 0x29, 0x40, 0x58, 0x60… (extendable).
   - ISO 9141 slow init at 0x33.
   - KW1281 addresses 0x01..0x7F only if the adapter is capable.
7. **TP2.0 sweep:** channel setup to logical 0x01..0x7F on 0x200 (only TP2.0-capable adapters).
8. For each found endpoint: identify (`22 F18C/F190/F187/F197`, `1A 87/9B`), pick the dialect, read DTCs, and propose the result as a **profile candidate** uploaded with the user's consent.

The sweep must be **opt-in**, with a "Deep scan" label and a warning (bus load). Never send write or IO-control services during a sweep. Use read-only probes (3E, 10 01, 22 F1xx) only.

### 5.6 DTC decoders

- **J2012 / ISO 15031-6:**
  - 2 bytes → P0xxx/C/B/U.
  - The 3rd UDS byte is the FTB (J2012-DA).
  - Status bits: testFailed, pending, confirmed, MIL, and others.
- **KWP:** 2-byte code + status. Some manufacturers send 3-byte or BCD codes, so make this a dialect option.
- **VAG:** 5-digit decimal (KW1281/TP2.0-KWP) + elaboration. UDS VAG cars use standard 3-byte codes plus VAG "Fehlerort" mapping, which needs our own texts.
- **BMW:** hex fault codes (e.g. 2A87), with texts that are per ECU. Build our own.
- **Toyota and Lada:** manufacturer P1xxx/B1xxx/C1xxx texts are in our own explanation DB.
- Explanation layer key: `(brand, ecuType, dtcCode, ftb?) → our text`. Fall back to the generic SAE text, then to a "manufacturer-specific code, here's what the letter and number mean" text.

### 5.7 Recording / logging mode (field testers)

- Record **every byte at the link layer**, with direction and monotonic ns timestamps. Also record **structured events** from every upper layer: AT command → reply, ISO-TP PDUs, service request and response, decode results, and errors.
- Format: JSON Lines or a compact protobuf. Include:
  - app version, phone model, adapter ID and caps, firmware strings;
  - VIN (**hashed or truncated per privacy policy, plus explicit user consent**);
  - user-entered make, model, year and engine.
- One-tap "Send log" (with consent): upload to our backend, or share an encrypted zip.
- An **exporter** to SavvyCAN CSV/GVRET and a **replay adapter** (a fake ElmDriver that replays a log), for regression tests in CI. This replaces the need for Ircama's NC-licensed emulator.
- An "experiment" UI for testers: run a specific profile probe, sweep a range, or try an alternate dialect. Results attach to the log.

### 5.8 Testing infrastructure

- Replay-based unit tests per brand.
- A bench ECU simulator on an ESP32/STM32 with an MCP2515/TWAI using driftregion/iso14229 (MIT), with any ELM327 plugged in.
- An adapter zoo: genuine ELM327, OBDLink MX+/CX, vLinker FS/MC+, Vgate iCar Pro (new firmware), Kiwi 3, plus 2–3 popular clones. Pin a reliability score per adapter in `AdapterQuirks.json`.

---

## 6. Prioritized implementation plan (by market impact in Russia)

Market assumptions (verify against Autostat reports):
- **Parc** (cars in use) is dominated by Lada, then Kia/Hyundai, Toyota, Renault (incl. Lada Largus kin), VW/Skoda, Nissan, Chevrolet/Daewoo, Mitsubishi, Ford, Mazda, Mercedes and BMW.
- **New sales** 2023–2026 are dominated by Lada plus Chinese brands: Haval, Chery family, Geely/Belgee, Changan, Omoda/Jaecoo, Tenet, Jetour, Exeed.

| Phase | Scope | Why | Main work |
|---|---|---|---|
| **P0 (foundation, 4–6 wk)** | Link layers (BT SPP, BLE, Wi-Fi), ElmDriver, CommandQueue, **AdapterCapabilityProbe**, ISO-TP 11/29 via adapter, OBD-II services, J2012 decoder, **Recorder + replay**, profile JSON engine, VIN → profile | Everything else depends on this. Recording starts collecting field data immediately. | Port ideas from python-OBD (connect flow) and udsoncan (MIT). Clone-quirk table. |
| **P1 Lada (6–8 wk)** | K-line KWP2000 fast init (Январь 5.1/7.2, M7.9.7, M73/74/75, ME17.9.7), Lada ABS/SRS/EPS/BCM/cluster on K-line and on CAN (Vesta, XRAY, Granta FL, Niva Travel/Legend), Largus (Renault) | Biggest parc and biggest new-sales brand. Competitors (OpenDiag, MotorData) prove ELM327 feasibility. | Field-log campaign with testers. Lada DTC texts. Dialect table for KWP local IDs. |
| **P2 Hyundai/Kia + Chinese brands (6–8 wk)** | UDS 11-bit profiles (seed from opendbc and evDash, MIT), ABS/ESP/SRS/TCM/BCM/cluster/HVAC. **Generic sweep v1** (functional + 0x700–0x7FF + 29-bit 18DA). Haval/Chery/Geely/Changan/Omoda/Jetour profiles built from logs. | Hyundai/Kia is #2 in the parc. Chinese brands are about half of new sales, with no open data, so the sweep is essential. | Sweep with backtrack verify (CC/gallia ideas). Profile-candidate upload pipeline. |
| **P3 Toyota/Lexus + Renault/Nissan (4–6 wk)** | **Extended addressing** (0x750 + sub), KWP-on-CAN dialect (`1A 88`, `21`), Toyota seeds from opendbc. Renault/Dacia/Nissan profiles from logs. Nissan older KWP-on-CAN. | Large parc. Extended addressing is reused by BMW. | ATCEA/FC handling. Adapter warnings (Vgate firmware). |
| **P4 VAG (6–8 wk)** | MQB/MLB UDS (+0x6A offset, gateway 0x710), seeded from opendbc. **TP2.0 + KWP2000** for PQ (2004–2015), beta, adapter-gated. KW1281 via KKL/advanced adapters only. | Large parc (Polo sedan, Rapid, Octavia, Tiguan). PQ cars need TP2.0. | TP2.0 from the jazdw spec. pq-flasher (MIT) as reference. VAG 5-digit DTC decoder. |
| **P5 Ford + GM/Chevrolet/Daewoo (4 wk)** | Ford HS-CAN modules. MS-CAN via STN/OBDLink only. GM: GMLAN DTC service, SW-CAN via STN. Daewoo/Chevrolet (Lacetti/Aveo/Cobalt/Nexia: older K-line KWP, GM-Daewoo addressing) | Mid-sized parc, often older cars. | StnDriver. Profile flags "requires MS-CAN adapter". |
| **P6 BMW + Mercedes (6+ wk)** | BMW: 0x6F1 extended addressing, ECU sweep 0x00–0xFF via CEA, UDS/KWP-BMW DTCs (hex codes). Mercedes: KWP-on-CAN/UDS profiles from logs. | Smaller parc share but high willingness to pay. | Reuses the extended-addressing transport. DTC texts per ECU type. |
| **P7 Advanced** | DoIP/ENET (BMW F/G, newer VAG), CAN-FD (needs non-ELM hardware), service functions (resets, bleeding), coding | Differentiation. | Separate hardware strategy. |

Continuous work from P0 onward:
- the recorder log pipeline;
- a provenance-tracked profile database;
- the adapter reliability catalogue;
- DTC explanation content, prioritised by the most frequent codes in our logs.

---

## 7. Source index

- AndrOBD: https://github.com/fr3ts0n/AndrOBD (GPL-3.0)
- python-OBD: https://github.com/brendan-w/python-OBD (GPL-2.0), https://raw.githubusercontent.com/brendan-w/python-OBD/master/obd/elm327.py
- EdiabasLib / Deep OBD: https://github.com/uholeschak/ediabaslib (GPL-3.0); docs: `docs/Deep_OBD_for_BMW_and_VAG.md`, `docs/Replacement_firmware_for_ELM327.md`
- Caring Caribou: https://github.com/CaringCaribou/caringcaribou (GPL-3.0), https://github.com/CaringCaribou/caringcaribou/blob/master/documentation/uds.md
- gallia: https://github.com/Fraunhofer-AISEC/gallia (Apache-2.0), https://gallia.readthedocs.io/en/latest/uds/scan_modes.html
- python-udsoncan: https://github.com/pylessard/python-udsoncan (MIT); python-can-isotp: https://github.com/pylessard/python-can-isotp (MIT)
- mdabrowski1990/uds: https://github.com/mdabrowski1990/uds (MIT), https://uds.readthedocs.io/en/latest/pages/user_guide/can.html
- driftregion/iso14229: https://github.com/driftregion/iso14229 (MIT)
- opendbc / openpilot / panda: https://github.com/commaai/opendbc (MIT); files `opendbc/car/fw_query_definitions.py`, `toyota/values.py`, `hyundai/values.py`, `ford/values.py`, `volkswagen/values.py`
- SavvyCAN: https://github.com/collin80/SavvyCAN (MIT)
- freediag: https://github.com/fenugrec/freediag (GPL-3.0)
- ELMduino: https://github.com/PowerBroker2/ELMduino (MIT)
- obd-java-api: https://github.com/pires/obd-java-api (Apache-2.0)
- Ircama ELM327-emulator: https://github.com/Ircama/ELM327-emulator (CC-BY-NC-SA-4.0)
- kw1281test: https://github.com/gmenounos/kw1281test (MIT)
- VW TP2.0 spec: https://jazdw.net/tp20 ; vag-blocks: https://github.com/jazdw/vag-blocks (GPL-3.0)
- OVMS3: https://github.com/openvehicles/Open-Vehicle-Monitoring-System-3 (MIT majority), poller docs https://docs.openvehicles.com/en/latest/components/poller/docs/Intro.html , TP2.0 thread https://lists.openvehicles.com/archives/list/ovmsdev@lists.openvehicles.com/thread/FDVRIIZNUR765CESHVMECH73E4DF4EOC/
- pq-flasher: https://github.com/I-CAN-hack/pq-flasher (MIT)
- PyVCDS: https://github.com/MartijnD92/PyVCDS (no clear license)
- NefMoto: https://github.com/NefMoto/NefMotoOpenSource (GPL-3.0)
- ddt4all: https://github.com/cedricp/ddt4all (GPL-3.0); issue https://github.com/cedricp/ddt4all/discussions/1120
- pyren mirror: https://github.com/emtee40/pyren-OBD2
- arduino-psa-diag: https://github.com/ludwig-v/arduino-psa-diag (GPL-3.0)
- OpenVehicleDiag / ecu_diagnostics: https://github.com/rnd-ash/OpenVehicleDiag , https://github.com/rnd-ash/ecu_diagnostics (GPL-3.0)
- evDash: https://github.com/nickn17/evDash (MIT); JejuSoul HKMC EV PIDs: https://github.com/JejuSoul/OBD-PIDs-for-HKMC-EVs (no license)
- WiCAN firmware: https://github.com/meatpiHQ/wican-fw (GPL-3.0)
- OBDb: https://github.com/OBDb , https://obdb.community (CC-BY-SA-4.0)
- awesome-canbus / awesome-automotive-can-id: https://github.com/iDoka/awesome-canbus , https://github.com/iDoka/awesome-automotive-can-id (CC0)
- mytrile DTC DB: https://github.com/mytrile/obd-trouble-codes (MIT)
- Quantex ELM327 developer notes (BMW extended addressing, filters, K-line init, DoIP): https://www.quantexlab.com/en/develop/elm327.html , https://quantexlab.com/en/develop/elm327_doip.html
- ELM327 AT command list: https://cdn.sparkfun.com/assets/4/e/5/0/2/ELM327_AT_Commands.pdf
- Linux can327 (clone limitations): https://docs.kernel.org/networking/device_drivers/can/can327.html
- STN chips: https://www.scantool.net/stn2120/ , https://obdsol.com/solutions/chips/stn2120 , https://www.scantool.net/stn1170/
- FORScan MS-CAN: https://forum.forscan.org/viewtopic.php?p=4 ; clone issues https://forum.forscan.org/viewtopic.php?p=5601
- Car Scanner: https://www.carscanner.info/faq/ , https://www.carscanner.info/choosing-obdii-adapter/ , https://www.carscanner.info/compatibility/ , https://www.carscanner.info/coding/ , https://www.carscanner.info/hyundai-kia-service-functions/ , release notes e.g. https://www.apkmirror.com/apk/car-scanner-llc/car-scanner-elm-obd2/car-scanner-elm-obd2-1-121-0-release
- OBD Auto Doctor: https://obdautodoctor.com/features , https://www.obdautodoctor.com/blog/thousands-of-new-obd2-trouble-codes-added/
- Torque header usage: https://www.t6forum.com/threads/vw-t6-custom-pid-codes-for-dpf.33964/post-469108 , Toyota 0x750/0x40: https://priuschat.com/goto/post?id=2630693
- BMW DME 0x12 discussion: https://racechrono.com/forum/d/2681-2681
- OpenDiag: https://toolfox.ru/services/s/opendiag ; drive2 Январь 7.2 + ELM327: https://www.drive2.ru/l/8421611/
- MotorData (Lada Vesta up to 25 ECUs): https://apps.apple.com/ru/app/motordata-obd-elm-car-scanner/id1503589009
- KWP2000 fast-init example: https://github.com/salvatoreBosco/KWP2000_arduino
- CCC workshop on diagnostic protocols (German): https://asm16.muc.ccc.de/_media/asm:16:workshops:kfz_diagnoseprotokolle.pdf

## 8. Open questions / next research steps

1. Get the **OBDLink FRPM** PDF to pin down STN commands for MS-CAN/SW-CAN, `STPX` raw transmit and STN-native ISO-TP options.
2. Check **vLinker and OBDLink TP2.0/KW1281 support** claims in their public docs. That decides whether VAG PQ is feasible without custom firmware.
3. Run a Lada field-log campaign (Vesta, XRAY, Granta FL, Niva Travel, Largus) to fill in the ECU table. Collect permission-based logs from testers.
4. Check WMI/VDS decoding sources with permissive licenses for VIN → platform mapping. NHTSA vPIC is public domain but US-centric. Chinese and Russian VINs need our own table.
5. Get legal review of the CC-BY-SA data strategy (OBDb) and the Apache NOTICE obligations (gallia).
