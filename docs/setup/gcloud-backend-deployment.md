# GCE backend deployment

The backend runs beside the existing application on `rami-api-backend` as a separate Docker Compose service. GitHub Actions builds the image, pushes it to Artifact Registry, then uses `gcloud compute scp/ssh` through IAP to tell the VM to pull and start that image. The VM's existing runtime service account pulls images. The container listens on port `9010`, restarts with Docker after a VM reboot, and keeps document settings and temporary audio under `/var/lib/meeting-automation-backend`.

## GitHub Actions runtime configuration

Add these three repository Actions secrets:

```dotenv
OPENAI_API_KEY=<OpenAI API key>
DOCUMENT_SETTINGS_ENCRYPTION_KEY=<base64-encoded-32-byte key>
ALLOWED_ORIGINS=https://meeting-automation-one.vercel.app
```

Generate a new settings key with `openssl rand -base64 32` if starting with empty document settings. If importing an existing encrypted settings file, use its existing key. Changing that key makes existing encrypted settings unreadable. Add optional model, email, Slack, and provider variables to the same secret when configured. `RENDER`, `PORT`, `DOCUMENT_SETTINGS_DIR`, and `TEMP_AUDIO_DIR` are set by Compose.

The workflow validates that all three secrets are set without printing their values, writes them to a mode-`0600` temporary file on the GitHub runner, and copies it over IAP. The VM deployment script installs them as a root-owned `/opt/apps/meeting-automation-backend/shared/.env` with mode `0600`, then deletes the uploaded temporary file. No manual VM environment-file setup is needed. The existing `rami-api-backend` `.env` is left untouched.

The deploy script uses `sudo` to create `/var/lib/meeting-automation-backend` and run Docker. GitHub Actions accesses the VM as `leedh84` through IAP and requires passwordless `sudo` for that account. This was checked on the VM. Docker Compose 2.40 and Docker 29 are installed.

## GCP and GitHub authentication

The following GCP resources are provisioned:

- Artifact Registry repository: `us-central1/meeting-automation`
- Workload Identity provider: `projects/627667150507/locations/global/workloadIdentityPools/github-actions/providers/meeting-automation`, restricted to `donghyunlee-dev/meeting-automation`
- Deploy service account: `meeting-automation-deploy@project-c43c730b-f69a-42f1-9ce.iam.gserviceaccount.com`, with repository writer and IAP tunnel access
- VM service account: the existing `rami-api-runtime`, which already has Artifact Registry reader access
- Firewall rule: `meeting-automation-backend-9010`, TCP 9010 from `0.0.0.0/0`, targeted only to VM tag `rami-api-backend`

The workflow uses GitHub OIDC federation for Google Cloud; no service account key is stored in GitHub. It also uses repository secrets `VM_SSH_PRIVATE_KEY` and `VM_SSH_KNOWN_HOSTS` for SSH through IAP. It runs for relevant backend changes on `master` and supports manual execution. It runs backend tests, pushes an immutable commit-SHA image, copies the Compose/deploy files over IAP, then pulls and starts only `meeting-automation-backend`. It checks `/actuator/health` and restores the prior image if the new container does not become healthy. Existing containers and the VM's current application are not modified.

Before the first deployment, set the three Actions secrets above; the workflow checks them before pushing an image. The VM generates a short-lived Artifact Registry access token from its attached service account for each pull and logs out afterward.

## Network access and frontend cutover


The firewall rule now allows TCP 9010 from any IPv4 source on this VM tag. Traffic on this port is plain HTTP. The production frontend currently defaults to the Render URL in `frontend/src/apiBase.ts`. Before switching it, provide an HTTPS API URL: browser calls from the HTTPS Vercel site to plain HTTP on port `9010` are blocked as mixed content. Configure a domain and TLS reverse proxy or another HTTPS entry point that forwards to this service, set Vercel's `VITE_API_BASE_URL` to that HTTPS URL, and set `ALLOWED_ORIGINS` to the exact frontend origin. Keep Render as the frontend target until this end-to-end path is verified.

## Capacity and service move

The current VM is `e2-micro` with about 953 MiB total memory, an existing container using about 127 MiB, and 14 GiB free disk at inspection. The new container is capped at 640 MiB and the JVM heap at 55% of that to leave room for the existing app and host. Measure memory during startup and a representative transcription/minutes job before routing production traffic. If the VM runs out of memory or processing time is unacceptable, resize it to `e2-small` or larger and retest; resizing is not automated here.

The backend keeps sessions in process memory, and audio chunks in the configured temporary audio directory. Deployments and VM restarts interrupt active sessions and do not guarantee audio recovery. Render's filesystem-backed document settings are not migrated automatically: export or re-enter provider settings through the app after the new service is available, and retain the same encryption key for any settings file that is deliberately migrated. Keep Render available until the new API and frontend integration have been verified.
