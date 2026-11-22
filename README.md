# Blood Donor Emergency Network

A Spring Boot + PostgreSQL REST API that connects hospitals with nearby blood donors during emergencies.

When a hospital raises a request, the system finds **eligible, blood-compatible donors near the hospital**,
notifies them, tracks who accepts, and closes the request once enough blood is donated. Background jobs
widen the search radius when nobody responds, expire overdue requests, and remind donors when they can donate again.
Everything is protected with **JWT login and three roles**: ADMIN, HOSPITAL and DONOR.

## Features

| Feature | How it works |
|---|---|
| JWT security | Stateless Spring Security with HS256-signed tokens (Nimbus). BCrypt password hashes. Three roles, plus ownership checks: a hospital only sees its own requests, and a donor's id always comes from the token. |
| Sessions | 15-minute access tokens plus a 14-day refresh token in an **httpOnly, SameSite=Strict cookie** that JavaScript can't read. Refresh tokens are stored hashed and **rotated on every use**. Replaying an old one revokes every session from that login (theft detection). Logout revokes the token, and "All devices" ends every session. The browser renews tokens silently. |
| Instant logout | Access tokens carry their session id (`sid`) and the account's token version (`tv`). A validator checks both on every request, cached for 30 s and updated immediately on logout, so a logged-out token gets **401 at once**, not after 15 minutes. The session's open live stream is closed too, and other tabs log out by themselves. |
| Brute-force protection | 5 failed password or OTP logins in a row lock that username for 15 minutes, even for the right password: `429` with a `Retry-After` header. Unknown usernames lock the same way, so responses never reveal which accounts exist. 20 failures from one IP block that IP. A successful login resets the counter, admins can unlock early, and locks and IP blocks are written to the audit log. All limits are configurable. |
| Blood compatibility | `BloodGroup` encodes A/B/Rh antigens. A B+ patient gets B+, B-, O+, O- donors. Exact-group donors are listed first so universal O- blood is saved. |
| Geo search | A lat/lng bounding-box query in PostgreSQL narrows the candidates, then the Haversine formula gives exact km. Works on plain PostgreSQL, no extension needed. |
| Eligibility rules | Donation gap of at least 90 days, age 18–65, and the donor's availability switch. All rules are configurable. |
| Request lifecycle | `OPEN → MATCHED → FULFILLED`, plus `CANCELLED` and `EXPIRED`. If an accepted donor backs out, the request reopens and replacement donors are notified. |
| One commitment at a time | A donor can't accept a second request while an earlier acceptance is still active. |
| Concurrency safety | `SELECT … FOR UPDATE` row locks on the request and the donor, so simultaneous accepts can't over-fill a request or double-book a donor. |
| Auto radius expansion | `@Scheduled` job: if an OPEN request gets no luck for 30 min, the radius grows 10 → 20 → … → 50 km and newly reached donors are notified. CRITICAL requests start at 20 km. |
| Privacy | Hospitals see donor phones only after the donor accepts. Donors see the hospital phone only after accepting, and never see other donors. |
| Reminders | Daily 9 AM job notifies donors whose 90-day wait ends today. |
| Web app | Built-in frontend at `/` with donor, hospital and admin dashboards and Leaflet maps. |
| Blood bank inventory | Units per blood group per hospital, with a minimum level. Falling below it automatically raises a restock request to nearby donors, and the units donated for it go back into stock. |
| Donation camps | Hospitals run one-day camps with time slots. Eligible donors within 15 km are invited, book a slot (row lock prevents overbooking), get a QR pass, and are checked in on the day by scanning it with the camera (or typing the code), which records the donation and adds a unit to stock. |
| Live updates | Server-Sent Events (`/api/live`) push changes after commit: hospitals see accepts and bookings, donors see new requests, and accepted donors appear on the request map (~1 km precision). |
| Donor rewards | Badges (First Drop 1, Bronze 3, Life Saver 5, Hero 10, Legend 25), "lives helped", and a public leaderboard showing short names only. |
| Admin analytics | Fulfilment rate, average response and fulfilment time, demand vs supply per blood group, a 14-day trend, and per-hospital stats. |
| User management | Admins search accounts (last login, active sessions), **disable** one (logged out everywhere at once; the last admin and your own account are protected), enable it again, or **reset a password** (a random temporary password shown once). Anyone can change their own password, which logs out their other devices. |
| Audit log | Append-only record of logins, failed logins (with reason and IP), OTP logins, logouts, token-theft detection, password changes and resets, disables and enables, sign-ups and new hospitals. Each entry is written in its own transaction, so failures are recorded too. Filterable in the admin UI. |
| OTP | Sign-up verifies the mobile with a 6-digit SMS code, and donors can log in with a code instead of a password. Codes are hashed, expire, are rate limited and allow 5 attempts. |
| Mobile app (PWA) | Installable on Android, iPhone and desktop ("📲 Install app"), with an offline page when there is no signal. Donors can turn on **push notifications**: a nearby blood request or camp invite pops up on the phone even when the app is closed. Web Push is built with JDK crypto only (VAPID / RFC 8292, payload encryption / RFC 8291, checked against the RFC test vector). Logging out stops alerts on that device. |
| WhatsApp | `SMS_CHANNEL=whatsapp` sends the same notifications over Twilio WhatsApp. |
| API docs | Swagger UI at `/swagger-ui.html`, with an **Authorize** button for the JWT. |

