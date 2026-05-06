# Spatial SNR Validation Portal

A stateless, cloud-native validation portal for QuPath manifest packets in Google Cloud Storage.

---

## 1) What this system does

The portal reads packet data directly from GCS (no database) using this structure:

```text
gs://<bucket-name>/Exported_Packets/
  └── <imageName>/
      └── <markerName>/
          └── Packet_<N>_<Timestamp>/
              ├── crop.ome.tif
              └── manifest.csv
```

It provides:
- Packet discovery and density-first queue ordering from `manifest.csv`.
- Adaptive SNR thresholding per packet.
- Decision writeback as `decision_<user>_<unix_timestamp>.json` in the same packet folder.

---

## 2) Repository layout

- `backend/` – FastAPI API service (GCS crawler, manifest reader, decision persistence).
- `frontend/` – React/Vite UI service.
- `docker-compose.yml` – local multi-container run for smoke testing.

---

## 3) Prerequisites

1. **Google Cloud project** with billing enabled.
2. Installed locally:
   - `gcloud` CLI
   - Docker Desktop for Windows (optional for local `docker compose` only)
   - `git`
3. A GCS bucket containing packet data under `Exported_Packets/...`.
4. IAM permissions to:
   - create service accounts
   - grant IAM roles
   - deploy Cloud Run
   - read/write bucket objects

---

## 4) Windows 11 + VS Code setup

These steps are tailored for **Windows 11** with development in **VS Code**.

### Step 4.1: Install tooling on Windows 11

1. Install **Git for Windows**: https://git-scm.com/download/win
2. Install **VS Code**: https://code.visualstudio.com/
3. Install **Google Cloud CLI**: https://cloud.google.com/sdk/docs/install
4. (Optional) Install **Docker Desktop** if you want local container runs.

### Step 4.2: Recommended VS Code extensions

- Python
- Pylance
- Docker
- ESLint
- Prettier

### Step 4.3: Open terminal choice

Use **PowerShell** or **Git Bash** inside VS Code (`Terminal -> New Terminal`).
Commands in this README are Bash-style; in PowerShell, set vars using `$env:VAR="value"`.

---

## 5) Local development setup (quick start)

### Step 5.1: Clone and enter repo

```bash
git clone <your-repo-url>
cd WebAppFeedbackLoopOnBinary
```

### Step 5.2: Configure local environment

Set the bucket name used by backend:

```bash
export GCS_BUCKET=<your-bucket-name>
export GCS_ROOT=Exported_Packets
```

PowerShell equivalent:

```powershell
$env:GCS_BUCKET="<your-bucket-name>"
$env:GCS_ROOT="Exported_Packets"
```

If using Application Default Credentials locally:

```bash
gcloud auth application-default login
```

### Step 5.3: Run locally with Docker Compose

```bash
docker compose up --build
```

Services:
- API: `http://localhost:8080`
- Frontend: `http://localhost:5173`

### Step 5.4: Validate local endpoints

```bash
curl http://localhost:8080/health
curl http://localhost:8080/api/tree
```

---

## 6) GCP deployment (Cloud Run + Artifact Registry + GCS)


> The steps below deploy **two Cloud Run services**:
> 1) `spatial-snr-api` (backend) and 2) `spatial-snr-web` (frontend).

### Step 5.1: Set environment variables

```bash
export PROJECT_ID=<your-project-id>
export REGION=us-central1
export REPO=spatial-snr
export API_SERVICE=spatial-snr-api
export WEB_SERVICE=spatial-snr-web
export BUCKET=<your-bucket-name>

gcloud config set project $PROJECT_ID
```

### Step 5.2: Enable required APIs

```bash
gcloud services enable \
  run.googleapis.com \
  artifactregistry.googleapis.com \
  cloudbuild.googleapis.com \
  storage.googleapis.com
```

### Step 5.3: Create Artifact Registry repo (Docker)

```bash
gcloud artifacts repositories create $REPO \
  --repository-format=docker \
  --location=$REGION \
  --description="Spatial SNR portal images"
```

