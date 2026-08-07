# CAD

Six print-ready assemblies for the Lighthouse cane. All parts are original to
this project except the omni wheel, whose provenance is noted below.

![Full assembly](Assembly.png)

## Parts

| Part | STL | Render | Purpose |
|---|---|---|---|
| **Omni wheel** | `Omni Wheel.stl` | `Omni Wheel.png` | Six-roller print-in-place omni wheel. Rollers captive in the hub — no bearings, no axles, no assembly. |
| **Main frame** | `Main.stl` | — | Lower assembly: motor, wheel, and ToF sensor at the cane tip. |
| **Hub mount + cover** | `Hub Mount - Hub Cover.stl` | `Hub Mount.png` | Clamps the drive assembly to the shaft. |
| **Electronics housing + cover** | `Mount & Electronics - Electronics Cover.stl` | `Electronics Mount.png` | Skeletonized enclosure for the UNO Q and Modulino stack. |
| **Distance mount** | `Mount & Electronics - Distance Mount.stl` | — | Aims the ToF sensor forward and down. |
| **Phone mount** | `S25 Ultra - Phone Mount.stl` | `Phone Mount.png` | S25 Ultra cradle on a pivoting hinge; rear cameras face forward. |
| **Battery mount** | `Anker 737 Power Bank - Battery Mount.stl` | `Battery Mount.png` | Anker 737 cradle, ports accessible. |

## Print settings

| | Recommendation |
|---|---|
| Material | **PETG** for structural parts (main frame, hub mount, phone mount) — a cane gets dropped and flexed. PLA is fine for covers. |
| Layer height | 0.2 mm |
| Perimeters | 4 on structural parts |
| Infill | 25–30% structural, 15% covers |
| Supports | None needed. **Do not use supports on the omni wheel** — they will fuse the rollers. |
| Filament | ~400–550 g for the full set (~$12–18) |

> **Not yet printed.** These models are complete and print-ready but have **not
> been printed and fit-checked**. Expect a clearance iteration, particularly on
> the print-in-place wheel where roller clearance is printer-dependent. If you
> print them, please open an issue with what you had to adjust — that feedback is
> more valuable to this project than most code contributions.

## The omni wheel

![Omni wheel](Omni%20Wheel.png)

Based on the open-source print-in-place omni wheel published on GrabCAD:

**<https://grabcad.com/library/omni-wheel-print-in-place-1>**

Adapted here for the JGA25 motor's 4 mm D-shaft and this cane's tip geometry.
Check the license terms on that page before redistributing the derived geometry —
we are citing the source, not asserting its terms.

Print it flat, no supports, 30–40 mm/s. Rollers come off the bed fused; work each
of the six free by hand. If they won't release, scale roller clearance up 0.05 mm
and reprint.

Why print-in-place matters beyond convenience: it removes a commercial omni wheel
($15–25), a machined hub adapter, roller bearings, and an assembly step. The
reference research design used a machined wheel-to-hub adapter. This is a
meaningful part of how the build lands under $200.

## Design notes

**Skeletonized housings.** Weight and airflow. Every gram sits at the end of a
lever the user holds for the length of a walk, and the UNO Q runs warm under
sustained inference load.

**Pivoting phone hinge.** Camera pitch is the one mounting parameter the software
cannot fully self-correct. The grid self-calibrates height and reads pitch and
roll live from gravity, but a camera aimed at sky or pavement gives it nothing to
work with. The hinge exists so pitch can be set once and locked.

**Balance.** Mount the battery opposite the electronics housing. An unbalanced
cane is fatiguing in a way a ten-minute bench test won't reveal.

**It is still a white cane.** The shaft is a standard white cane or 1.25 in PVC,
and contact sensing works unchanged if every electronic system fails. That
fallback is deliberate.

## Source files

Only STLs are committed. If you have the editable models (Fusion / STEP), adding
them under `source/` would let others modify rather than remesh — particularly
for adapting the phone mount to other devices, which is the most likely change
anyone will want to make.

## Reference

The mechanical concept — a motorized omni wheel at the cane tip applying grounded
kinesthetic steering — follows:

> P. Slade, A. Tambe, M. J. Kochenderfer, "Multimodal sensing and intuitive
> steering assistance improve navigation and mobility for people with impaired
> vision," *Science Robotics* **6**(59), eabg6594 (2021).
> [doi:10.1126/scirobotics.abg6594](https://doi.org/10.1126/scirobotics.abg6594)
> · [reference design](https://github.com/pslade2/AugmentedCane)

Their published CAD is a useful cross-reference, though this build's geometry
differs throughout: different motor, printed rather than machined wheel
interface, and housings sized for the UNO Q and a phone rather than for a LiDAR
unit and onboard compute.
