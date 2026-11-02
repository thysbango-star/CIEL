# Design references

Kept as the visual direction for Ciel (do not delete):

- `great-sage-theme-concepts.png` — the eight theme concepts (Azure Calm, Ethereal Light, Void Minimal, Twilight Sage, Nature's Wisdom, Divine Light, Starfall, Glassmorphism) and the Great Sage layout.
- `ciel-core-original.jpg` — the glowing core. The app uses a processed copy (`app/src/main/res/drawable-nodpi/ciel_core.webp`: 512 px, brightness converted to transparency so it blends over light and dark themes; tinted per theme at runtime).

The interface recreates these as native Compose/Canvas elements; the images themselves are not used as screens.