## Web frontend

A **React 19** app (Vite, React Router, react-leaflet) lives in [`frontend/`](frontend).
Its production build goes into `src/main/resources/static`, so Spring Boot serves it at **http://localhost:8080/**.

```
frontend/src
├── main.jsx, App.jsx          routes + role guard (RequireRole)
├── api/client.js              fetch wrapper: JWT header, error messages, one helper per endpoint
├── auth/AuthContext.jsx       login/register/logout, session kept in localStorage
├── hooks/usePolling.js        load data and refresh every 20 s
├── components/                maps (Leaflet), request list/detail/form, toast, badges
└── pages/                     AuthPage, DonorDashboard, HospitalDashboard, AdminDashboard
```

**Working on the frontend** (Node.js 20+ needed), with Spring Boot running on :8080:

```bash
cd frontend
npm install
npm run dev      # http://localhost:5173 with hot reload; /api is proxied to :8080
npm run build    # writes the bundle into ../src/main/resources/static
```

Only run `npm run build` again when you change the frontend. Otherwise `./mvnw spring-boot:run` alone serves the last build.

| Screen | What you can do |
|---|---|
| Login / sign-up | Log in (demo account buttons included), register as a donor by picking your location on a map, check blood-group compatibility |
| Donor | Eligibility countdown, availability switch, nearby requests with **I can donate / Decline**, hospital phone after accepting, donation history |
| Hospital | Raise an emergency request, filter requests, see the search radius on a map, see donors and their responses, **Mark donated**, cancel |
| Admin | Every request across hospitals (and raise one for any hospital), add hospitals with a map location picker and a staff login |

Lists refresh automatically every 20 seconds. Maps use OpenStreetMap tiles via Leaflet, loaded from the internet.

## Roles

| Role | Can do |
|---|---|
| **ADMIN** | Add hospitals (with their staff login), view any donor, see and manage every request |
| **HOSPITAL** | Raise requests, see its own requests and the donor responses, confirm donations, cancel its own requests |
| **DONOR** | Sign up, see requests sent to them, accept or decline, set availability, check eligibility and donation history |
| *Public* | Donor sign-up, login, blood-group compatibility table, nearby-donor search (phones masked) |

## Tech stack

**Backend:** Java 21 · Spring Boot 4.1 · Spring Security (OAuth2 resource server, JWT) · Spring Data JPA (Hibernate) · PostgreSQL · Flyway · Bean Validation · springdoc-openapi · Docker / Docker Compose · JUnit 5 + H2 for tests

**Frontend:** React 19 · Vite · React Router · Leaflet / react-leaflet (OpenStreetMap)

## Project structure

```
src/main/java/com/blooddonor
├── security/     SecurityConfig, JwtService, AuthService, CurrentUser, AccessGuard, AdminBootstrap
├── entity/       Donor, Hospital, BloodRequest, DonorResponse, UserAccount, BloodGroup (+ compatibility), statuses
├── repository/   Spring Data repositories (bounding-box query, row locks)
├── service/      DonorService, BloodRequestService, DonorMatchingService, EligibilityService, ScheduledJobs
├── controller/   Auth, Me (donor self-service), Requests, Hospitals, Donors, BloodGroups
├── dto/          Request/response records with validation
├── config/       AppProperties (business rules), OpenAPI, sample data loader, Clock
├── exception/    400 / 401 / 403 / 404 / 409 handling with ProblemDetail
└── util/         GeoUtils (Haversine, bounding box)
```

