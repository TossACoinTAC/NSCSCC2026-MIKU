# USB Full-Speed host integration

The controller RTL in this directory is the NSCSCC/JIT-THU modification of
UltraEmbedded's USB Full-Speed host. The upstream RTL reference is commit
`81eb9f131dbb434a9047ae074fea5c31ef46ce5d` from
`ultraembedded/core_usb_host`. The JIT-THU version adds the `USB_CTRL2`
register at offset `0x24`, a software-controlled PHY reset, and fixes the AXI
write ordering expected by its register block.

`apb_usbh_bridge.v` is specific to this Chiplab integration. It transfers one
APB request at a time through the project's Xilinx `axi_clock_converter_0`,
from the 100 MHz uncore clock to the 60 MHz UTMI clock. The architecture follows
the independently hardware-tested integrations in JIT-THU Chiplab-SoC commit
`f33d195d9e7330ae6e19c13f91888b537800358c` and NonTrivial-MIPS commit
`8e83643c22a3ba7612a9bb9cec93292dad618ab5`, both of which use a Xilinx AXI
clock converter before the JIT-THU USB controller. Writes send the AXI address
before data because the modified V0.5 register block does not accept write data
before the address. If the PHY clock is absent before a transaction starts, the
bridge returns a zero-valued APB response without issuing an AXI transaction.

`usb3500_phy_startup.v` explicitly cycles `SUSPENDN` after FPGA programming,
detects `CLKOUT` from the 100 MHz uncore domain, and applies the USB3500 reset
requirements. Reset assertion is asynchronous; reset deassertion and the
post-reset ready indication are synchronous to `CLKOUT`. If the PHY clock does
not appear within the datasheet limit, the sequencer records the failure and
retries without requiring a full experiment-box power cycle.

The controller is mapped at physical address `0x1fe0c000`. Its SoC interrupt
input is `6`, corresponding to LoongArch CPU interrupt number `8` in the Linux
device tree.