If the repository already exists, this command can be skipped.

### Step 5.4: Create service account for backend runtime

```bash
export API_SA=spatial-snr-api-sa

gcloud iam service-accounts create $API_SA \
  --display-name="Spatial SNR API Runtime"
```

Grant least required access to packet bucket (object read/write):

```bash
gcloud storage buckets add-iam-policy-binding gs://$BUCKET \
  --member="serviceAccount:${API_SA}@${PROJECT_ID}.iam.gserviceaccount.com" \
  --role="roles/storage.objectAdmin"
```

### Step 5.5: Build backend image with Google Cloud Build

Use Cloud Build instead of local Docker builds:

```bash
gcloud builds submit ./backend \
  --tag $REGION-docker.pkg.dev/$PROJECT_ID/$REPO/spatial-snr-api:latest
```

### Step 5.6: Deploy backend to Cloud Run

```bash
gcloud run deploy $API_SERVICE \
  --image=$REGION-docker.pkg.dev/$PROJECT_ID/$REPO/spatial-snr-api:latest \
  --region=$REGION \
  --platform=managed \
  --allow-unauthenticated \
  --service-account=${API_SA}@${PROJECT_ID}.iam.gserviceaccount.com \
  --set-env-vars=GCS_BUCKET=$BUCKET,GCS_ROOT=Exported_Packets,ALLOWED_ORIGINS=* \
  --memory=1Gi \
  --cpu=1
```

Capture backend URL:

```bash
export API_URL=$(gcloud run services describe $API_SERVICE --region=$REGION --format='value(status.url)')
echo $API_URL
```

### Step 5.7: Build frontend image with Cloud Build (inject API URL)

Because frontend build needs `VITE_API_BASE`, pass it as a Docker build arg through Cloud Build:

```bash
gcloud builds submit ./frontend \
  --config ./frontend/cloudbuild.yaml \
  --substitutions=_IMAGE=$REGION-docker.pkg.dev/$PROJECT_ID/$REPO/spatial-snr-web:latest,_VITE_API_BASE=$API_URL
```


### Step 5.8: Deploy frontend to Cloud Run

```bash
gcloud run deploy $WEB_SERVICE \
  --image=$REGION-docker.pkg.dev/$PROJECT_ID/$REPO/spatial-snr-web:latest \
  --region=$REGION \
  --platform=managed \
  --allow-unauthenticated \
  --memory=512Mi \
  --cpu=1
```

Get frontend URL:

```bash
gcloud run services describe $WEB_SERVICE --region=$REGION --format='value(status.url)'
```

---

## 7) Post-deploy verification checklist

1. Open frontend URL.
2. Confirm tree loads images/markers/packets from GCS.
3. Select packet and verify scatter points render.
4. Move SNR slider and verify point colors change dynamically.
5. Save a decision and confirm a new `decision_<user>_<timestamp>.json` appears under packet path in GCS.

Optional check:

```bash
gcloud storage ls gs://$BUCKET/Exported_Packets/<image>/<marker>/<packet>/
```

---

## 8) Security and IAM notes

- Prefer **restricted CORS** in production (`ALLOWED_ORIGINS=https://your-frontend-domain`).
- If frontend should be private, remove `--allow-unauthenticated` and enforce IAM/IAP.
- Consider replacing `roles/storage.objectAdmin` with a narrower custom role if policy requires stricter controls.

---

## 9) Operational notes

- System is intentionally stateless; all durable state is written back to GCS.
- Downstream Nextflow can gate on presence of decision JSON files.
- Backend currently reads packet TIFF for render endpoint and caches manifests in memory.

---

## 10) Useful API endpoints

- `GET /health`
- `GET /api/tree`
- `GET /api/packet/{image}/{marker}/{packet}/manifest`
- `GET /api/packet/{image}/{marker}/{packet}/render?channels=0,1`
- `POST /api/packet/{image}/{marker}/{packet}/decision`