## Quick start with Docker (no Java or PostgreSQL install needed)

With [Docker Desktop](https://www.docker.com/products/docker-desktop/) installed:

```bash
docker compose up --build
```

This builds the app image (multi-stage: JDK to build, JRE to run, non-root user) and starts it with PostgreSQL 18.
The first build downloads dependencies and takes a few minutes. Then open **http://localhost:8080** (web app) or
**http://localhost:8080/swagger-ui.html** (API), and log in with the demo accounts below.

- Port 8080 already in use? Run with another port, e.g. PowerShell `$env:APP_PORT=9090; docker compose up --build`.
- Stop: `docker compose down`. Stop and delete the database too: `docker compose down -v`.
- Override secrets with env vars or a `.env` file: `DB_PASSWORD`, `JWT_SECRET`, `ADMIN_USERNAME`, `ADMIN_PASSWORD`,
  and the `SMS_*` / `TWILIO_*` variables for real SMS.

## Running it without Docker

### 1. Install PostgreSQL and create the database

Install PostgreSQL from https://www.postgresql.org/download/windows/ and remember the password you set for `postgres`. Then:

```bash
psql -U postgres -c "CREATE DATABASE blood_donor;"
```

### 2. Run the app

The defaults are `localhost:5432/blood_donor`, user `postgres` and password `postgres`.
If your password is different, set it first (PowerShell: `$env:DB_PASSWORD="yourpassword"`).

```bash
./mvnw spring-boot:run
```

Flyway creates the tables from `src/main/resources/db/migration` on first start. Open **http://localhost:8080/** for the web app, or
**http://localhost:8080/swagger-ui.html** for the raw API.

### 3. Demo logins

On first start an admin account is created, plus fictional Chennai sample data with logins:

| Role | Username | Password |
|---|---|---|
| ADMIN | `admin` | `admin123` |
| HOSPITAL: City Care (T. Nagar) | `04420001111` | `hospital123` |
| HOSPITAL: Sunrise (Velachery) | `04420002222` | `hospital123` |
| HOSPITAL: Lakeview (Anna Nagar) | `04420003333` | `hospital123` |
| DONOR: any sample donor, e.g. Arun (B+) | `9840000001` … `9840000010` | `donor123` |

These defaults are for local development only. Elsewhere, set the `JWT_SECRET` (at least 32 characters),
`ADMIN_USERNAME` and `ADMIN_PASSWORD` environment variables, and set `app.sample-data=false`.

### 4. Using a token in Swagger

1. `POST /api/auth/login` with `{"username":"04420001111","password":"hospital123"}`
2. Copy `accessToken` from the response.
3. Click **Authorize** (top right), paste the token, click Authorize.
4. Now call any endpoint allowed for that role. Log in as another user and Authorize again to switch roles.

### 5. Run the tests (no PostgreSQL needed, they use in-memory H2)

```bash
./mvnw test
```

> If Maven says `JAVA_HOME is not defined correctly`, point JAVA_HOME at your JDK 21 folder,
> e.g. PowerShell: `$env:JAVA_HOME="C:\Program Files\Java\jdk-21"`.

## API

| Method | Endpoint | Who | Purpose |
|---|---|---|---|
| POST | `/api/auth/otp/request` | public | `{"phone","purpose":"REGISTER"|"LOGIN"}`: send a 6-digit code by SMS |
| POST | `/api/auth/register` | public | Donor sign-up with `otpCode`, returns a token |
| POST | `/api/auth/login` | public | Login, returns a token |
| POST | `/api/auth/otp/login` | public | Donor login with `{"phone","code"}` instead of a password |
| POST | `/api/auth/refresh` | refresh cookie | New access token; rotates the cookie |
| POST | `/api/auth/logout` | refresh cookie | Log out on this device |
| POST | `/api/auth/logout-all` | any logged in | Log out on every device |
| GET | `/api/live` | any logged in | Server-Sent Events stream of live updates |
| GET | `/api/me/rewards` | DONOR | Badge, donations, lives helped, rank |
| GET/POST | `/api/me/camp-bookings` (+ `/{id}/cancel`) | DONOR | My camp passes, book a slot |
| GET | `/api/leaderboard` | public | Top donors (short names) |
| GET | `/api/camps?lat=..&lng=..`, `/api/camps/{id}/slots` | public | Upcoming camps, free places per slot |
| POST | `/api/camps`, GET `/api/camps/hosted`, `/{id}/bookings`, POST `/{id}/cancel`, `/check-in` | HOSPITAL, ADMIN | Run camps |
| GET | `/api/inventory`, PUT `/{group}`, POST `/{group}/use`, `/{group}/add` | HOSPITAL (own), ADMIN (`?hospitalId=`) | Blood bank |
| GET | `/api/inventory/overview`, `/api/admin/analytics` | ADMIN | All blood banks, analytics |
| GET | `/api/admin/users?q=&role=&enabled=&page=` | ADMIN | Search accounts |
| POST | `/api/admin/users/{id}/disable` `{"reason"}`, `/enable`, `/unlock`, `/reset-password` | ADMIN | Manage an account |
| GET | `/api/admin/audit?user=&action=&page=` | ADMIN | Audit log, newest first |
| POST | `/api/auth/change-password` `{"currentPassword","newPassword"}` | any logged in | Change my password |
| GET | `/api/auth/me` | any logged in | Who am I |
| GET | `/api/me` | DONOR | My profile |
| PATCH | `/api/me/availability` | DONOR | `{"available": false}` |
| GET | `/api/me/eligibility` | DONOR | Eligible? Reasons, next eligible date |
| GET | `/api/me/donations` | DONOR | My donation history |
| GET | `/api/me/requests` | DONOR | Requests I was notified about |
| POST | `/api/me/requests/{id}/accept` | DONOR | Accept |
| POST | `/api/me/requests/{id}/decline` | DONOR | Decline / back out |
| GET | `/api/push/vapid-public-key` | public | Server key the browser subscribes with |
| GET/POST/DELETE | `/api/me/push-subscriptions` | DONOR | Devices with alerts on / add this browser `{"endpoint","keys":{"p256dh","auth"}}` / remove it `{"endpoint"}` |
| POST | `/api/me/push-subscriptions/test` | DONOR | Send a test alert to my devices |
| POST | `/api/requests` | HOSPITAL, ADMIN | Raise an emergency request (auto-notifies donors) |
| GET | `/api/requests?status=OPEN` | HOSPITAL (own), ADMIN (all) | List requests |
| GET | `/api/requests/{id}` | owner HOSPITAL, ADMIN | Request with donor responses |
| POST | `/api/requests/{id}/donors/{donorId}/donated` | owner HOSPITAL, ADMIN | Confirm donation |
| POST | `/api/requests/{id}/cancel` | owner HOSPITAL, ADMIN | Cancel |
| POST | `/api/hospitals` | ADMIN | Add a hospital and its staff login |
| GET | `/api/hospitals` | any logged in | List hospitals |
| GET | `/api/donors/{id}` (+ `/eligibility`, `/donations`) | ADMIN | Any donor |
| GET | `/api/donors/nearby?bloodGroup=B_POS&lat=..&lng=..&radiusKm=10` | public | Nearby eligible donors (masked phones) |
| GET | `/api/blood-groups` | public | Compatibility table |

Blood group values: `O_NEG, O_POS, A_NEG, A_POS, B_NEG, B_POS, AB_NEG, AB_POS`.

Status codes: `400` invalid input · `401` missing/invalid token or wrong password · `403` wrong role or not your data · `404` not found · `409` business rule (e.g. donor not eligible) · `429` too many OTP requests.

### Full flow with curl (fresh database)

```bash
# 1. City Care Hospital logs in
HOSP=$(curl -s -X POST localhost:8080/api/auth/login -H "Content-Type: application/json" -d '{"username":"04420001111","password":"hospital123"}' | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p')

# 2. It needs 1 unit of B+ (hospitalId comes from the token)
curl -X POST localhost:8080/api/requests -H "Authorization: Bearer $HOSP" -H "Content-Type: application/json" -d '{"patientName":"Ravi","bloodGroup":"B_POS","unitsNeeded":1,"urgency":"HIGH","neededBy":"2030-01-01T18:00:00"}'

# 3. Arun (B+, donor id 1) logs in, sees the request and accepts -> MATCHED
ARUN=$(curl -s -X POST localhost:8080/api/auth/login -H "Content-Type: application/json" -d '{"username":"9840000001","password":"donor123"}' | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p')
curl -H "Authorization: Bearer $ARUN" localhost:8080/api/me/requests
curl -X POST -H "Authorization: Bearer $ARUN" localhost:8080/api/me/requests/1/accept

# 4. Hospital confirms the donation -> FULFILLED; Arun now has to wait 90 days
curl -X POST -H "Authorization: Bearer $HOSP" localhost:8080/api/requests/1/donors/1/donated
curl -H "Authorization: Bearer $ARUN" localhost:8080/api/me/eligibility
```

## SMS notifications

By default, messages are only written to the console log (`[SMS to +9198400…] …`).
Set `SMS_PROVIDER=twilio` to send real SMS through [Twilio](https://www.twilio.com).

How it works (`com.blooddonor.notification`):
- `SmsNotificationService` converts numbers to E.164 (`9840000001` → `+919840000001`, landline `04420001111` → `+914420001111`).
- It sends **only after the database transaction commits**, so nobody gets a text about a change that was rolled back.
- It sends on a **background thread**, so a slow or failing SMS provider never slows down or breaks an API call. Failures are logged.
- `TwilioSmsSender` calls Twilio's REST API directly with Spring's `RestClient`, so no SDK is needed.
- **Safety:** `SMS_ALLOWED_NUMBERS` limits real SMS to the numbers you list. The sample donors use made-up numbers
  that may belong to real people, so always set it while testing.

### Try it with a free Twilio trial

1. Sign up at twilio.com. A trial can only send to numbers you verify, so verify your own mobile under
   *Phone Numbers → Verified Caller IDs*.
2. From the Twilio console, copy the **Account SID** and **Auth Token**, and get a trial **phone number** (the sender).
3. Set the env vars (PowerShell) and start the app:
   ```powershell
   $env:SMS_PROVIDER="twilio"
   $env:TWILIO_ACCOUNT_SID="AC..."
   $env:TWILIO_AUTH_TOKEN="..."
   $env:TWILIO_FROM_NUMBER="+1..."
   $env:SMS_ALLOWED_NUMBERS="+91<your mobile>"
   ./mvnw spring-boot:run
   ```
4. Register yourself as a donor with that mobile number near a sample hospital, then raise a request for your
   blood group as the hospital. The SMS arrives on your phone.

Never commit the auth token. Keep it in env vars only.

## Database migrations (Flyway)

The schema lives in versioned SQL files in `src/main/resources/db/migration`, and Hibernate runs with
`ddl-auto=validate`: it never changes tables, it only refuses to start if an entity and the schema disagree.

- **New database:** Flyway runs `V1__init_schema.sql` and records it in `flyway_schema_history`.
- **Database created before Flyway** (by the old `ddl-auto=update`): `baseline-on-migrate` marks it as version 1
  without touching the data, and later migrations apply normally.
- **Changing the schema:** change the entity, then add a new file such as `V2__add_donor_weight.sql`.
  Never edit a migration that has already run, because Flyway checksums it.
- The tests run the same migrations on H2 (PostgreSQL mode) and validate them against the entities, so a missing
  migration fails the build.

## Configuration (`application.properties`)

```properties
app.min-days-between-donations=90
app.min-donor-age=18
app.max-donor-age=65
app.initial-radius-km=10
app.radius-step-km=10
app.max-radius-km=50
app.expand-after-minutes=30
app.security.jwt-expiry-minutes=15
app.security.refresh-token-days=14
app.security.cookie-secure=false      # set COOKIE_SECURE=true when served over https
app.security.max-failed-logins=5
app.security.lock-minutes=15
app.security.max-failed-logins-per-ip=20
app.push.subject=mailto:admin@blooddonor.local   # PUSH_SUBJECT: contact for push services
jobs.expand-search.cron=0 */5 * * * *
jobs.eligibility-reminder.cron=0 0 9 * * *
jobs.refresh-token-cleanup.cron=0 30 3 * * *
```

## Mobile app and push notifications

- **Install:** open the site on a phone. Chrome/Edge show "📲 Install app" in the top bar; on iPhone use Share → Add to Home Screen.
- **Alerts:** a donor taps **🔔 Turn on alerts** on their dashboard and allows notifications. "Send test alert" checks it works.
- The server generates its VAPID key pair on first use and keeps it in the `app_settings` table. Don't delete it: every browser subscribed with that key.
- Push and service workers need **https** (or `localhost`). Dead subscriptions are removed automatically.
- The service worker is registered only in the production build (`npm run build`), not in `npm run dev`.

## Ideas to extend

- Sharing session revocations between several app instances (Redis pub/sub) when scaling out
- PostGIS `geography` column and `ST_DWithin` for very large donor tables
- An Indian SMS gateway with DLT templates (MSG91, Fast2SMS)
- Analytics as SQL aggregate queries or a reporting table for large data
