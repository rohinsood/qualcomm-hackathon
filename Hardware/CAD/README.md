# CAD

**This directory is a placeholder.** No CAD files have been produced for this
build yet.

The electronics are verified working on the bench (see
[`../Assembly Instructions.md`](../Assembly%20Instructions.md), Stage 1). The
mechanical parts — motor mount, shaft clamp, handle enclosure, phone mount — are
specified in [`../Bill of Materials.md`](../Bill%20of%20Materials.md) but not yet
modeled or fabricated.

We are not shipping placeholder geometry. An STL that has never been printed or
fit-checked is worse than no STL, because someone will waste filament on it.

## If you want to build the mechanics now

The research design this project's steering is based on publishes complete CAD,
a bill of materials, and solder-at-home instructions, all open-source:

- **[pslade2/AugmentedCane](https://github.com/pslade2/AugmentedCane)**
- P. Slade, A. Tambe, M. J. Kochenderfer, "Multimodal sensing and intuitive
  steering assistance improve navigation and mobility for people with impaired
  vision," *Science Robotics* **6**(59), eabg6594 (2021).
  [doi:10.1126/scirobotics.abg6594](https://doi.org/10.1126/scirobotics.abg6594)

Their motor mount, shaft clamp, and wheel adapter are directly reusable — the
steering mechanism is the same concept. What differs is the electronics bay: this
build houses an **Arduino UNO Q plus three Modulinos** rather than an ESP32 and
discrete driver, and it needs a **phone mount** (the phone replaces their LiDAR
and onboard compute). Expect to remodel the handle and add the mount; the tip
assembly should carry over.

## What to add here when it exists

```
CAD/
├── README.md                this file
├── source/                 editable models (.f3d / .step / .scad)
├── stl/                     print-ready, one file per part
│   └── <part>.stl
└── images/                  renders + photos of the built article
```

Please include, for each part: print orientation, layer height, infill, whether
supports are needed, and material. A bare STL forces the next person to rederive
all of it.
