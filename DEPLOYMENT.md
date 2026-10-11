# Deploy PostgreSQL, the backend, and the frontend separately

Use **Neon → Render → Netlify**, in that order. The same code also works with another PostgreSQL provider, a Java 21 Docker host, and a static frontend host.

```text
Browser → Netlify frontend
          VITE_API_BASE_URL → Render API
                             SPRING_DATASOURCE_* → Neon PostgreSQL
                             SLUICE_CORS_ALLOWED_ORIGINS ← Netlify origin
```

The frontend holds only the public API URL. Database credentials exist only in the backend host's environment. Environment templates are examples; neither Spring nor a hosted platform automatically loads `sluice-api/.env.example`.

## Files used by each deployment

```text
sluice/
├── pom.xml                       Maven reactor, required for the API build
├── sluice-core/                  Queue library, compiled into the API
│   └── src/main/resources/
│       └── db/migration/         Flyway creates/updates database tables
├── sluice-api/
│   ├── Dockerfile                Independent backend image; Java 21
│   ├── .env.example              Backend environment reference
│   └── src/main/resources/
│       └── application.yml       Environment-driven runtime settings
├── frontend/
│   ├── package.json              Independent npm project
│   └── .env.example              Public frontend environment reference
├── render.yaml                   Optional Render backend Blueprint
├── netlify.toml                  Frontend build settings
└── mock-upstream/                Optional standalone simulator
```

Keep one repository. Render needs the repository root as its Docker build context because the API depends on `sluice-core` and the parent POM. The API image copies only its Java build inputs. Netlify builds only `frontend/`. Deployments remain independent; separate repositories are not necessary.

## 1. Create PostgreSQL on Neon

