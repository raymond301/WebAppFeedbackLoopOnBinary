# Spatial SNR Validation Portal

## Overview
Cloud-native stateless validation portal for QuPath manifest packets in GCS.

## Architecture
- **Backend**: FastAPI + gcsfs/fsspec, dynamic crawl of `gs://<bucket>/Exported_Packets`.
- **Frontend**: React + Recharts scatter overlay and adaptive SNR slider.
- **Persistence**: Writes `decision_<user>_<timestamp>.json` into each packet folder.

## Run locally
```bash
docker compose up --build
```

## Key endpoints
- `GET /api/tree`
- `GET /api/packet/{image}/{marker}/{packet}/manifest`
- `GET /api/packet/{image}/{marker}/{packet}/render?channels=0,1`
- `POST /api/packet/{image}/{marker}/{packet}/decision`

## Cloud Run deploy
Build and deploy backend and frontend separately to Cloud Run services.
