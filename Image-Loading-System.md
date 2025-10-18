# Forge Image Loading System Documentation

## Overview

This document explains how Forge selects and loads card images during gameplay. The system uses a sophisticated multi-tier fallback mechanism to ensure cards always display properly, even when specific image files are missing.

## Quick Summary

When a card needs to be displayed:
1. Generate an **image key** from the card's properties (name, set, art variant)
2. Convert the key to a **file path** (e.g., `res/images/cards/M21/Lightning Bolt2.full.jpg`)
3. Search for the file using **multiple fallback strategies** if the primary path doesn't exist
4. Cache the result (whether found or missing) to optimize future lookups
5. Display the image or render a **placeholder** if all fallbacks fail

---

## Image Selection Flow

### 1. Image Key Generation

**Location:** `forge-core/src/main/java/forge/item/PaperCard.java:425-474`

Each `PaperCard` instance generates and caches image keys for different card faces:

```java
public String getCardImageKey() {
    if (this.cardImageKey == null)
        this.cardImageKey = ImageUtil.getImageKey(this, "", true);
    return cardImageKey;
}

public String getCardAltImageKey() {
    if (this.cardAltImageKey == null){
        if (this.hasBackFace())
            this.cardAltImageKey = ImageUtil.getImageKey(this, "back", true);
        else
            this.cardAltImageKey = ImageUtil.getImageKey(this, "", true);
    }
    return cardAltImageKey;
}
```

**Supported Face Types:**
- **Front face** (default)
- **Back face** (transform, double-faced, meld cards)
- **Specialized faces** (white/blue/black/red/green for Specialize mechanic)

**Image Key Format:** The key doesn't include directory structure - that's added later during path resolution.

---

### 2. Image Path Construction

**Location:** `forge-core/src/main/java/forge/util/ImageUtil.java:98-196`

The `getImageRelativePath()` method builds the actual file path from card metadata:

```java
public static String getImageRelativePath(PaperCard cp, String face,
                                         boolean includeSet, boolean isDownloadUrl)
```

**Path Construction Steps:**

1. **Get card name** based on face:
   - Regular cards: Use card name
   - Back face: Use other part's name (transform/DFC cards)
   - Specialized faces: Use the specialized variant's name
   - Split cards: Concatenate both halves (e.g., "Fire Ice")
   - Functional variants: Append variant name

2. **Convert to MWS filename format:**
   - Strip accents from characters
   - Remove special characters: `"`, `/`, `:`, `?`
   - Convert to Magic Workstation naming convention

3. **Add art index** (if multiple arts exist):
   - Art indices are 1-based (1, 2, 3, etc.)
   - If requested art index exceeds available arts: `artIdx = artIdx % cntPictures`
   - Wraps around if you try to use art variant 5 when only 3 exist

4. **Add suffix:**
   - Most cards: `.full`
   - Planes and Phenomena: No suffix

5. **Prepend set folder** (if includeSet):
   - Format: `SetCode/CardName[ArtIndex].full`
   - Example: `M21/Lightning Bolt2.full`

**Example Paths:**
- Single art: `M21/Lightning Bolt.full`
- Multiple arts: `M21/Lightning Bolt2.full`
- Back face: `ISD/Delver of Secrets$alt.full` → resolves to `ISD/Insectile Aberration.full`
- Split card: `INV/Fire Ice.full`
- Plane: `PC2/Stairs to Infinity` (no .full suffix)

---

### 3. File Discovery and Fallback System

**Location:** `forge-core/src/main/java/forge/ImageKeys.java:99-323`

This is the **most critical** part of the image loading system. When `getImageFile(String key)` is called, it performs an extensive search:

#### Cache Checks (Lines 143-150)

```java
if (toFind.contains(filename))
    return null;  // Currently being looked up by async thread
if (missingCards.contains(filename))
    return null;  // Already confirmed missing

File cachedFile = cachedCards.get(filename);
if (cachedFile != null)
    return cachedFile;  // Previously found
```

