# Linux SoC overlay

This directory is a root-repository overlay for the Linux-oriented Chiplab
platform. It is intentionally separate from `platform/soc-vga`: the latter is
the currently verified VGA-only platform, while this overlay restores the
historical Linux peripherals (DMFE Ethernet, USB Full-Speed host, PS/2, SPI,
NAND, and NT35510 LCD) around the legacy `loongson` Chiplab top level.

The overlay is not a board-pass claim. It has not yet been implemented as a
single design with the current CPU revision. The build flow must use the locked
Chiplab base commit from `cpu/reference/manifest.lock`, a committed generated
CPU RTL file, and Vivado 2023.2. Historical bitstreams and Linux logs are
reference evidence only.

## Source identity

The base platform is Chiplab commit
`c398d274812f164d387146fa7d8f612a4a1296d9`. The Linux top-level and the
APB changes were preserved from the local Chiplab history at commit
`5bfb5cfc5300cac43a37485a1f2ec4149cb30d1e` and its USB worktree descendant
`d7a45ff25e2ac5b8063bf14e1be1945e304d5da9`. The latter is recorded as
provenance, not as a moving Git dependency; the required files are present in
this root repository so a clean checkout can reproduce the source set.

The USB host is based on UltraEmbedded `core_usb_host` commit
`81eb9f131dbb434a9047ae074fea5c31ef46ce5d`. The register-block and clock-domain
integration follows JIT-THU Chiplab-SoC commit
`f33d195d9e7330ae6e19c13f91888b537800358c` and the AXI clock-converter pattern
documented by NonTrivial-MIPS commit
`8e83643c22a3ba7612a9bb9cec93292dad618ab5`.

The NT35510 adapter is derived from the TrivialMIPS implementation documented
in the Linux submodule. The PS/2 adapter and the USB bridge/startup sequencer
are project integration code. Exact file hashes and attribution labels are in
`source-manifest.json`.

The device-tree contract used by the source checks is the committed snapshot
`overlay/dts/loongson32_ls.dts`, copied from kernel commit
`104de262720d594b9651faf05c0fbabdb59fba68`. The build does not require a
checkout or network access to the private kernel repository.

## Hardware contract

The legacy Linux top level uses these externally visible resources:

| Resource | Address or IRQ | Package pins / clock |
| --- | --- | --- |
| UART | `0x1fe001e0`, IRQ 3 | `RX=F23`, `TX=H19` |
| DMFE | `0x1ff00000`, IRQ 2 | MII pins in `overlay/fpga/loongson/soc_up.xdc` |
| PS/2 | `0x1fe04000`, IRQ 7 | `CLK=Y2`, `DATA=AD1` |
| NT35510 LCD | `0x1fe08000` | data/control pins in `soc_up.xdc` |
| USB host | `0x1fe0c000`, IRQ 8 | UTMI pins, 60 MHz PHY clock |

The CPU interrupt vector maps the USB source to `int_out[6]` and the PS/2
source to `int_out[5]`; the Linux device tree uses hardware interrupt numbers
8 and 7 respectively. The exact mapping is checked by
`check_contracts.py`.

## Known limitations before implementation

* The legacy top level does not contain the current VGA AXI TFT master. VGA and
  this Linux overlay are separate profiles until a fourth DDR AXI master is
  integrated and timed.
* The USB top currently retains the diagnostic static UTMI reset/suspend mode
  from the historical integration. Dynamic USB3500 startup must be verified on
  the target board before it is used as the production setting.
* The legacy interconnect uses 4-bit AXI3 burst lengths while the current
  generated `core_top` declares 8-bit lengths. `soc_top.v` now uses an explicit
  adapter at this boundary. The current CPU emits only values that fit in four
  bits, and `check_contracts.py` rejects removal of the adapter.
* Historical implementation runs used physical post-route exploration. Those
  artifacts are not formal evidence for this profile. A new implementation must
  use the repository's normal Vivado flow without `AggressiveExplore` directives.
* Touch-controller signals are not part of this overlay. The panel controller
  identity and the Goodix-like I2C transactions remain unconfirmed.

## Checks

Run the source checks from the repository root. The locked Chiplab base XCI is
still `50/33 MHz`; the build Tcl changes it to `100/100 MHz`, so the pre-build
check records that one expected mismatch as a warning:

```sh
python3 platform/soc-linux/check_contracts.py --allow-clock-mismatch
```

The command verifies the source manifest hashes, top-level ports, XDC pins and
clocks, APB addresses, interrupt bit positions, and CPU AXI width boundary.

After generating CPU RTL and its matching generation manifest from a clean
commit, run the complete implementation on a Vivado 2023.2 host:

```sh
scripts/vivado/build_soc_linux.sh \
  --chiplab /path/to/clean/chiplab \
  --rtl build/rtl/mycpu_top.v \
  --generation-manifest build/rtl/generation-manifest.json \
  --vivado /opt/Xilinx/Vivado/2023.2/bin/vivado \
  --out build/vivado/soc-linux \
  --cpu-mhz 100 --uncore-mhz 100
```

The build expands the locked Chiplab commit into a temporary directory, applies
only the committed overlay, and runs one standard implementation through
`write_bitstream`. Post-route physical optimization is disabled. A successful
`manifest.json` requires nonnegative setup and hold slack, zero routed-net
errors, no DRC Error or Critical Warning rules, Vivado build `4029153`, and
`LCD_csel` routed to package pin `H18`. This does not replace later board tests.
