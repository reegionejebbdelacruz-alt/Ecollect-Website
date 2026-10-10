# ♻️ Ecollect — Barangay Garbage & Collection Schedule System

**Business name — what the system does — core entity for CRUD:**
*Ecollect — barangay garbage collection scheduling for residents — collection schedules*

A public landing page + user accounts + admin-managed garbage collection schedules with live truck status.
Built in **one Java file, zero dependencies** — runs anywhere Java 17+ is installed.

## Problem being solved

Residents often don't know **which day** the garbage truck comes, **what time** to bring trash out,
**which type of waste** is collected, or **whether the truck is already on its way** — so trash piles up,
pickups are missed, and streets get dirty. Barangays also lack a simple official board they can update themselves.

Ecollect fixes this: the city **admin** posts weekly collection schedules (city, barangay, day, time,
truck, waste type, status). Residents **register with their city** and instantly
see their city's pickups, a city badge on every page, and — on their collection day —
whether the truck is **on its way or already at their pickup point**.

## Feature list

**1. Landing page (`/` — no login required)**
- Business name, description, 4 service cards, truck illustration image, "How it works" section
- Hero banner photo behind the text on home, dashboard, and admin pages (`hero.png` is included by default — drop in your own `hero.jpg` to replace it)
- Full weekly calendar is **members-only** (login wall) — visitors see a teaser, so registering is worth it
- Clear call to action: **Register — find my schedule** / **Login**
- Responsive / mobile-friendly layout (flex, grid, scrollable tables)

**2. Users**
- **Register** with name, email, password (min. **8** characters) **+ city** (`/register`)
- **Login / Logout** (`/login`, `/logout`) — logout shows a *"🙏 Thank you for your cooperation!"* message
- **Forgot password** (`/forgot`) — reset it with your email + a new password (no mail server needed)
- Wrong password? The login page **keeps your typed email** and says *"Incorrect password"* so you know exactly what went wrong
- Passwords **hashed with PBKDF2-HMAC-SHA256 + random salt** — never stored as plain text (see `data.json`)
- Two roles: **admin** (manages everything) vs **regular user** (views own city's data)
- Protected pages redirect to `/login` when not signed in
- **City indication:** after login/register, a `📍 City` badge appears in the navbar, the dashboard
  header (with today's **date**), and the schedules page, and lists default to the user's city
- **Admin-only enforcement:** create/update/delete URLs return `403 Forbidden` for non-admins,
  and edit/delete buttons are hidden from regular users

**3. CRUD (core entity: collection schedule)**
- **Create** (`POST /admin/create`), **Read** (landing, dashboard, `/schedules`, `/admin`),
  **Update** (`POST /admin/update`), **Delete** (`POST /admin/delete`) — all on `/admin`
- Fields: city, barangay, day, time (displayed as **AM/PM**), truck (**10 trucks: A–J**), waste type, status (Scheduled / On The Way / At Location / Collected / Missed / Cancelled)
- **Busy-truck rule:** one truck serves only ONE place per day — double-booking is rejected (e.g. *"⛔ Truck A is already busy on Monday..."*)
- Validation with friendly errors (required fields, day/time/waste whitelists, min lengths)
- **Delete asks for confirmation** (browser `confirm()` dialog: *"This cannot be undone."*)
- Data **persists in `data.json`** — still there after restart
- **Live truck status:** the admin marks each truck Scheduled / On The Way / At Location / Collected / Missed / Cancelled, and the dashboard tells residents if the truck is on its way or already at their pickup point

## Tech stack

- **Java 17+** (tested on Java 21) — `com.sun.net.httpserver.HttpServer` (built into the JDK)
- **No frameworks, no libraries, no database server** — file storage in `data.json`
- Server-rendered HTML + inline CSS (no build step, no npm)
- Password hashing: PBKDF2-HMAC-SHA256 (JDK `javax.crypto`, 31,000 iterations + salt)
- Sessions: random UUID tokens in an HTTP-only cookie

## Setup and run instructions (tested from a clean machine)

Requirements: **Java 17 or newer** — check with `java -version`.
Download from https://adoptium.net if needed. Nothing else to install.

```bash
# 1. Get the code
git clone <your-repo-url>
cd ecocollect

# 2. Run (no build, no dependencies)
java Main.java
# Windows alternative: double-click run.bat | Mac/Linux: ./run.sh

# 3. Open
http://localhost:8080
```

- First run creates `data.json` with demo accounts + 6 sample schedules.
- Custom port: `java Main.java 9090`
- Stop: press `Ctrl+C`. Data is saved after every change, so nothing is lost.

## Test accounts

| Role | Email | Password | City |
|---|---|---|---|
| Admin | `admin@ecollect.ph` | `admin123` | Quezon City |
| Regular user | `maria@example.com` | `user1234` | Cebu City |

Try this: login as Maria → dashboard shows `📍 Cebu City` + her city's pickups.
Login as admin → `🛠️ Admin` page can add/edit/delete schedules (users get 403).

## AI tools used and what they were used for

- **opencode (Muse Spark 1.3)** — generated the initial `Main.java` scaffold (routing, templates,
  hashing, JSON storage), the README, and the BUILDING_GUIDE; assisted with debugging compile errors.
- All code was reviewed, run, and tested locally by the student. Prompts used included:
  *"create a garbage collection schedule system in one Java file with landing page, login/register
  with city, admin-only CRUD, and live truck status"* and follow-ups for validation and delete confirmation.

## Project structure

```
Main.java           → entire app (models, storage, hashing, templates, router)
data.json           → created on first run (users + schedules; passwords are hashes)
README.md           → this file
BUILDING_GUIDE.md   → step-by-step tutorial (how it was built & deployed)
run.bat / run.sh    → one-click launchers
```

## Screenshots (optional — add your own)

Take screenshots of `/` (landing), `/dashboard` (city badge + today's truck status), and `/admin` (CRUD)
and drop them in a `screenshots/` folder.
