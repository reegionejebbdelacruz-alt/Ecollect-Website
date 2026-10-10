# BUILDING_GUIDE.md — How to build & deploy Ecollect, step by step

This companion guide walks through the whole task: plan → claim idea → build → test → deploy → submit.
Follow it in order. Estimated time: 3–5 hours (faster with AI help, but read every step so you can explain it).

---

## Step 0 — Claim your unique idea (do this FIRST)

1. Open the class idea list / group chat and search for "garbage", "waste", "collection", "schedule".
2. If nobody claimed it, post:
   **Ecollect — barangay garbage collection scheduling — collection schedules**
3. First to claim wins. If it's taken, pivot (e.g. "street-sweeping crew tracker" or "recycling junkshop buy-back system").

## Step 1 — Install what you need (15 min)

You only need **Java 17+**. Nothing else — no Node, no database, no IDE required.

1. Check: open a terminal and run `java -version`.
2. If missing, install Temurin/Adoptium JDK 21 from https://adoptium.net, then re-open the terminal.
3. Optional but recommended: install **Git** (https://git-scm.com) and **VS Code**.

Verify: `java -version` prints something like `openjdk version "21..."`.

## Step 2 — Understand the plan (20 min)

The system has exactly the 3 required parts:

| Part | Page(s) | Who can use it |
|---|---|---|
| 1. Landing page | `GET /` | Everyone (no login) |
| 2. Users | `GET/POST /register`, `GET/POST /login`, `GET /logout`, `GET /dashboard` | Public registration; dashboard needs login |
| 3. CRUD | `GET /admin`, `POST /admin/create`, `POST /admin/update`, `POST /admin/delete` | **Admin only** (users get 403) |

Plus the two special requirements:
- **City indication:** register collects `city`; navbar + dashboard always render `📍 {city}`; schedule lists default to the user's city.
- **Live truck status:** the admin marks each truck Scheduled / On The Way / At Location / Collected / Missed / Cancelled; the dashboard shows *"Your truck status today"* with on-its-way / already-here messaging.

Data model (keep it small!):
- `User { id, name, email, city, passHash, role }`
- `Schedule { id, city, barangay, day, time, truck, waste, status }`

## Step 3 — Build it (with AI, responsibly) (2–3 hours)

The whole app is one file: `Main.java`. Suggested build order (ask your AI tool for one piece at a time):

1. **Skeleton:** `HttpServer` on port 8080 + one route `GET /` returning "hello". Run `java Main.java`, open http://localhost:8080. ✅ milestone 1.
2. **Landing page:** HTML template with hero, services grid, schedule table, CTA buttons, mobile CSS. ✅ milestone 2.
3. **Storage:** `save()`/`load()` to `data.json` + seed admin/user/schedules on first run. Restart the app — data must still be there. ✅ milestone 3.
4. **Auth:** register/login/logout, `hashPassword`/`verifyPassword` (PBKDF2), session cookie, protected `/dashboard` with city badge. Open `data.json` and confirm passwords are hashes, not plain text. ✅ milestone 4.
5. **CRUD:** admin-only create/update/delete with `validateSchedule()` errors + JS `confirm()` on delete; users see 403. ✅ milestone 5.
6. **Truck status:** `todayCard()` messaging (on its way / at location) + *"Your truck status today"* on the dashboard. ✅ milestone 6.

> Vibe-coding rule: after every AI-generated chunk, **run it** and **re-read it**. If you can't explain a function in one sentence, ask the AI to explain or simplify it before moving on.

## Step 4 — Test everything (30 min)

Run `java Main.java` and check this list in your browser:

- [ ] `/` loads on your phone screen size (resize the browser narrow) with image + Register/Login buttons
- [ ] Register a new user with your own city → redirected to dashboard showing `📍 YourCity`
- [ ] Logout → `/dashboard` redirects to `/login`
- [ ] Login with wrong password → email stays in the box with "Incorrect password"; forgot-password reset works (min. 8 chars); logout shows "Thank you for your cooperation"
- [ ] As regular user: `/schedules` shows your city by default; no edit/delete buttons; opening `/admin` shows 403
- [ ] As admin (`admin@ecocollect.ph / admin123`): add a schedule → appears everywhere; edit it; delete it (confirm dialog appears first)
- [ ] Dashboard shows "Your truck status today" with the right message when a schedule matches today's weekday
- [ ] Restart the app (`Ctrl+C`, run again) → users + schedules still there
- [ ] `data.json` contains `31000:...` hashes, never plain passwords

## Step 5 — Write the README (20 min)

`README.md` is already drafted — fill in YOUR repo URL, keep the test accounts, and honestly list the AI tools you used. Graders run only the README steps, so re-test them in a fresh terminal:

```
git clone <url> → cd ecocollect → java Main.java → http://localhost:8080
```

## Step 6 — Version control & deploy (30 min)

```bash
git init
git add Main.java README.md BUILDING_GUIDE.md run.bat run.sh .gitignore
git commit -m "Ecollect: landing page, auth with city badge, admin CRUD, live truck status"
# create an empty repo on github.com, then:
git remote add origin https://github.com/YOURNAME/ecocollect.git
git branch -M main
git push -u origin main
```

**Never commit** real passwords or secrets — `data.json` holds demo credentials only, and even those are hashes.

Free deployment options (pick one; the app is a plain Java process):
- **Render.com:** New → Web Service → runtime Docker, Dockerfile below, start command `java Main.java`. Set the port Render gives you via `$PORT` (run `java Main.java $PORT`).
- **Railway / Fly.io:** same idea — any host that runs a command and exposes a port works.
- **Class demo:** running `java Main.java` on your laptop and screen-sharing is acceptable if the class allows it.

Minimal `Dockerfile` (optional):
```dockerfile
FROM eclipse-temurin:21-jdk
WORKDIR /app
COPY Main.java .
# (add hero.png / hero.jpg to the COPY line if the file is in your repo)
EXPOSE 8080
CMD ["sh", "-c", "java Main.java ${PORT:-8080}"]
```

## Step 7 — Submit (5 min)

Submit **only the GitHub repository link** where asked. Final checklist:

- [ ] Idea claimed & unique (no duplicates with classmates)
- [ ] Landing page with image + working call to action, mobile-friendly
- [ ] Register/login/logout working; passwords hashed; protected pages redirect
- [ ] Admin + regular user roles; only admin can change/fix schedules
- [ ] Full CRUD on schedules; validation; delete confirmation; data persists
- [ ] City name shown after login/register; truck status (on its way / at location) shown on scheduled day
- [ ] README complete (description, problem, features, stack, setup, AI disclosure, test accounts)
- [ ] No secrets committed; repo link submitted

## If something breaks

| Symptom | Fix |
|---|---|
| `java: command not found` | Install JDK 21, reopen terminal |
| `Address already in use` | Another app uses 8080 → run `java Main.java 9090` |
| `data.json` corrupted | Stop app, delete `data.json`, restart (re-seeds demo data) |
| Logged out after restart | Normal — sessions are in-memory, but users + schedules persist |
| Can't explain code in defense | Re-read `Main.java` section comments; ask AI "explain function X in 3 sentences" |

Good luck — keep the scope small, commit often, and demo with confidence! ♻️
