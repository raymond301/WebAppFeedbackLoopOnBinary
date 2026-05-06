from __future__ import annotations

import io
import json
import os
import time
from collections import defaultdict
from typing import Any

import fsspec
import numpy as np
import pandas as pd
import tifffile
from cachetools import TTLCache
from fastapi import FastAPI, HTTPException, Query
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import Response
from pydantic import BaseModel

BUCKET = os.getenv("GCS_BUCKET", "")
ROOT = os.getenv("GCS_ROOT", "Exported_Packets")
ALLOWED_ORIGINS = os.getenv("ALLOWED_ORIGINS", "*").split(",")

app = FastAPI(title="Spatial SNR Validation Portal API")
app.add_middleware(
    CORSMiddleware,
    allow_origins=ALLOWED_ORIGINS,
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

fs = fsspec.filesystem("gcs")
manifest_cache: TTLCache[str, pd.DataFrame] = TTLCache(maxsize=512, ttl=600)


class DecisionRequest(BaseModel):
    user_email: str
    status: str
    threshold: float | None = None


def packet_uri(image: str, marker: str, packet: str) -> str:
    return f"{BUCKET}/{ROOT}/{image}/{marker}/{packet}"


def get_manifest(packet_path: str) -> pd.DataFrame:
    key = f"{packet_path}/manifest.csv"
    if key in manifest_cache:
        return manifest_cache[key]
    with fs.open(key, "rb") as f:
        df = pd.read_csv(f)
    manifest_cache[key] = df
    return df


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok"}


@app.get("/api/tree")
def get_tree() -> dict[str, Any]:
    if not BUCKET:
        raise HTTPException(status_code=500, detail="GCS_BUCKET not configured")
    root = f"{BUCKET}/{ROOT}"
    tree: dict[str, Any] = defaultdict(lambda: defaultdict(list))
    for image_path in fs.ls(root):
        image = image_path.rstrip("/").split("/")[-1]
        for marker_path in fs.ls(image_path):
            marker = marker_path.rstrip("/").split("/")[-1]
            packets = []
            for packet_path in fs.ls(marker_path):
                packet = packet_path.rstrip("/").split("/")[-1]
                try:
                    df = get_manifest(packet_path)
                    plus_count = int((df["Class"].astype(str) == "+").sum()) if "Class" in df else 0
                except Exception:
                    plus_count = 0
                packets.append({"packet": packet, "plus_count": plus_count})
            packets.sort(key=lambda x: x["plus_count"], reverse=True)
            tree[image][marker] = packets
    return {"images": tree}


@app.get("/api/packet/{image}/{marker}/{packet}/manifest")
def packet_manifest(image: str, marker: str, packet: str) -> dict[str, Any]:
    p = packet_uri(image, marker, packet)
    df = get_manifest(p)
    channels = []
    if "ActiveChannels" in df and not df.empty:
        channels = [c.strip() for c in str(df["ActiveChannels"].iloc[0]).split("|") if c.strip()]
    snr_min = float(df["SNR"].min()) if "SNR" in df else 0.0
    snr_max = float(df["SNR"].max()) if "SNR" in df else 1.0
    points = df[["CellID", "X", "Y", "SNR", "Class"]].to_dict(orient="records")
    return {"channels": channels, "snr_min": snr_min, "snr_max": snr_max, "points": points}


@app.get("/api/packet/{image}/{marker}/{packet}/render")
def render_packet(
    image: str,
    marker: str,
    packet: str,
    channels: str = Query("0,1", description="Comma separated channel indexes"),
) -> Response:
    p = packet_uri(image, marker, packet)
    tif_path = f"{p}/crop.ome.tif"
    idx = [int(x) for x in channels.split(",") if x != ""]
    with fs.open(tif_path, "rb") as f:
        data = tifffile.imread(io.BytesIO(f.read()))
    arr = data
    if arr.ndim == 4:
        arr = arr[arr.shape[0] // 2]
    if arr.ndim == 3:
        # assume C,Y,X
        selected = [arr[min(i, arr.shape[0] - 1)] for i in idx[:3]]
    elif arr.ndim == 2:
        selected = [arr, arr, arr]
    else:
        raise HTTPException(status_code=400, detail="Unsupported TIFF dimensions")
    while len(selected) < 3:
        selected.append(selected[-1])
    rgb = np.stack(selected[:3], axis=-1).astype(np.float32)
    rgb = rgb - rgb.min()
    if rgb.max() > 0:
        rgb = rgb / rgb.max()
    rgb8 = (rgb * 255).clip(0, 255).astype(np.uint8)
    png = tifffile.imwrite(io.BytesIO(), rgb8, photometric="rgb")
    # tifffile.imwrite to BytesIO returns None; reopen strategy
    import PIL.Image
    out = io.BytesIO()
    PIL.Image.fromarray(rgb8).save(out, format="PNG")
    return Response(content=out.getvalue(), media_type="image/png")


@app.post("/api/packet/{image}/{marker}/{packet}/decision")
def save_decision(image: str, marker: str, packet: str, req: DecisionRequest) -> dict[str, str]:
    p = packet_uri(image, marker, packet)
    ts = int(time.time())
    safe_user = req.user_email.replace("@", "_").replace(".", "_")
    filename = f"decision_{safe_user}_{ts}.json"
    payload = {
        "user_email": req.user_email,
        "threshold": req.threshold,
        "status": req.status,
        "unix_timestamp": ts,
        "image": image,
        "marker": marker,
        "packet": packet,
    }
    with fs.open(f"{p}/{filename}", "wb") as f:
        f.write(json.dumps(payload, indent=2).encode("utf-8"))
    return {"saved": filename}
