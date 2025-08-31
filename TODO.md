# Adventure Mode Image Loading Issues - Investigation Notes

## Issues Identified
1. **Missing card images on first shop entry** - Images don't load initially in adventure mode shops
2. **Different art appears on re-entry** - When re-entering shops, images load but show different artwork than expected
3. **Suspected "fan art" filtering not working** - User believes fan art is showing despite being disabled, but investigation shows no fan art settings exist

## Investigation Findings

### Image Loading System Architecture
- **Primary Handler**: `ImageFetcher.java` (`forge-gui/src/main/java/forge/util/ImageFetcher.java`)
- **Adventure Shop UI**: `ShopScene.java` and `AdventureDeckEditor.java` in `forge-gui-mobile/src/forge/adventure/scene/`
- **Image Sources**: 
  - CardForge server: `https://downloads.cardforge.org/images/cards/`
  - Scryfall API: `https://api.scryfall.com/cards/`

### Key Findings

#### No Fan Art Settings
- **Forge does not have fan art filtering capabilities**
- All image sources are official (CardForge + Scryfall)
- What appears to be "fan art" is likely official alternate artwork

#### Relevant Preferences (`ForgePreferences.java` lines 86-105, 173)
```java
UI_PREFERRED_ART("LATEST_ART_ALL_EDITIONS")          // Controls art preference
UI_SMART_CARD_ART("false")                           // Smart art selection
UI_RANDOM_ART_IN_POOLS("true")                       // Randomizes art variants ⚠️
UI_ENABLE_ONLINE_IMAGE_FETCHER("true")               // Enables image downloading
UI_CARD_ART_FORMAT("Full")                           // Full vs cropped images
```

#### Potential Root Causes

##### Issue 1: Missing Images on First Load
- **Async Loading**: `ImageFetcher.fetchImage()` downloads images asynchronously via thread pool
- **Caching Problem**: Images may not be cached properly on first shop entry
- **EDT Thread Issues**: Image fetching requires EDT thread (line 89: `FThreads.assertExecutedByEdt(true)`)

##### Issue 2: Different Art on Re-entry  
- **Random Art Setting**: `UI_RANDOM_ART_IN_POOLS = true` randomizes artwork variants
- **Image Key Generation**: Different image keys might be generated between shop entries
- **Multi-Edition Lookup**: `getScryfallDownloadURL()` method tries multiple editions for cards

## Technical Details

### Image Loading Flow
1. `ShopScene.enter()` → `AdventureDeckEditor.refresh()` 
2. Card rendering requests images via `ImageFetcher.fetchImage()`
3. If image not cached, downloads asynchronously from CardForge/Scryfall
4. Notifies UI when download completes

### Problematic Code Locations
- **ImageFetcher.java:225-235** - Multi-edition card lookup that could cause art variations
- **ImageFetcher.java:88-95** - Preference checks that disable fetching
- **AdventureDeckEditor.java:105** - Random art in pools setting affects shop display

## Proposed Solutions

### High Priority
- [ ] **Investigate image caching behavior** in adventure mode shops
- [ ] **Check EDT thread execution** during shop image loading
- [ ] **Examine `UI_RANDOM_ART_IN_POOLS` impact** on shop displays

### Medium Priority  
- [ ] **Review image key generation consistency** between shop entries
- [ ] **Analyze async loading timing** for initial shop display
- [ ] **Test image preloading** for adventure mode

### Low Priority
- [ ] **Document artwork source behavior** for user education
- [ ] **Consider shop-specific image caching** improvements

## Files to Monitor
- `forge-gui/src/main/java/forge/util/ImageFetcher.java`
- `forge-gui-mobile/src/forge/adventure/scene/ShopScene.java`
- `forge-gui-mobile/src/forge/adventure/scene/AdventureDeckEditor.java`
- `forge-gui/src/main/java/forge/localinstance/properties/ForgePreferences.java`

## Test Cases Needed
- [ ] Fresh adventure mode start → enter shop → verify image loading
- [ ] Exit and re-enter shop → verify art consistency  
- [ ] Test with `UI_RANDOM_ART_IN_POOLS` set to `false`
- [ ] Test with image cache cleared

---
*Investigation Date: 2025-08-31*  
*Status: Root cause analysis complete, solution implementation pending*