**Three-tier cache system:**
1. **toFind Set** - Prevents duplicate lookups for files currently being searched
2. **missingCards Set** - Remembers confirmed missing files (prevents repeated filesystem access)
3. **cachedCards HashMap** - Stores filename → File mappings (50,000 initial capacity)

#### Primary File Lookup (Line 152)

```java
File file = findFile(dir, filename);
```

The `findFile()` method tries multiple extensions in priority order:
1. `.jpg` (most common for cards)
2. `.png` (fallback format)
3. Empty string (for keys with extension already included)

#### Fallback Strategy Chain

If the primary lookup fails, the system tries these fallbacks **in order**:

**Fallback 1: AE/Ae Casing Variants** (Lines 182-196)
```java
if (filename.contains("Ae")) {
    file = findFile(dir, TextUtil.fastReplace(filename, "Ae", "AE"));
}
```
- Handles older cards with inconsistent casing (e.g., "AEther" vs "Aether")
- Important for case-sensitive filesystems (Linux, macOS)

**Fallback 2: .fullborder Variant** (Lines 197-210)
```java
if (filename.contains(".full")) {
    String fullborderFile = TextUtil.fastReplace(filename, ".full", ".fullborder");
    file = findFile(dir, fullborderFile);
}
```
- Some image packs use `.fullborder` instead of `.full`
- Also tries without art index: `CardName1.fullborder` → `CardName.fullborder`

**Fallback 3: Remove Art Index** (Lines 211-216)
```java
file = findFile(dir, filename.replaceAll("[0-9].full",".full"));
```
- If `Lightning Bolt2.full` is missing, try `Lightning Bolt.full`
- Useful when only one art exists but game requests variant 2+

**Fallback 4: Set Lookup (Async)** (Lines 217-232)
```java
if (hasSetLookup(filename)) {
    ThreadUtil.getServicePool().submit(() -> {
        File f = setLookUpFile(filename, fullborderFile);
        if (f != null)
            cachedCards.put(filename, f);
        else
            missingCards.add(filename);
        toFind.remove(filename);
    });
}
```
- For special cards that need custom lookup logic
- Searches alternative directories configured in `setLookup` mappings
- Runs asynchronously to avoid blocking gameplay

**Fallback 5: Set Aliases** (Lines 233-247)
```java
if (!setCode.isEmpty() && editionAlias.containsKey(setCode)) {
    for (String alias : editionAlias.get(setCode)) {
        file = findFile(dir, TextUtil.fastReplace(filename, setCode + "/", alias + "/"));
    }
}
```
- Some sets have multiple codes (e.g., promotional variants)
- Tries each alias: `PMEI/Card.full`, `MEI/Card.full`, etc.

**Fallback 6: Add .full/.fullborder for Planes/Phenomena** (Lines 249-261)
```java
if (!filename.contains(".full")) {
    file = findFile(dir, TextUtil.addSuffix(filename,".full"));
    file = findFile(dir, TextUtil.addSuffix(filename,".fullborder"));
}
```
- Planes and Phenomena normally have no suffix
- But some image packs include them anyway

**Fallback 7: Set-less Lookup** (Lines 262-315)
```java
if (filename.contains("/")) {
    String setlessFilename = filename.substring(filename.indexOf('/') + 1);
    file = findFile(dir, setlessFilename);
}
```
- If `M21/Lightning Bolt.full.jpg` not found, try just `Lightning Bolt.full.jpg`
- Searches root card directory for generic versions
- Also applies all previous fallback strategies to the set-less filename

#### Token-Specific Fallbacks (Lines 157-180)

Tokens have their own fallback hierarchy:

