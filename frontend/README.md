# Sluice queue console

React and Vite, ordinary CSS, and the Sluice REST API. The dashboard and interactive Test lab use real PostgreSQL jobs and recorded worker events.

The interface uses a dark theme throughout. **Queue** shows clickable status cards, a compact jobs table, and a preset-based submission form. Custom JSON and idempotency are under **Advanced options**. **Tests** opens a separate view with scenario inputs and live animations; event logs and verification checks expand on demand. Both views preserve form input while navigating and polling. The inspector keeps worker and lease metadata in an expandable section.

When the backend is waking up, the dashboard shows shimmer placeholders and one **Connecting to Sluice** notice. Readiness checks use `GET /jobs/stats`, with a five-second pause after each failed request; ordinary dashboard and lab polling wait until the backend responds. Inputs remain editable, while **Enqueue** and **Run** stay disabled. The interface loads immediately on success. After three minutes, retries stop and **Try again** starts a fresh connection attempt. Reconnection preserves loaded data and form values. Job and test submissions are never replayed automatically; check the queue or current run before repeating a timed-out submission.

Current frontend version: `0.1.0`. The companion Java backend is `0.1.0-SNAPSHOT`. See the root [README](../README.md) for the queue architecture, HTTP API, and project roadmap.

## Run locally

Requirements: Node.js 20.19+, 22.13+, or 24+ on the supported major-version lines, npm, and the Sluice backend services. From the repository root:

```bash
docker compose up --build -d postgres sluice-api
cd frontend
npm ci
npm run dev
```

Open the Vite URL, normally http://localhost:5173. The development proxy sends `/api/jobs` to `http://localhost:8080/jobs`, including query parameters. No backend CORS changes are needed for this setup.

The **Success** preset completes quickly. **Slow job** uses 6,000 ms of upstream latency so its claimed state is easier to observe. **Failure** repeatedly receives HTTP 429 with `Retry-After: 3` and eventually exhausts the configured failure budget. Local throttling can add delays without increasing attempts. Unsupported custom job types have no registered handler and will follow the backend's failure path.

Use a new idempotency key for each distinct demo, or leave it blank. Reusing a key returns the existing job, including its original payload and status.

The submission form can enqueue an atomic batch of 5, 10, 20, or 40 jobs. A key supplied for a batch becomes a prefix (`key:1`, `key:2`, …). Reuse the same prefix and count to retrieve the original batch.

## Test lab

Expand **Tests** in the sidebar and choose a scenario to open Test lab. Every scenario has a circled **i** explanation available on hover, keyboard focus, or tap. Inputs change with the selected scenario:

- **Batch & queue:** choose more jobs than workers, latency, and a claim pause; watch pending/claimed/completed lanes and real worker slots.
- **Idempotency:** concurrent submissions with one key merge into one persisted job.
- **Concurrency & locks:** a real transaction locks the first row while workers claim other rows with `SKIP LOCKED`.
- **Lease expiry:** an abandoned worker stops heartbeats; the scheduled reaper recovers the job for another worker.
- **Heartbeat renewal:** a job outlasts its initial lease while successful renewal pulses move its deadline forward.
- **Retries & dead letter:** configure the failure budget and Retry-After; follow the attempt rail and backoff countdown.
- **Rate limiting:** observe local deferrals through a shared capacity-one bucket refilling at one token per second, without spending failure attempts.

Start with **10 jobs**, **2 threads**, **3 seconds latency**, **1 second claim pause** in Batch & queue, then click **Run batch & queue**. Each test has a separate real worker pool and reserved jobs. Job cards open the ordinary inspector; the event log and final checks provide evidence for the animation.

One run is allowed at a time, up to 40 jobs/submissions and eight threads, with a three-minute deadline. Reports retain up to 2,000 events, and the API keeps 20 reports in memory. Restart loses reports and stops active workers; unfinished reserved jobs remain in PostgreSQL. Completed jobs also remain visible in global dashboard counts. Disable the local lab with `SLUICE_LAB_ENABLED=false`. Rebuild the API after pulling the feature so `/jobs/batch` and `/tests/runs` are available.

Submission JSON includes `priority: 0` because the current `EnqueueRequest` record contains a primitive `int priority` and the runtime rejects an omitted value. The controller currently enqueues at priority zero; the interface does not offer unsupported priority controls. The payload is JSON-stringified inside the outer request body.

## Configuration and production build

Copy `.env.example` to `.env.local` if you need to change `VITE_API_BASE_URL`, then restart Vite. This public, build-time value can be a path such as `/api` or an API origin. Do not put secrets in Vite environment variables.

```bash
npm run lint
npm test
npm run build
```

`dist/` contains the static production assets. `npm run preview` serves the build locally for visual checks, but does **not** include the development API proxy.

For a separate Netlify deployment, the root `netlify.toml` supplies the base directory (`frontend`), build command (`npm run build`), publish directory (`dist`), and Node version. Set `VITE_API_BASE_URL=https://YOUR_BACKEND.onrender.com` in Netlify's build environment, with no `/api` suffix. Set `SLUICE_CORS_ALLOWED_ORIGINS=https://YOUR_FRONTEND.netlify.app` in the backend environment. Changing the API URL requires rebuilding the frontend; changing CORS requires restarting/redeploying the backend. Database credentials stay only on the backend.

The backend now embeds the demo upstream, so hosted deployments do not need a separate mock service. Follow the root [deployment guide](../DEPLOYMENT.md) in PostgreSQL → backend → frontend order.

## Implementation

- `src/lib/api.js`: configurable API client, payload serialization, HTTP errors, request timeout, and cancellation.
- `src/lib/connection.js` and `src/hooks/useConnection.js`: shared readiness state, serialized startup retries, a three-minute deadline, cancellation, and manual recovery.
- `src/lib/poller.js` and `src/hooks/useDashboard.js`: serialized polling; manual refreshes coalesce; old queries are cancelled and stale responses ignored. Each successful section retains its data during failures. The displayed refresh time advances only when all visible requests succeed.
- `src/components/`: submission form, filterable job table, status badges, icons, and a native dialog inspector with keyboard focus management.
- `src/components/TestLab.jsx`, `TestVisualization.jsx`, and `InfoTip.jsx`: changing scenario forms, keyboard-accessible tooltips, live animations, worker ownership, event log, and measured results. Lab polling is serialized at 600 ms; dashboard polling remains three seconds.
- `src/lib/scenarios.js`: scenario explanations, defaults, and input constraints.
- `src/styles.css`: dark theme tokens, responsive layout, compact table, expandable settings, focus states, and reduced-motion support. Fonts use Google Fonts with local system fallbacks.
- `src/lab.css`: scenario visuals, responsive lab/sidebar controls, and reduced-motion support.
- `tests/`: 25 tests using Node's built-in runner verify API contracts, errors, payload handling, polling concurrency, and connection startup/recovery without needing Docker.

The jobs API returns an array, so pagination shows the loaded page size and page number without inventing a filtered total. A full page enables Next; a short or empty page disables it. Statistics always cover the entire queue. Polling preserves form input and selection. Dates use the viewer's local timezone. Job details distinguish current status from retained claim metadata.

Lab events and worker slots cover the current lab run; the ordinary queue does not expose worker-pool history or calculated performance metrics. Activity bars indicate processing and are not completion percentages. Recovery may execute work more than once, so external side effects still need handler-level idempotency.
