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
| Blood compatibility | `BloodGroup` encodes A/B/Rh antigens. A B+ patient gets B+, B-, O+, O- donors. Exact-group donors are listed first so universal O- blood is saved. |
| Geo search | A lat/lng bounding-box query in PostgreSQL narrows the candidates, then the Haversine formula gives exact km. Works on plain PostgreSQL, no extension needed. |
| Eligibility rules | Donation gap of at least 90 days, age 18–65, and the donor's availability switch. All rules are configurable. |
| Request lifecycle | `OPEN → MATCHED → FULFILLED`, plus `CANCELLED` and `EXPIRED`. If an accepted donor backs out, the request reopens and replacement donors are notified. |
| One commitment at a time | A donor can't accept a second request while an earlier acceptance is still active. |
| Concurrency safety | `SELECT … FOR UPDATE` row locks on the request and the donor, so simultaneous accepts can't over-fill a request or double-book a donor. |
| Auto radius expansion | `@Scheduled` job: if an OPEN request gets no luck for 30 min, the radius grows 10 → 20 → … → 50 km and newly reached donors are notified. CRITICAL requests start at 20 km. |
| Privacy | Hospitals see donor phones only after the donor accepts. Donors see the hospital phone only after accepting, and never see other donors. |
| Reminders | Daily 9 AM job notifies donors whose 90-day wait ends today. |
| API docs | Swagger UI at `/swagger-ui.html`, with an **Authorize** button for the JWT. |

## Roles

| Role | Can do |
|---|---|
| **ADMIN** | Add hospitals (with their staff login), view any donor, see and manage every request |
| **HOSPITAL** | Raise requests, see its own requests and the donor responses, confirm donations, cancel its own requests |
| **DONOR** | Sign up, see requests sent to them, accept or decline, set availability, check eligibility and donation history |
| *Public* | Donor sign-up, login, blood-group compatibility table, nearby-donor search (phones masked) |

## Tech stack

Java 21 · Spring Boot 4.1 · Spring Security (OAuth2 resource server, JWT) · Spring Data JPA (Hibernate) · PostgreSQL · Bean Validation · springdoc-openapi · JUnit 5 + H2 for tests

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

## Running it

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

Tables are created automatically. Open **http://localhost:8080/swagger-ui.html**.

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
| POST | `/api/auth/register` | public | Donor sign-up, returns a token |
| POST | `/api/auth/login` | public | Login, returns a token |
| GET | `/api/auth/me` | any logged in | Who am I |
| GET | `/api/me` | DONOR | My profile |
| PATCH | `/api/me/availability` | DONOR | `{"available": false}` |
| GET | `/api/me/eligibility` | DONOR | Eligible? Reasons, next eligible date |
| GET | `/api/me/donations` | DONOR | My donation history |
| GET | `/api/me/requests` | DONOR | Requests I was notified about |
| POST | `/api/me/requests/{id}/accept` | DONOR | Accept |
| POST | `/api/me/requests/{id}/decline` | DONOR | Decline / back out |
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

Status codes: `400` invalid input · `401` missing/invalid token or wrong password · `403` wrong role or not your data · `404` not found · `409` business rule (e.g. donor not eligible).

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

Notifications are written to the console log (`[NOTIFY 98400…] …`). To send real SMS/WhatsApp messages,
implement `NotificationService` with a provider such as Twilio or MSG91.

## Configuration (`application.properties`)

```properties
app.min-days-between-donations=90
app.min-donor-age=18
app.max-donor-age=65
app.initial-radius-km=10
app.radius-step-km=10
app.max-radius-km=50
app.expand-after-minutes=30
app.security.jwt-expiry-minutes=120
jobs.expand-search.cron=0 */5 * * * *
jobs.eligibility-reminder.cron=0 0 9 * * *
```

## Ideas to extend

- Refresh tokens and logout (token blacklist)
- PostGIS `geography` column and `ST_DWithin` for very large donor tables
- Flyway migrations instead of `ddl-auto=update`
- Real SMS/WhatsApp notifications, plus a React frontend with a map view