```java
if (dir.equals(CACHE_TOKEN_PICS_DIR)) {
    // Try: SetCode/CollectorNumber_TokenName
    file = findFile(dir, setCode + "/" + collectorNumber + "_" + setlessFilename);

    // Try: SetCode/TokenName
    file = findFile(dir, setCode + "/" + setlessFilename);

    // Try: TokenName (no set folder)
    file = findFile(dir, setlessFilename);
}
```

**Token Key Format:** `t:TokenName|SetCode|CollectorNumber`
- Example: `t:Goblin|M21|5` → tries `M21/5_Goblin`, then `M21/Goblin`, then `Goblin`

---

### 4. Special Card Types and Face-Down Images

**Location:** `forge-game/src/main/java/forge/game/card/Card.java:6561-6588`

When cards are face-down, they use special placeholder images instead of their regular artwork:

```java
public final String getFacedownImageKey() {
    if (isInZone(ZoneType.Exile)) {
        if (isForetold())
            return StaticData.instance().getOtherImageKey(ImageKeys.FORETELL_IMAGE, null);
        return ImageKeys.getTokenKey(ImageKeys.HIDDEN_CARD);
    }

    if (isManifested())
        return StaticData.instance().getOtherImageKey(ImageKeys.MANIFEST_IMAGE, set);
    if (isCloaked())
        return StaticData.instance().getOtherImageKey(ImageKeys.CLOAKED_IMAGE, set);
    if (getCastSA() != null) {
        if (getCastSA().isKeyword(Keyword.DISGUISE))
            return StaticData.instance().getOtherImageKey(ImageKeys.CLOAKED_IMAGE, set);
        else if (getCastSA().isKeyword(Keyword.MORPH) || getCastSA().isKeyword(Keyword.MEGAMORPH))
            return StaticData.instance().getOtherImageKey(ImageKeys.MORPH_IMAGE, set);
    }

    return ImageKeys.getTokenKey(ImageKeys.HIDDEN_CARD);
}
```

**Face-Down Image Types:**

| Mechanic | Image Key | Description |
|----------|-----------|-------------|
| Morph/Megamorph | `MORPH_IMAGE` | Gray creature card back with eye symbol |
| Manifest | `MANIFEST_IMAGE` | Similar to morph but distinct |
| Cloak/Disguise | `CLOAKED_IMAGE` | Ward symbol variant |
| Foretold (in exile) | `FORETELL_IMAGE` | Special foretell card back |
| Default | `HIDDEN_CARD` | Generic card back |

