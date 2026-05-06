# QuPath Prepackage Scripts

This folder contains QuPath Groovy scripts that bridge high-throughput spatial image analysis to downstream validation workflows (cloud pipelines and web app review).

## Purpose

The prepackage workflow is designed to:

1. Identify high-value regions by density of positive cells.
2. Let a human reviewer quickly keep or skip candidate hotspots.
3. Export marker-filtered OME-TIFF crops using only currently active channels.
4. Serialize per-packet metadata for downstream ingestion.

The result is a compact, curated dataset from very large fluorescence images.

## Files In This Folder

- `define_snr_division.groovy`
- `export_snr_manifest.groovy`

## Primary Script: export_snr_manifest.groovy

`export_snr_manifest.groovy` runs a 4-stage pipeline:

1. Hotspot identification
2. Interactive navigation
3. Active channel filtering
4. Data serialization

### Stage 1: Hotspot Identification

- The script collects detection objects with class `(+)` logic equivalent to `PathClass.fromString("(+)")`.
- It bins positive cells into a grid based on crop size and pixel calibration.
- Grid cells are ranked by positive-cell count.
- Top `maxSpotsToVisit` bins are selected as candidate hotspots.

### Stage 2: Interactive Navigation

- A JavaFX panel opens with:
  - `KEEP & EXPORT`
  - `NEXT (Skip)`
- The viewer is centered on each candidate spot.
- Reviewer can accept or skip each spot in sequence.

### Stage 3: Active Channel Filtering

- The script reads the currently active/selected channels from QuPath display state.
- It creates a filtered server using only active channel indices.
- The exported crop includes only those channels (marker-specific export).

### Stage 4: Data Serialization

For each kept spot, the script creates a packet containing:

1. `crop.ome.tif`
2. `manifest.csv`
3. `packet_info.json`

## Output Folder Structure

Exports are created under the project parent directory:

```text
<project-parent>/
  Exported_Packets/
    <imageName>/
      <markerName>/
        Packet_<index>_<HHmmss>/
          crop.ome.tif
          manifest.csv
          packet_info.json
```

### Folder Naming Rules

- `Exported_Packets`: configurable via `outputDirName`.
- `<imageName>`: source image name with extension stripped.
- `<markerName>`:
  - first active channel name that is not DAPI
  - fallback: `Combined`
- `Packet_<index>_<HHmmss>`: running packet index plus timestamp.

## Data Contract

### 1) crop.ome.tif

- Region crop centered on the selected hotspot.
- Dimensions are `gridDim x gridDim` pixels.
- Physical crop size derives from `cropSizeMicrons` and image pixel size.
- Includes only active channels at export time.

### 2) manifest.csv

Header:

```csv
CellID,X,Y,SNR,Class,ChannelsUsed
```

Row semantics:

- `CellID`: QuPath detection ID
- `X`, `Y`: centroid coordinates in full-image pixel space
- `SNR`: value from `Calculated_SNR` measurement
- `Class`: detection path class
- `ChannelsUsed`: pipe-delimited active channel names

Only cells inside the crop ROI are included.

### 3) packet_info.json

Per-packet metadata sidecar with keys:

- `packetIndex`
- `timestamp`
- `imageName`
- `markerName`
- `cropSizeMicrons`
- `pixelSizeMicrons`
- `cropSizePixels`
- `spotCenterX`
- `spotCenterY`
- `spotCellCount`
- `activeChannels` (array)

Example:

```json
{
  "packetIndex": 1,
  "timestamp": "093212",
  "imageName": "SLIDE-3024_FullPanel",
  "markerName": "CD8",
  "cropSizeMicrons": 200.0,
  "pixelSizeMicrons": 0.5,
  "cropSizePixels": 400,
  "spotCenterX": 13073.0,
  "spotCenterY": 5383.0,
  "spotCellCount": 87,
  "activeChannels": ["CD8", "PanCK"]
}
```

## Configuration Knobs

In `export_snr_manifest.groovy`:

- `cropSizeMicrons` (currently `200.0` in your latest script)
- `maxSpotsToVisit` (default `20`)
- `outputDirName` (default `Exported_Packets`)

## Execution Prerequisites

1. Open image in QuPath project context.
2. Ensure detections are available.
3. Ensure positive class `(+)` detections exist from upstream SNR thresholding workflow.
4. Toggle desired channels ON in the viewer before export.

If no channels are active or no positive cells are found, the script exits with an error dialog.

## QuPath 0.7 Compatibility Notes

The script has been adapted to avoid API mismatches seen in QuPath 0.7.0:

- Uses `viewer.getImageDisplay()` instead of `getDisplay()`.
- Avoids `getRenderParameters()` assumptions by probing channel APIs dynamically.
- Uses `viewer.centerROI(...)` fallback for viewer navigation compatibility.
- Uses `GeneralTools.stripExtension(...)` (replaces deprecated `getNameWithoutExtension`).
- Uses `LocalDateTime` + `DateTimeFormatter` instead of `Date.format(...)`.
- Reads measurements with compatibility logic:
  - try `measurementList.get(name)`
  - fallback `getMeasurementValue(name)`

## Known Non-Blocking Warnings

- SSL/PKIX update-check warnings in QuPath logs are environment/network trust-store issues and do not affect this export script logic.
- Deprecation warnings for `qupath.lib.gui.dialogs.Dialogs` can be addressed later by migrating to `qupath.fx.dialogs.Dialogs`.

## Troubleshooting Quick Guide

1. Error: no channels active
- Turn on one or more channels in the QuPath viewer.

2. Error: no `(+)` cells found
- Run upstream SNR thresholding/classification script first.

3. Export packet created but SNR is `0.0`
- Verify `Calculated_SNR` measurement exists on detections.
- Confirm measurement naming matches exactly.

4. No hotspots identified
- Confirm detections and class assignments are present.
- Increase `cropSizeMicrons` or inspect classing quality.

## Suggested Downstream Usage

- Treat each packet folder as one atomic review/training sample.
- Parse `packet_info.json` first to index and route packets.
- Join packet-level metadata with `manifest.csv` rows for analytics.
- Use `crop.ome.tif` and manifest centroids for web-based visual validation.