1. Sign in to [Neon](https://console.neon.tech/) and create a free project.
2. Choose a region close to the Render region you will use.
3. Open the project's connection details. Use a **direct connection** (disable connection pooling in the connection dialog), and record the host, database, role/username, and password.
4. Prepare the following backend values. Replace every placeholder:

   ```text
   SPRING_DATASOURCE_URL=jdbc:postgresql://YOUR_DIRECT_HOST:5432/neondb?sslmode=require
   SPRING_DATASOURCE_USERNAME=YOUR_ROLE
   SPRING_DATASOURCE_PASSWORD=YOUR_PASSWORD
   ```

   If your database has another name, replace `neondb`. Spring uses a **JDBC URL**, not the `postgresql://user:password@...` URL shown for some clients. Enter the username and password separately using Neon's actual role/password values.

5. Leave table creation to the API: Flyway runs the six existing migrations at startup. Do not manually create the jobs tables or move the database into the frontend.

Use a database role that can create and update tables in this project's schema. Save credentials in Render's environment settings; do not commit a real `.env` file.

## 2. Deploy the backend on Render

1. Commit the deployment changes and push your repository to GitHub.
2. In [Render](https://dashboard.render.com/), select **New → Web Service** and connect that repository.
3. Configure these settings:

   | Setting | Value |
   |---|---|
   | Runtime/language | Docker |
   | Root directory | Leave blank (repository root) |
   | Dockerfile path | `sluice-api/Dockerfile` |
   | Docker build context | `.` (repository root) |
   | Instance type | Free |
   | Health check path | `/actuator/health` |

   The Dockerfile supplies the build and startup commands. Do not set the root directory to `sluice-api`: that would hide the parent POM and `sluice-core` from the build.

4. Add environment variables:

   | Variable | Value |
   |---|---|
   | `SPRING_DATASOURCE_URL` | Your JDBC URL from step 1 |
   | `SPRING_DATASOURCE_USERNAME` | Your database role |
   | `SPRING_DATASOURCE_PASSWORD` | Your database password |
   | `SLUICE_WORKER_COUNT` | `2` |
   | `DB_POOL_SIZE` | `5` |
   | `JAVA_TOOL_OPTIONS` | `-XX:MaxRAMPercentage=65.0` |
   | `SLUICE_LAB_ENABLED` | `true` |
   | `SLUICE_SIMULATION_ENABLED` | `true` |

   Render supplies `PORT` automatically. The server binds to `0.0.0.0` at that port. Other hosts can set `PORT`; the local default is `8080`.

   Set `SLUICE_CORS_ALLOWED_ORIGINS` to your Netlify origin if already known. Otherwise, leave it unset initially and add it in step 4 below. Backend health checks and requests without a browser Origin do not depend on this setting.

   **Leave `SLUICE_MOCK_UPSTREAM_URL` unset.** The worker and Test lab use the built-in simulation endpoint through loopback HTTP at the configured port. This still exercises real HTTP success, latency, and 429/Retry-After behavior.

5. Deploy. Check the logs for successful Flyway migrations and application startup. Copy the assigned API URL, such as `https://sluice-api-xxxx.onrender.com`.
6. Open `https://YOUR_BACKEND.onrender.com/actuator/health`. Expected response:

   ```json
   { "status": "UP" }
   ```

   Open `/jobs/stats` at the same origin to check the queue connection. A new database has zero counts.

Alternatively, create a Render **Blueprint** from the repository's `render.yaml`. It prompts for database credentials and the allowed frontend origin; enter your final Netlify origin, or a placeholder origin that you replace after frontend creation. Use either the Blueprint or manual Web Service flow, rather than creating duplicate backend services.

Render's Docker and health-check behavior is documented in [Docker on Render](https://render.com/docs/docker) and [Health Checks](https://render.com/docs/health-checks).

## 3. Deploy the frontend on Netlify

1. In [Netlify](https://app.netlify.com/), add/import a project from the same GitHub repository.
2. The root `netlify.toml` supplies these settings:

   | Setting | Value |
   |---|---|
   | Base directory | `frontend` |
   | Build command | `npm run build` |
   | Publish directory | `dist` (relative to the base directory) |
   | Node version | `22.14.0` |

3. Add this **build environment** variable before deploying:

   ```text
   VITE_API_BASE_URL=https://YOUR_BACKEND.onrender.com
   ```

   Use the backend origin with no `/api` suffix. Backend endpoints are `/jobs`, `/jobs/batch`, and `/tests/runs`. `/api` is the local Vite development proxy only; this deployment uses direct browser requests and CORS.

4. Deploy and copy the final frontend origin, such as `https://sluice-console-xxxx.netlify.app`.

Vite embeds this public URL in the JavaScript build. Changing it requires a new frontend build/deploy; changing only the hosting environment does not update an existing bundle. Never place database credentials in a `VITE_*` variable. See [Vite on Netlify](https://docs.netlify.com/build/frameworks/framework-setup-guides/vite/) and [Netlify environment variables](https://docs.netlify.com/configure-builds/environment-variables).

## 4. Allow the frontend and verify the full app

1. Return to Render's backend environment settings and set:

   ```text
   SLUICE_CORS_ALLOWED_ORIGINS=https://YOUR_FRONTEND.netlify.app
   ```

   Use the exact HTTPS origin, without a path. Multiple origins can be comma-separated, for example a custom domain and the original Netlify domain. Preview-deploy URLs require their own allowed origins; the app does not enable a wildcard automatically.

2. Save/redeploy the backend so it reads the new setting.
3. Open the frontend. Check that queue counts load.
4. Submit a **Success** job and verify it reaches Completed.
5. In **Tests → Batch & queue**, choose five jobs, two worker threads, three seconds latency, and a one-second claim pause. Run the test and verify the queue animation and pass/fail report.
6. Try **Heartbeat renewal** and **Lease expiry** to verify the embedded simulation and scheduled reaper together.

If you change providers later, update backend database credentials, frontend `VITE_API_BASE_URL`, and backend `SLUICE_CORS_ALLOWED_ORIGINS` as needed. Redeploy the affected component. Moving to a new database does not transfer existing jobs automatically; use your database provider's export/import process if you want to keep them.

## Local development and optional standalone mock

The normal local path now needs PostgreSQL and the API:

```bash
docker compose up --build -d postgres sluice-api
cd frontend
npm ci
npm run dev
```

The frontend's local `/api` proxy still works. You can also set `VITE_API_BASE_URL=http://localhost:8080` in `frontend/.env.local`; the default allowed origins include `http://localhost:5173` and `http://127.0.0.1:5173`.

To keep a standalone upstream, deploy `mock-upstream/Dockerfile` separately and set `SLUICE_MOCK_UPSTREAM_URL=https://YOUR_MOCK_HOST/simulate` on the API. For the original local Compose mock, start `mock-upstream` and override the API URL to `http://mock-upstream:8081/simulate`. You may disable the embedded endpoint with `SLUICE_SIMULATION_ENABLED=false` when an external upstream URL is configured.

## Free-plan behavior and troubleshooting

- Render Free web services sleep after 15 minutes without inbound traffic and can restart. While the API sleeps, its workers, heartbeat tasks, scheduler, and reaper are stopped. On wake, ordinary expired jobs can be recovered by the reaper; in-memory lab reports are lost on restart, and unfinished reserved lab jobs are not processed by ordinary workers. Use always-on backend hosting for uninterrupted queue processing. [Render free limits](https://render.com/docs/free).
- Neon has storage/compute quotas. Workers and scheduled polling query the database while the API is awake, so the database is not necessarily idle even when the queue is empty. Netlify also has free usage limits. [Neon pricing](https://neon.com/pricing), [Netlify pricing](https://www.netlify.com/pricing/).
- This is a public demo with no authentication. CORS controls browser access; it does not prevent someone from calling the API directly. Use demo data for public hosting.
- **Backend fails at startup:** check the JDBC prefix, host, database name, username/password, and SSL options in Render's environment; read the first database/Flyway error in its logs.
- **Backend health is UP, but the frontend reports a connection error:** check the built frontend API URL and the exact CORS origin; save/redeploy the appropriate service. Do not use `localhost` or `/api` as the hosted API URL unless you configure an explicit hosting proxy.
- **First request is slow or times out:** allow Render to wake, check `/actuator/health`, and retry; the dashboard automatically retries read requests. Check for a received job/current run before resubmitting a timed-out write.
- **Jobs fail with a connection error to port 8081:** remove the old `SLUICE_MOCK_UPSTREAM_URL` value to use the embedded endpoint, or set it to a reachable deployed mock service.