**Note:** These images can be set-specific (using the card's set code) or generic.

---

### 5. Image Format and Extension Priority

**Location:** `forge-core/src/main/java/forge/ImageKeys.java:78`

```java
private static final String[] FILE_EXTENSIONS = { ".jpg", ".png", "" };
```

**Priority Order:**
1. **JPG** - Primary format, smaller file size, good for photographs (card scans)
2. **PNG** - Fallback format, lossless, better for graphics with transparency
3. **Empty** - For keys that already include an extension

When searching for `M21/Lightning Bolt.full`, the system checks:
1. `M21/Lightning Bolt.full.jpg`
2. `M21/Lightning Bolt.full.png`
3. `M21/Lightning Bolt.full` (exact match)

---

### 6. Art Selection and User Preferences

#### Art Index Selection

**Location:** `forge-core/src/main/java/forge/item/PaperCard.java:95-98`

Each `PaperCard` instance stores an `artIndex` property:

```java
private int artIndex = 1;  // Default to first art variant

public int getArtIndex() { return artIndex; }
public void setArtIndex(int artIndex) { this.artIndex = artIndex; }
```

**How Art Variants Work:**
- Art index is **1-based** (not 0-based)
- User-selectable in deck editor and collection views
- Stored in deck files and inventory data
- Multiple versions of same card can coexist with different art indices

**File Naming:**
- 1 art: `CardName.full.jpg`
- 2+ arts: `CardName1.full.jpg`, `CardName2.full.jpg`, `CardName3.full.jpg`, etc.

**Overflow Handling** (`ImageUtil.java:122-126`):
```java
int artIdx = cp.getArtIndex() - 1;  // Convert to 0-based
if (hasManyPictures) {
    if (cntPictures <= artIdx)
        artIdx = artIdx % cntPictures;  // Wrap around
    s.append(artIdx + 1);  // Convert back to 1-based
}
```

If you select art variant 5 but only 3 exist: `5 % 3 = 2` → uses art 3.

#### Image Format Preferences

**Desktop Preferences:**
**Location:** `forge-gui-desktop/src/main/java/forge/ImageCache.java:259-320`

**Art Crop vs Full Card:**
```java
boolean useArtCrop = "Crop".equals(FModel.getPreferences()
    .getPref(ForgePreferences.FPref.UI_CARD_ART_FORMAT));

if (useArtCrop) {
    imageKey = TextUtil.fastReplace(imageKey, ".full", ".artcrop");
}
```

**User Configurable Options:**
- **Art crop mode** - Shows just the artwork (requires artist info)
- **Scale larger images** - Scales up small images to fill space
- **Black border rendering** - Corner rounding for older sets

**Set-Specific Border Handling:**
- White-bordered sets (Revised, 4E-9E, Unlimited) skip white border removal
- Alpha and Masters Edition sets have custom corner radius calculations
- Modern sets use standard border rendering

#### Edition/Set Preference - Universes Beyond Handling

**Location:** `forge-core/src/main/java/forge/card/CardDb.java:51-63, 935-950`

When a card exists in multiple printings, Forge automatically **deprioritizes Universes Beyond sets** in favor of traditional Magic sets.

**Universes Beyond Sets Deprioritized:**
```java
private static final Set<String> UNIVERSES_BEYOND_SETS = new HashSet<>(Arrays.asList(
    "WHO",  // Doctor Who Commander
    "BOT",  // Transformers
    "40K",  // Warhammer 40,000 Commander
    "LTR",  // The Lord of the Rings: Tales of Middle-earth
    "LTC",  // The Lord of the Rings: Tales of Middle-earth Commander
    "ACR",  // Assassin's Creed
    "TLA",  // Avatar: The Last Airbender
    "TAE",  // Avatar: The Last Airbender - Eternal
    "FAL",  // Fallout Commander
    "SLX"   // Secret Lair: Universes Within (alternate art versions)
));
```

**How It Works:**

When selecting a default printing for a card (lines 935-950):
1. **Separate editions** into two groups: non-UB and UB
2. **Non-UB editions are preferred** - sorted first in the list
3. **Within each group**, editions are sorted by date (respecting the `latestFirst` preference)
4. **Fallback behavior**: If a card only exists in UB sets, those printings are used

**Example:**
- Card "Lightning Bolt" exists in: M21 (2020), LTR (2023), and M10 (2009)
- With `LATEST_ART` preference: Selects **M21** (not LTR, despite being newer)
- With `ORIGINAL_ART` preference: Selects **M10** (oldest non-UB printing)

This ensures that when you add cards to decks without specifying a set, you'll get traditional Magic artwork by default, while still allowing UB printings when explicitly chosen or when no alternatives exist.

---

### 7. Caching Strategy

Forge uses multiple levels of caching to optimize performance:

#### File Lookup Cache

**Location:** `forge-core/src/main/java/forge/ImageKeys.java:50-56`

```java
// Stores filename → File mappings
private static final Map<String, File> cachedCards =
    new HashMap<>(50000, 0.99f);

// Tracks confirmed missing files
private static final Set<String> missingCards =
    Collections.synchronizedSet(new HashSet<>());

// Tracks files currently being looked up
private static final Set<String> toFind =
    Collections.synchronizedSet(new HashSet<>());
```

**Purpose:**
- Eliminates repeated filesystem access
- Stores both positive (file found) and negative (file missing) results
- Thread-safe for concurrent access

#### Desktop Image Cache

**Location:** `forge-gui-desktop/src/main/java/forge/ImageCache.java:87-158`

Uses **Guava LoadingCache** for loaded and scaled images:

```java
private static final LoadingCache<String, BufferedImage> scaledCache;
```

**Cache Configuration:**
- **Key Format:** `imageKey#widthxheight` (e.g., `M21/Lightning Bolt.full#488x680`)
- **Size Limit:** Configurable via `UI_IMAGE_CACHE_MAXIMUM` preference
- **Expiration:** 15 minutes idle time (automatically evicts unused images)
- **Loader:** Reads file from disk and decodes using `ImageIO.read()`

**Why Cache Scaled Images?**
- Same card may be displayed at different sizes (hand, battlefield, zoomed view)
- Each size is cached separately to avoid repeated scaling operations
- Scaling is CPU-intensive, especially for high-res images

#### Mobile Image Cache

**Location:** `forge-gui-mobile/src/forge/assets/ImageCache.java:160-168`

Uses custom **EvictingQueue** implementation:

```java
private static final int CACHE_CAPACITY = 300;  // Configurable
private final Set<String> cardsLoaded = new HashSet<>();
private final EvictingQueue<String> syncQ;
```

**Strategy:**
- **LRU (Least Recently Used)** eviction
- **Capacity-based** (not time-based like desktop)
- Tracks loaded cards in a HashSet for quick membership testing
- Synchronized queue for thread-safe operations

**Why Different from Desktop?**
- Mobile devices have less RAM
- LibGDX texture management differs from Java2D BufferedImage
- Simpler eviction strategy is more predictable for constrained environments

---

### 8. Default and Fallback Rendering

**Location:** `forge-gui-desktop/src/main/java/forge/ImageCache.java:87-132, 334-356`

#### Default Image

When the image cache initializes, it loads a placeholder:

```java
static {
    try {
        defImage = ImageIO.read(new File(ForgeConstants.NO_CARD_FILE));
    } catch (Exception ex) {
        _defaultImage = new BufferedImage(10, 10, BufferedImage.TYPE_INT_ARGB);
    }
}
```

**Path:** `res/images/cards/nocard.jpg`

#### Rendered Placeholder

If no image file exists after all fallbacks, Forge **renders** a card placeholder:

**File:** `FCardImageRenderer.drawCardImage()`

**Rendered Placeholder Includes:**
- Card name (top)
- Card type line
- Rules text (word-wrapped)
- Power/Toughness (if creature)
- Artist name (if available)
- Copyright/set info (bottom)
- Color-coded background based on card colors

**When This Happens:**
- Missing image files
- Failed downloads
- Custom cards without artwork
- Tokens without specific images

---

### 9. Image Key Prefixes and Types

**Location:** `forge-core/src/main/java/forge/ImageKeys.java:13-40`

Forge uses prefixes to distinguish different image types:

| Prefix | Type | Example Key | Directory |
|--------|------|-------------|-----------|
| `c:` | Card | `c:Lightning Bolt\|M21\|1` | `res/images/cards/` |
| `t:` | Token | `t:Goblin\|M21\|5` | `res/images/tokens/` |
| `i:` | Icon | `i:mana_blue` | `res/images/icons/` |
| `b:` | Booster | `b:booster_m21` | `res/images/boosters/` |
| `f:` | Fat Pack | `f:fatpack_m21` | `res/images/fatpacks/` |
| `x:` | Booster Box | `x:boosterbox_m21` | `res/images/boosterboxes/` |
| `p:` | Precon Deck | `p:precon_m21` | `res/images/precons/` |
| `o:` | Tournament Pack | `o:tournament_m21` | `res/images/tournamentpacks/` |
| `a:` | Adventure | `a:dragon_boss` | `res/images/adventure/` |

**Special Suffixes:**
- `$alt` - Back face or alternate version
- `$wspec` - White specialized version
- `$uspec` - Blue specialized version
- `$bspec` - Black specialized version
- `$rspec` - Red specialized version
- `$gspec` - Green specialized version

**Examples:**
- Front face: `c:Delver of Secrets|ISD|1`
- Back face: `c:Delver of Secrets|ISD|1$alt` (resolves to Insectile Aberration)
- Token: `t:Goblin|M21|5`
- Icon: `i:loyalty_ability`

---

### 10. Complete Image Loading Flow Diagram

```
┌─────────────────────────────────┐
│   Card Display Request          │
│   (CardView in gameplay)        │
└────────────┬────────────────────┘
             │
             ▼
┌─────────────────────────────────┐
│ Determine Card State            │
│ - Is face-down? → Special image │
│ - Is cloned? → Use source card  │
│ - Which face to show?           │
└────────────┬────────────────────┘
             │
             ▼
┌─────────────────────────────────┐
│ Generate Image Key              │
│ PaperCard.getCardImageKey()     │
│ Format: SetCode/Name[Art].full  │
└────────────┬────────────────────┘
             │
             ▼
┌─────────────────────────────────┐
│ Apply User Preferences          │
│ - Art crop vs full card         │
│ - Border rendering options      │
└────────────┬────────────────────┘
             │
             ▼
┌─────────────────────────────────┐
│ Check Image Cache               │
│ - Already loaded and scaled?    │
└────────────┬────────────────────┘
             │ Cache miss
             ▼
┌─────────────────────────────────┐
│ ImageKeys.getImageFile()        │
│ File path resolution            │
└────────────┬────────────────────┘
             │
             ▼
┌─────────────────────────────────┐
│ Check Missing Cards Cache       │
│ - Known to be missing? → null   │
└────────────┬────────────────────┘
             │ Not in cache
             ▼
┌─────────────────────────────────┐
│ Check File Lookup Cache         │
│ - Previously found? → Return    │
└────────────┬────────────────────┘
             │ Not cached
             ▼
┌─────────────────────────────────┐
│ Filesystem Search               │
│ Try in priority order:          │
│ 1. Direct path (.jpg/.png)      │
│ 2. AE/Ae casing variants        │
│ 3. .fullborder variant          │
│ 4. Without art index            │
│ 5. Set aliases                  │
│ 6. Set-less (root directory)    │
│ 7. Special setLookup (async)    │
└────────────┬────────────────────┘
             │
             ├─── File Found ─────────┐
             │                        │
             │                        ▼
             │            ┌──────────────────────┐
             │            │ Cache File Path      │
             │            │ cachedCards.put()    │
             │            └──────────┬───────────┘
             │                       │
             │                       ▼
             │            ┌──────────────────────┐
             │            │ Load Image from File │
             │            │ ImageIO.read() or    │
             │            │ LibGDX TextureLoader │
             │            └──────────┬───────────┘
             │                       │
             │                       ▼
             │            ┌──────────────────────┐
             │            │ Scale to Target Size │
             │            │ (if needed)          │
             │            └──────────┬───────────┘
             │                       │
             │                       ▼
             │            ┌──────────────────────┐
             │            │ Cache Scaled Image   │
             │            │ scaledCache.put()    │
             │            └──────────┬───────────┘
             │                       │
             ├───────────────────────┘
             │
             ▼
┌─────────────────────────────────┐
│ Display Image                   │
│ or Render Placeholder           │
└─────────────────────────────────┘
```

---

## Key Discoveries and Design Patterns

### 1. Defensive Fallback Strategy
The system has **7+ levels of fallback** before giving up, ensuring cards almost always display properly even with incomplete image collections.

### 2. Three-Tier Caching
Minimizes expensive operations:
- **File lookup cache** - Prevents repeated filesystem access
- **Missing cards cache** - Avoids re-checking known missing files
- **Loaded image cache** - Keeps decoded/scaled images in memory

### 3. Lazy Loading with Caching
Image keys are generated once per card and cached, preventing repeated string manipulation.

### 4. Asynchronous Lookup
Complex lookup operations (setLookup) run in background thread pool to avoid blocking gameplay.

### 5. Smart Art Index Wrapping
Modulo arithmetic ensures the game never crashes from requesting non-existent art variants.

### 6. Rendered Fallback
When all else fails, Forge renders a readable text-based placeholder instead of showing nothing.

### 7. Set-Agnostic Fallback
If set-specific images are missing, the system falls back to generic versions from the root directory. This allows maintaining a smaller image collection while still supporting gameplay.

---

## Common Questions

### Q: Why does my card show the wrong art variant?
**A:** The art index in your deck file or collection may be set to a variant that doesn't exist. The system wraps using modulo, so variant 5 might wrap to variant 2 if only 2 arts exist.

### Q: Why do some cards load slowly?
**A:** First-time loads require filesystem access and image decoding. Subsequent loads use the cache. Very large images take longer to scale.

### Q: Can I mix JPG and PNG files?
**A:** Yes! The system tries both extensions. JPG is checked first for efficiency.

### Q: Why doesn't my custom card show its image?
**A:** Ensure the filename matches the expected format: `SetCode/CardName.full.jpg` (no art index for single-art cards). Check for special characters in the card name that might be stripped.

### Q: What's the difference between .full and .fullborder?
**A:** Both show the entire card. `.fullborder` typically includes the physical card border, while `.full` may be cropped to just the card face. The system treats them as interchangeable fallbacks.

### Q: How do I force Forge to refresh images?
**A:** Delete or clear the cache. The file lookup cache is in-memory only and resets when Forge restarts. The scaled image cache can be cleared via preferences.

### Q: Why does Forge prefer non-Universes Beyond artwork?
**A:** When a card exists in both traditional Magic sets and Universes Beyond sets (like Doctor Who, Transformers, Lord of the Rings), Forge automatically selects the traditional Magic printing by default. This preserves the classic Magic aesthetic while still allowing UB printings when explicitly chosen or when they're the only option. Cards that only exist in UB sets will still use those printings normally.

---

## Related Files

### Core Logic
- `forge-core/src/main/java/forge/ImageKeys.java` - File discovery and fallback system
- `forge-core/src/main/java/forge/util/ImageUtil.java` - Image path construction
- `forge-core/src/main/java/forge/item/PaperCard.java` - Image key generation
- `forge-core/src/main/java/forge/card/CardDb.java` - Edition selection and Universes Beyond filtering

### Desktop Implementation
- `forge-gui-desktop/src/main/java/forge/ImageCache.java` - Desktop image caching
- `forge-gui-desktop/src/main/java/forge/view/FCardImageRenderer.java` - Placeholder rendering

### Mobile Implementation
- `forge-gui-mobile/src/forge/assets/ImageCache.java` - Mobile/Adventure image caching
- `forge-gui-mobile/src/forge/Graphics.java` - LibGDX texture management

### Game Logic
- `forge-game/src/main/java/forge/game/card/Card.java` - Face-down image logic
- `forge-game/src/main/java/forge/game/card/CardView.java` - View state for UI

---

## Performance Tips

### For Users
1. **Use JPG format** for card images (smaller files, faster to load)
2. **Enable image cache** and allocate sufficient memory
3. **Use set-specific images** when possible (reduces fallback overhead)
4. **Avoid excessive cache clearing** (rebuilding the cache is expensive)

### For Developers
1. **Preload commonly used images** during startup
2. **Use the cached image keys** instead of regenerating them
3. **Batch image operations** to minimize context switching
4. **Monitor the missingCards cache** to identify systematic issues
5. **Consider image resolution** - higher res = slower loading and more memory

---

*Last Updated: 2025-10-18*
*Forge Version: Current (debug branch)*
