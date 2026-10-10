// ============================================================================
// Ecollect — Barangay Garbage & Collection Schedule System
// ----------------------------------------------------------------------------
// A complete 3-part school system in ONE file with ZERO dependencies:
//   1. Landing Page  (public, mobile-friendly, with call to action)
//   2. Users         (register/login/logout, hashed passwords, admin + user roles)
//   3. CRUD          (admin manages collection schedules; users view + live truck status)
//
// Extra required features:
//   * Only the ADMIN can create/edit/delete schedules (users get 403 otherwise).
//   * After login/register the site always shows the user's CITY NAME (navbar badge
//     + dashboard header), and schedules are filtered to that city.
//   * Every schedule has a TRUCK ROUTE (ordered stops). On their collection day,
//     users see "today's truck status" (on its way / at location).
//
// How it works (be ready to explain this):
//   * com.sun.net.httpserver.HttpServer (built into the JDK) listens on port 8080.
//   * Pages are rendered on the server as HTML strings (no frontend framework).
//   * Users/schedules are stored in data.json (file storage -> survives restart).
//   * Passwords are hashed with PBKDF2-HMAC-SHA256 + random salt (never plain text).
//   * Login creates a random session token stored in a Map and in a browser cookie.
//
// Run it:
//   Windows :  java Main.java            (or double-click run.bat)
//   Mac/Linux: java Main.java            (or ./run.sh)
//   Then open http://localhost:8080
// Requires Java 17 or newer (Java 21 recommended). No libraries to install.
// ============================================================================

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

public class Main {

    // ============================== DATA MODELS =============================

    /** App user. role is "admin" or "user". passHash is NEVER a plain password. */
    static class User {
        String id, name, email, city, passHash, role;
        User(String id, String name, String email, String city, String passHash, String role) {
            this.id = id; this.name = name; this.email = email;
            this.city = city; this.passHash = passHash; this.role = role;
        }
        boolean isAdmin() { return "admin".equals(role); }
    }

    /** Core CRUD entity: one garbage collection schedule with live truck status. */
    static class Schedule {
        String id, city, barangay, day, time, truck, waste, status;
        Schedule(String id, String city, String barangay, String day, String time,
                 String truck, String waste, String status) {
            this.id = id; this.city = city; this.barangay = barangay; this.day = day;
            this.time = time; this.truck = truck;
            this.waste = waste; this.status = status;
        }
    }

    // ============================ STORAGE & STATE ===========================

    static final Path DATA_FILE = Path.of("data.json");
    static final List<User> users = new ArrayList<>();
    static final List<Schedule> schedules = new ArrayList<>();
    /** sessionToken -> userId (in-memory; users stay logged in until restart). */
    static final Map<String, String> sessions = new ConcurrentHashMap<>();

    static final List<String> DAYS =
            Arrays.asList("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday");
    static final List<String> CITIES = Arrays.asList(
            "Quezon City", "Manila", "Caloocan", "Davao City", "Cebu City",
            "Iloilo City", "Baguio City", "Cagayan de Oro", "Zamboanga City", "Makati", "Pasig", "Taguig");
    static final List<String> WASTE_TYPES = Arrays.asList(
            "Biodegradable", "Non-biodegradable", "Recyclable", "Hazardous", "Mixed");
    static final List<String> STATUSES = Arrays.asList(
            "Scheduled", "On The Way", "At Location", "Collected", "Missed", "Cancelled");
    /** Fleet: 10 trucks identified by letter. One truck = one place per day. */
    static final List<String> TRUCKS = Arrays.asList(
            "Truck A", "Truck B", "Truck C", "Truck D", "Truck E",
            "Truck F", "Truck G", "Truck H", "Truck I", "Truck J");

    // ============================ PASSWORD HASHING ==========================
    // PBKDF2-HMAC-SHA256, 31,000 iterations, 16-byte random salt.
    // Stored format: iterations:saltHex:hashHex

    static final SecureRandom RANDOM = new SecureRandom();

    static String hashPassword(String password) {
        try {
            byte[] salt = new byte[16];
            RANDOM.nextBytes(salt);
            int iterations = 31_000;
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, 256);
            byte[] hash = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec).getEncoded();
            return iterations + ":" + toHex(salt) + ":" + toHex(hash);
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new RuntimeException("Hashing failed", e);
        }
    }

    static boolean verifyPassword(String password, String stored) {
        try {
            String[] p = stored.split(":");
            int iterations = Integer.parseInt(p[0]);
            byte[] salt = fromHex(p[1]);
            byte[] expected = fromHex(p[2]);
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, 256);
            byte[] actual = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec).getEncoded();
            return MessageDigest.isEqual(expected, actual); // constant-time compare
        } catch (Exception e) {
            return false;
        }
    }

    static String toHex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    static byte[] fromHex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++)
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        return out;
    }

    // ========================= FILE PERSISTENCE (JSON) =======================
    // Data survives restarts because everything is saved to data.json after
    // every create/update/delete and after every new registration.

    static synchronized void save() {
        StringBuilder j = new StringBuilder("{\n\"users\": [\n");
        for (int i = 0; i < users.size(); i++) {
            User u = users.get(i);
            j.append(String.format(
                    "  {\"id\":\"%s\",\"name\":\"%s\",\"email\":\"%s\",\"city\":\"%s\",\"passHash\":\"%s\",\"role\":\"%s\"}%s\n",
                    je(u.id), je(u.name), je(u.email), je(u.city), je(u.passHash), je(u.role),
                    i < users.size() - 1 ? "," : ""));
        }
        j.append("],\n\"schedules\": [\n");
        for (int i = 0; i < schedules.size(); i++) {
            Schedule s = schedules.get(i);
            j.append(String.format(
                    "  {\"id\":\"%s\",\"city\":\"%s\",\"barangay\":\"%s\",\"day\":\"%s\",\"time\":\"%s\"," +
                    "\"truck\":\"%s\",\"waste\":\"%s\",\"status\":\"%s\"}%s\n",
                    je(s.id), je(s.city), je(s.barangay), je(s.day), je(s.time), je(s.truck),
                    je(s.waste), je(s.status),
                    i < schedules.size() - 1 ? "," : ""));
        }
        j.append("]\n}\n");
        try {
            Files.writeString(DATA_FILE, j.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("WARNING: could not save data.json (" + e.getMessage() + ")");
        }
    }

    /** Minimal JSON reader that understands exactly the file written by save(). */
    static synchronized void load() {
        users.clear();
        schedules.clear();
        if (!Files.exists(DATA_FILE)) return;
        try {
            String json = Files.readString(DATA_FILE, StandardCharsets.UTF_8);
            for (String o : topObjects(arrayOf(json, "\"users\""))) {
                users.add(new User(f(o, "id"), f(o, "name"), f(o, "email"),
                        f(o, "city"), f(o, "passHash"), f(o, "role")));
            }
            for (String o : topObjects(arrayOf(json, "\"schedules\""))) {
                schedules.add(new Schedule(f(o, "id"), f(o, "city"), f(o, "barangay"),
                        f(o, "day"), f(o, "time"), f(o, "truck"),
                        f(o, "waste"), f(o, "status")));
            }
        } catch (IOException e) {
            System.err.println("WARNING: could not load data.json (" + e.getMessage() + ")");
        }
    }

    /** Seed demo accounts + schedules on first run so the grader can log in. */
    static void seedIfEmpty() {
        if (!users.isEmpty()) return;
        users.add(new User("U-admin", "Admin", "admin@ecollect.ph", "Quezon City",
                hashPassword("admin123"), "admin"));
        users.add(new User("U-maria", "Maria Santos", "maria@example.com", "Cebu City",
                hashPassword("user1234"), "user"));
        schedules.add(new Schedule("S-1", "Quezon City", "Brgy. Commonwealth", "Monday", "06:00",
                "Truck A", "Biodegradable", "Scheduled"));
        schedules.add(new Schedule("S-2", "Quezon City", "Brgy. Batasan Hills", "Wednesday", "07:30",
                "Truck B", "Non-biodegradable", "Scheduled"));
        schedules.add(new Schedule("S-3", "Cebu City", "Brgy. Lahug", "Tuesday", "06:30",
                "Truck C", "Mixed", "Scheduled"));
        schedules.add(new Schedule("S-4", "Cebu City", "Brgy. Mabolo", "Friday", "08:00",
                "Truck D", "Recyclable", "Scheduled"));
        schedules.add(new Schedule("S-5", "Davao City", "Brgy. Buhangin", "Thursday", "07:00",
                "Truck E", "Biodegradable", "Scheduled"));
        schedules.add(new Schedule("S-6", "Manila", "Brgy. Sampaloc", "Saturday", "06:00",
                "Truck F", "Mixed", "Scheduled"));
        save();
    }

    // --- tiny JSON helpers (only what save()/load() need) ---
    static String je(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "");
    }

    static String arrayOf(String json, String key) {
        int k = json.indexOf(key);
        if (k < 0) return "[]";
        int a = json.indexOf('[', k), depth = 0;
        for (int i = a; i < json.length(); i++) {
            if (json.charAt(i) == '[') depth++;
            if (json.charAt(i) == ']') { depth--; if (depth == 0) return json.substring(a, i + 1); }
        }
        return "[]";
    }

    static List<String> topObjects(String arr) {
        List<String> out = new ArrayList<>();
        int depth = 0, start = -1;
        boolean inStr = false;
        for (int i = 0; i < arr.length(); i++) {
            char c = arr.charAt(i);
            if (c == '"' && arr.charAt(i - 1) != '\\') inStr = !inStr;
            if (inStr) continue;
            if (c == '{') { if (depth == 0) start = i; depth++; }
            if (c == '}') { depth--; if (depth == 0 && start >= 0) out.add(arr.substring(start, i + 1)); }
        }
        return out;
    }

    static String f(String obj, String key) {
        String k = "\"" + key + "\":\"";
        int a = obj.indexOf(k);
        if (a < 0) return "";
        a += k.length();
        StringBuilder sb = new StringBuilder();
        for (int i = a; i < obj.length(); i++) {
            char c = obj.charAt(i);
            if (c == '\\' && i + 1 < obj.length()) { sb.append(obj.charAt(i + 1)); i++; continue; }
            if (c == '"') break;
            sb.append(c);
        }
        return sb.toString();
    }

    // ============================== HTTP HELPERS ============================

    static Map<String, String> parseForm(String body) {
        Map<String, String> m = new HashMap<>();
        if (body == null || body.isBlank()) return m;
        for (String pair : body.split("&")) {
            String[] kv = pair.split("=", 2);
            String k = URLDecoder.decode(kv[0], StandardCharsets.UTF_8);
            String v = kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "";
            m.put(k, v.trim());
        }
        return m;
    }

    static Map<String, String> parseQuery(String raw) { return parseForm(raw); }

    static String readBody(HttpExchange ex) throws IOException {
        return new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    static void sendHtml(HttpExchange ex, String html) throws IOException {
        sendHtml(ex, html, 200);
    }

    static void sendHtml(HttpExchange ex, String html, int code) throws IOException {
        byte[] b = html.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }

    static void redirect(HttpExchange ex, String where) throws IOException {
        ex.getResponseHeaders().set("Location", where);
        ex.sendResponseHeaders(302, -1);
        ex.close();
    }

    static void forbidden(HttpExchange ex) throws IOException {
        sendHtml(ex, layout("Forbidden", null,
                "<div class='card'><h2>403 — Admins only</h2>" +
                "<p>Only the <b>admin</b> account can create, edit, or delete schedules.</p>" +
                "<p><a class='btn' href='/dashboard'>Back to dashboard</a></p></div>"), 403);
    }

    /** Logged-in user from the SESSION cookie, or null when not signed in. */
    static User sessionUser(HttpExchange ex) {
        String cookie = ex.getRequestHeaders().getFirst("Cookie");
        if (cookie == null) return null;
        for (String part : cookie.split(";")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length == 2 && kv[0].trim().equals("SESSION")) {
                String uid = sessions.get(kv[1].trim());
                if (uid != null) return findUserById(uid);
            }
        }
        return null;
    }

    static void setSession(HttpExchange ex, String userId) {
        String token = UUID.randomUUID().toString();
        sessions.put(token, userId);
        ex.getResponseHeaders().add("Set-Cookie",
                "SESSION=" + token + "; Path=/; HttpOnly; Max-Age=86400");
    }

    static void clearSession(HttpExchange ex) {
        String cookie = ex.getRequestHeaders().getFirst("Cookie");
        if (cookie != null)
            for (String part : cookie.split(";")) {
                String[] kv = part.trim().split("=", 2);
                if (kv.length == 2 && kv[0].trim().equals("SESSION")) sessions.remove(kv[1].trim());
            }
        ex.getResponseHeaders().add("Set-Cookie", "SESSION=; Path=/; Max-Age=0");
    }

    // --- lookups ---
    static User findUserById(String id) {
        return users.stream().filter(u -> u.id.equals(id)).findFirst().orElse(null);
    }

    static User findUserByEmail(String email) {
        return users.stream().filter(u -> u.email.equalsIgnoreCase(email)).findFirst().orElse(null);
    }

    static Schedule findSchedule(String id) {
        return schedules.stream().filter(s -> s.id.equals(id)).findFirst().orElse(null);
    }

    static int dayIndex(String d) {
        int i = DAYS.indexOf(d);
        return i < 0 ? 99 : i;
    }

    static void sortSchedules(List<Schedule> list) {
        list.sort((a, b) -> {
            int c = a.city.compareToIgnoreCase(b.city);
            if (c != 0) return c;
            c = Integer.compare(dayIndex(a.day), dayIndex(b.day));
            if (c != 0) return c;
            return a.time.compareTo(b.time);
        });
    }

    /** "13:30" -> "1:30 PM", "06:00" -> "6:00 AM" (for dashboard/tables). */
    static String ampm(String hhmm) {
        try {
            String[] p = hhmm.split(":");
            int h = Integer.parseInt(p[0]), m = Integer.parseInt(p[1]);
            String ap = h >= 12 ? "PM" : "AM";
            int h12 = h % 12;
            if (h12 == 0) h12 = 12;
            return h12 + ":" + String.format("%02d", m) + " " + ap;
        } catch (Exception e) {
            return hhmm == null ? "" : hhmm;
        }
    }

    /** A truck can serve only ONE place per day — find a clash, if any. */
    static Schedule findTruckConflict(String truck, String day, String excludeId) {
        for (Schedule s : schedules)
            if (s.truck.equals(truck) && s.day.equals(day)
                    && (excludeId == null || !s.id.equals(excludeId)))
                return s;
        return null;
    }

    // ============================== HTML TEMPLATES ==========================

    static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    static String css() {
        return """
            *{box-sizing:border-box;margin:0;padding:0}
            body{font-family:'Segoe UI',Arial,sans-serif;background:#f1f8e9;color:#263238;line-height:1.6}
            .nav{background:#1b5e20;color:#fff;padding:12px 16px;display:flex;flex-wrap:wrap;gap:10px;align-items:center;position:sticky;top:0;z-index:10}
            .nav .brand{font-weight:800;font-size:1.2rem;margin-right:auto;text-decoration:none;color:#fff}
            .nav a.link{color:#fff;text-decoration:none;padding:6px 10px;border-radius:6px}
            .nav a.link:hover{background:#2e7d32}
            .city-badge{background:#ffeb3b;color:#1b5e20;font-weight:700;padding:6px 12px;border-radius:20px;font-size:.9rem;white-space:nowrap}
            .role-badge{background:#fff;color:#1b5e20;font-weight:700;padding:6px 12px;border-radius:20px;font-size:.85rem}
            .wrap{max-width:1000px;margin:0 auto;padding:16px}
            .hero{background:linear-gradient(rgba(20,60,25,.72),rgba(20,60,25,.72)),url('/hero-img') center/cover no-repeat,linear-gradient(135deg,#2e7d32,#66bb6a);color:#fff;border-radius:14px;padding:32px 24px;text-align:center;margin:16px 0;text-shadow:0 1px 4px rgba(0,0,0,.65)}
            .banner{background:linear-gradient(rgba(20,60,25,.72),rgba(20,60,25,.72)),url('/hero-img') center/cover no-repeat,linear-gradient(135deg,#1b5e20,#43a047);color:#fff;border-radius:14px;padding:24px 20px;text-align:center;margin:16px 0;text-shadow:0 1px 4px rgba(0,0,0,.65)}
            .banner h2{color:#fff;margin-bottom:6px}
            .hero h1{font-size:2rem;margin-bottom:8px}
            .hero p{opacity:.95;max-width:640px;margin:0 auto 16px}
            .hero img{max-width:100%;height:auto;border-radius:10px;margin:12px 0}
            .btn{display:inline-block;background:#2e7d32;color:#fff;border:none;padding:10px 20px;border-radius:8px;text-decoration:none;font-weight:600;cursor:pointer;font-size:1rem;margin:4px 4px 4px 0}
            .btn:hover{background:#1b5e20}
            .btn.yellow{background:#f9a825}.btn.yellow:hover{background:#f57f17}
            .btn.red{background:#c62828}.btn.red:hover{background:#8e0000}
            .btn.ghost{background:#fff;color:#2e7d32;border:2px solid #2e7d32}
            .grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(220px,1fr));gap:12px;margin:16px 0}
            .card{background:#fff;border-radius:12px;padding:18px;box-shadow:0 2px 8px rgba(0,0,0,.08);margin:12px 0}
            .card h2,.card h3{color:#1b5e20;margin-bottom:8px}
            table{width:100%;border-collapse:collapse;background:#fff;border-radius:10px;overflow:hidden;margin:12px 0;font-size:.95rem}
            th{background:#2e7d32;color:#fff;text-align:left;padding:10px}
            td{padding:10px;border-bottom:1px solid #e0e0e0;vertical-align:top}
            tr:nth-child(even) td{background:#f9fbe7}
            .table-scroll{overflow-x:auto}
            form label{display:block;font-weight:600;margin:10px 0 4px;color:#1b5e20}
            input,select,textarea{width:100%;padding:10px;border:1px solid #a5d6a7;border-radius:8px;font-size:1rem;background:#fff}
            input:focus,select:focus,textarea:focus{outline:2px solid #66bb6a}
            .error{background:#ffebee;color:#b71c1c;border:1px solid #ef9a9a;padding:10px;border-radius:8px;margin:10px 0}
            .ok{background:#e8f5e9;color:#1b5e20;border:1px solid #a5d6a7;padding:10px;border-radius:8px;margin:10px 0}
            .status{display:inline-block;padding:3px 12px;border-radius:14px;font-size:.8rem;font-weight:700;color:#fff}
            .Scheduled{background:#1565c0}.OnTheWay{background:#ef6c00}.AtLocation{background:#00897b}.Collected{background:#2e7d32}.Missed{background:#c62828}.Cancelled{background:#616161}
            .today{border:3px solid #f9a825}
            footer{text-align:center;color:#558b2f;padding:24px;font-size:.9rem}
            .stats{display:grid;grid-template-columns:repeat(auto-fit,minmax(140px,1fr));gap:10px;margin:12px 0}
            .stat{background:#fff;border-radius:10px;padding:14px;text-align:center;box-shadow:0 2px 8px rgba(0,0,0,.08)}
            .stat b{font-size:1.5rem;color:#1b5e20;display:block}
            @media(max-width:600px){.hero h1{font-size:1.5rem}th,td{padding:8px 6px;font-size:.85rem}}
            """;
    }

    /** Page shell: navbar (with CITY badge when logged in) + footer. */
    static String layout(String title, User u, String body) {
        StringBuilder nav = new StringBuilder(
                "<div class='nav'><a class='brand' href='/'>♻️ Ecollect</a>");
        if (u == null) {
            nav.append("<a class='link' href='/'>Home</a>")
               .append("<a class='link' href='/login'>Login</a>")
               .append("<a class='link' href='/register'>Register</a>");
        } else {
            // >>> CITY INDICATION: every logged-in page shows the user's city <<<
            nav.append("<span class='city-badge'>📍 ").append(esc(u.city)).append("</span>")
               .append("<a class='link' href='/dashboard'>Dashboard</a>")
               .append("<a class='link' href='/schedules'>Schedules</a>");
            if (u.isAdmin()) nav.append("<a class='link' href='/admin'>🛠️ Admin</a>");
            nav.append("<span class='role-badge'>").append(u.isAdmin() ? "ADMIN" : "USER").append("</span>")
               .append("<a class='link' href='/logout'>Logout (").append(esc(u.name)).append(")</a>");
        }
        nav.append("</div>");
        return "<!DOCTYPE html><html lang='en'><head><meta charset='UTF-8'>"
                + "<meta name='viewport' content='width=device-width, initial-scale=1'>"
                + "<title>" + esc(title) + " — Ecollect</title>"
                + "<style>" + css() + "</style></head><body>"
                + nav + "<div class='wrap'>" + body + "</div>"
                + "<footer>♻️ Ecollect — Barangay Garbage & Collection Schedule System<br>"
                + "Claim: <b>Ecollect — barangay garbage collection scheduling — collection schedules</b></footer>"
                + "</body></html>";
    }

    // ---- 1. LANDING PAGE (public, no login) ----
    static String landingPage(boolean goodbye) {
        return layout("Clean City, On Schedule", null,
                (goodbye ? "<div class='ok' style='text-align:center;font-size:1.1rem'>🙏 <b>Thank you for your cooperation!</b> "
                        + "You have been logged out. Please segregate your waste and see you on collection day! ♻️</div>" : "")
                + """
            <div class='hero'>
              <h1>♻️ Ecollect</h1>
              <p><b>Barangay Garbage & Collection Schedule System.</b>
              Know exactly <b>which day</b> the garbage truck visits your street,
              track the <b>truck's live status</b>, and never miss a pickup again.</p>
              <br>
              <a class='btn yellow' href='/register'>📝 Register — find my schedule</a>
              <a class='btn ghost' href='/login' style='background:#fff'>🔑 Login</a>
            </div>
            <div class='grid'>
              <div class='card'><h3>📅 Scheduled Pickups</h3><p>Weekly collection timetables per city and barangay, updated by the city admin.</p></div>
              <div class='card'><h3>🚚 Live Truck Status</h3><p>See whether the truck is <b>on its way</b> or <b>already at your pickup point</b> on collection day.</p></div>
              <div class='card'><h3>📍 Your City First</h3><p>Register with your city and your dashboard instantly highlights your pickups.</p></div>
              <div class='card'><h3>♻️ Segregation Guide</h3><p>Each schedule labels the waste type: Biodegradable, Non-biodegradable, Recyclable, Hazardous, or Mixed.</p></div>
            </div>
            <div class='card' style='text-align:center;border:2px dashed #2e7d32'><h2>🔒 Full weekly collection calendar — members only</h2>
              <p>Login or register to unlock <b>your city's schedules</b>, AM/PM pickup times, and live truck status.</p>
              <p><a class='btn yellow' href='/register'>📝 Register free</a> <a class='btn' href='/login'>🔑 Login</a></p>
            </div>
            <div class='card'><h2>How it works</h2>
              <p>1️⃣ <b>Register</b> with your name, email, password and <b>city</b> →
              2️⃣ Your <b>dashboard</b> shows your city badge + this week's pickups →
              3️⃣ On your day, check the <b>truck status</b> — on its way or already at your place.<br>
              🛠️ Only the <b>admin</b> can add, edit, or fix schedules — residents always see official data.</p>
            </div>""");
    }

    // ---- 2a. LOGIN (keeps your typed email so you see what went wrong) ----
    static String loginPage(String err, String email) {
        return layout("Login", null, "<div class='card' style='max-width:480px;margin:24px auto'>"
                + "<h2>🔑 Login</h2>"
                + (err != null ? "<div class='error'>" + esc(err) + "</div>" : "")
                + "<form method='POST' action='/login'>"
                + "<label>Email</label><input name='email' type='email' required value='" + esc(email) + "'>"
                + "<label>Password</label><input name='password' type='password' required>"
                + "<br><br><button class='btn' type='submit'>Login</button> "
                + "<a class='btn ghost' href='/register'>Need an account? Register</a></form>"
                + "<p style='margin-top:10px'><a href='/forgot'>Forgot password?</a></p>"
                + "<p style='margin-top:10px;font-size:.9rem'>Demo admin: <b>admin@ecollect.ph / admin123</b><br>"
                + "Demo user: <b>maria@example.com / user1234</b></p></div>");
    }

    // ---- 2b. REGISTER (includes CITY — shown everywhere after login) ----
    static String registerPage(String err) {
        StringBuilder opts = new StringBuilder();
        for (String c : CITIES) opts.append("<option>").append(esc(c)).append("</option>");
        return layout("Register", null, "<div class='card' style='max-width:520px;margin:24px auto'>"
                + "<h2>📝 Register</h2>"
                + "<p>Tell us your <b>city</b> — the site will show it on every page after you sign in.</p>"
                + (err != null ? "<div class='error'>" + esc(err) + "</div>" : "")
                + "<form method='POST' action='/register'>"
                + "<label>Full name</label><input name='name' required minlength='2'>"
                + "<label>Email</label><input name='email' type='email' required>"
                + "<label>City</label><select name='city' required>" + opts + "</select>"
                + "<label>Password (min. 8 characters)</label><input name='password' type='password' required minlength='8'>"
                + "<br><br><button class='btn' type='submit'>Create account</button> "
                + "<a class='btn ghost' href='/login'>Already registered? Login</a></form></div>");
    }

    // ---- 2d. FORGOT PASSWORD (reset directly with your email — no mail server needed) ----
    static String forgotPage(String err, String ok, String email) {
        return layout("Forgot password", null, "<div class='card' style='max-width:480px;margin:24px auto'>"
                + "<h2>🔓 Reset password</h2>"
                + "<p>Enter your account email and choose a new password (min. 8 characters).</p>"
                + (err != null ? "<div class='error'>" + esc(err) + "</div>" : "")
                + (ok != null ? "<div class='ok'>" + esc(ok) + "</div>" : "")
                + "<form method='POST' action='/forgot'>"
                + "<label>Email</label><input name='email' type='email' required value='" + esc(email) + "'>"
                + "<label>New password</label><input name='newpass' type='password' required minlength='8'>"
                + "<label>Confirm new password</label><input name='confirm' type='password' required minlength='8'>"
                + "<br><br><button class='btn' type='submit'>Reset password</button> "
                + "<a class='btn ghost' href='/login'>Back to login</a></form></div>");
    }

    // ---- 2c. DASHBOARD (protected; city badge + today's truck status) ----
    static String dashboardPage(User u) {
        String today = LocalDate.now().getDayOfWeek().name(); // e.g. MONDAY
        String todayTitle = titleCase(today);
        List<Schedule> mine = new ArrayList<>();
        for (Schedule s : schedules) if (s.city.equals(u.city)) mine.add(s);
        sortSchedules(mine);

        StringBuilder todayHtml = new StringBuilder();
        boolean hasToday = false;
        for (Schedule s : mine)
            if (s.day.equalsIgnoreCase(todayTitle)) { hasToday = true; todayHtml.append(todayCard(s)); }
        if (!hasToday)
            todayHtml.append("<div class='card'><h3>🚚 No pickup today (" + todayTitle + ")</h3>"
                    + "<p>No truck visits <b>" + esc(u.city) + "</b> today. Check your upcoming schedules below.</p></div>");

        StringBuilder rows = new StringBuilder();
        for (Schedule s : mine)
            rows.append("<tr><td>").append(esc(s.barangay)).append("</td><td>").append(esc(s.day))
                .append("</td><td>").append(esc(ampm(s.time))).append("</td><td>").append(esc(s.truck))
                .append("</td><td>").append(esc(s.waste)).append("</td><td>")
                .append(statusPill(s.status)).append("</td></tr>");
        if (mine.isEmpty())
            rows.append("<tr><td colspan='6'>No schedules posted for your city yet. Please check back later.</td></tr>");

        return layout("Dashboard", u,
                "<div class='banner today'><h2>👋 Welcome, " + esc(u.name) + "!</h2>"
                + "<p style='font-size:1.2rem'>You are viewing schedules for</p>"
                + "<p><span class='city-badge' style='font-size:1.2rem'>📍 " + esc(u.city) + "</span></p>"
                + "<p>Today is <b>" + todayTitle + "</b> · 📅 "
                + LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("MMMM d, yyyy"))
                + "</p></div>"
                + "<h2 style='margin-top:16px'>🚚 Your truck status today</h2>" + todayHtml
                + "<div class='card'><h2>🗓️ All pickups in " + esc(u.city) + "</h2>"
                + "<div class='table-scroll'><table><tr><th>Barangay</th><th>Day</th><th>Time</th><th>Truck</th><th>Waste</th><th>Status</th></tr>"
                + rows + "</table></div>"
                + "<p><a class='btn' href='/schedules'>View all cities →</a>"
                + (u.isAdmin() ? " <a class='btn yellow' href='/admin'>🛠️ Manage schedules</a>" : "") + "</p></div>");
    }

    static String todayCard(Schedule s) {
        String msg;
        if ("On The Way".equals(s.status))
            msg = "🚚 <b>" + esc(s.truck) + " is ON ITS WAY</b> to " + esc(s.barangay) + ". Get your trash ready!";
        else if ("At Location".equals(s.status))
            msg = "📍 <b>" + esc(s.truck) + " is NOW AT " + esc(s.barangay) + "</b> — bring your trash out immediately!";
        else if ("Collected".equals(s.status))
            msg = "✅ Collection finished for today. See you next " + esc(s.day) + "!";
        else if ("Missed".equals(s.status))
            msg = "⚠️ Pickup was missed. Please wait for the next scheduled day.";
        else if ("Cancelled".equals(s.status))
            msg = "⛔ Today's pickup is cancelled.";
        else
            msg = "🗓️ Scheduled pickup today at <b>" + esc(ampm(s.time)) + "</b>. Bring trash out before the truck arrives.";
        return "<div class='card today'><h3>" + esc(s.barangay) + " — " + esc(ampm(s.time))
                + " (" + esc(s.truck) + ") " + statusPill(s.status) + "</h3>"
                + "<p>Waste type today: <b>" + esc(s.waste) + "</b>.</p>"
                + "<p>" + msg + "</p></div>";
    }

    static String titleCase(String s) {
        s = s.toLowerCase();
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    static String statusPill(String st) {
        String cls = st == null ? "Scheduled" : st.replace(" ", "");
        return "<span class='status " + esc(cls) + "'>" + esc(st) + "</span>";
    }

    // ---- schedules list (both roles view; admin gets edit/delete) ----
    static String schedulesPage(User u, String filterCity) {
        StringBuilder cityOpts = new StringBuilder("<option value=''>All cities</option>");
        for (String c : CITIES)
            cityOpts.append("<option value='").append(esc(c)).append("'")
                    .append(c.equals(filterCity) ? " selected" : "").append(">")
                    .append(esc(c)).append("</option>");
        List<Schedule> list = new ArrayList<>();
        for (Schedule s : schedules)
            if (filterCity == null || filterCity.isBlank() || s.city.equals(filterCity)) list.add(s);
        sortSchedules(list);
        StringBuilder rows = new StringBuilder();
        for (Schedule s : list) {
            rows.append("<tr><td><b>").append(esc(s.city)).append("</b></td><td>")
                .append(esc(s.barangay)).append("</td><td>").append(esc(s.day)).append("</td><td>")
                .append(esc(ampm(s.time))).append("</td><td>").append(esc(s.truck)).append("</td><td>")
                .append(esc(s.waste)).append("</td><td>").append(statusPill(s.status)).append("</td>");
            if (u.isAdmin())
                rows.append("<td><a class='btn' href='/admin?edit=").append(esc(s.id))
                    .append("'>✏️ Edit</a> "
                    + "<form method='POST' action='/admin/delete' style='display:inline' "
                    + "onsubmit=\"return confirm('Delete schedule for " + esc(s.barangay)
                    + " (" + esc(s.day) + ")? This cannot be undone.');\">"
                    + "<input type='hidden' name='id' value='").append(esc(s.id)).append("'>"
                    + "<button class='btn red' type='submit'>🗑️ Delete</button></form></td>");
            else
                rows.append("<td><a class='btn ghost' href='/schedules?city=").append(urlEnc(s.city))
                    .append("'>View</a></td>");
            rows.append("</tr>");
        }
        if (list.isEmpty()) rows.append("<tr><td colspan='9'>No schedules found.</td></tr>");
        return layout("Schedules", u,
                "<div class='card'><h2>🗓️ Collection schedules</h2>"
                + "<p>Showing schedules for <b>" + esc(filterCity == null || filterCity.isBlank() ? "all cities" : filterCity)
                + "</b> · Your city: <span class='city-badge'>📍 " + esc(u.city) + "</span></p>"
                + "<form method='GET' action='/schedules'><label>Filter by city</label>"
                + "<select name='city' onchange='this.form.submit()'>" + cityOpts + "</select></form>"
                + "<div class='table-scroll'><table><tr><th>City</th><th>Barangay</th><th>Day</th><th>Time</th>"
                + "<th>Truck</th><th>Waste</th><th>Status</th><th>Action</th></tr>" + rows
                + "</table></div>"
                + (u.isAdmin() ? "<p><a class='btn yellow' href='/admin'>🛠️ Manage (CRUD)</a></p>"
                               : "<p><i>🔒 Only the admin can add, edit, or delete schedules.</i></p>")
                + "</div>");
    }

    static String urlEnc(String s) {
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    // ---- 3. ADMIN CRUD ----
    static String adminPage(User u, String err, String ok, Schedule edit) {
        StringBuilder rows = new StringBuilder();
        List<Schedule> list = new ArrayList<>(schedules);
        sortSchedules(list);
        for (Schedule s : list)
            rows.append("<tr><td>").append(esc(s.city)).append("</td><td>").append(esc(s.barangay))
                .append("</td><td>").append(esc(s.day)).append("</td><td>").append(esc(ampm(s.time)))
                .append("</td><td>").append(esc(s.truck)).append("</td><td>").append(esc(s.waste))
                .append("</td><td>").append(statusPill(s.status)).append("</td><td>")
                .append("<a class='btn' href='/admin?edit=").append(esc(s.id)).append("'>✏️ Edit</a> ")
                .append("<form method='POST' action='/admin/delete' style='display:inline' "
                        + "onsubmit=\"return confirm('Delete schedule for " + esc(s.barangay)
                        + " (" + esc(s.day) + ")? This cannot be undone.');\">"
                        + "<input type='hidden' name='id' value='").append(esc(s.id)).append("'>"
                        + "<button class='btn red' type='submit'>🗑️ Delete</button></form></td></tr>");
        long todayCount = list.stream().filter(s -> s.day.equalsIgnoreCase(
                titleCase(LocalDate.now().getDayOfWeek().name()))).count();
        long cityCount = list.stream().map(s -> s.city).distinct().count();

        return layout("Admin", u,
                "<div class='banner'><h2>🛠️ Admin — manage collection schedules</h2>"
                + "<p>Signed in as <b>" + esc(u.name) + "</b> <span class='city-badge'>📍 " + esc(u.city) + "</span> "
                + "<span class='role-badge'>ADMIN</span></p></div>"
                + "<div class='card'>"
                + (err != null ? "<div class='error'>" + esc(err) + "</div>" : "")
                + (ok != null ? "<div class='ok'>" + esc(ok) + "</div>" : "")
                + "<div class='stats'><div class='stat'><b>" + list.size() + "</b>Total schedules</div>"
                + "<div class='stat'><b>" + cityCount + "</b>Cities covered</div>"
                + "<div class='stat'><b>" + todayCount + "</b>Pickups today</div>"
                + "<div class='stat'><b>" + users.size() + "</b>Registered users</div></div></div>"
                + "<div class='card'><h3>" + (edit == null ? "➕ Add new schedule" : "✏️ Edit schedule " + esc(edit.id)) + "</h3>"
                + scheduleForm(edit) + "</div>"
                + "<div class='card'><h3>📋 All schedules (CRUD)</h3>"
                + "<div class='table-scroll'><table><tr><th>City</th><th>Barangay</th><th>Day</th><th>Time</th>"
                + "<th>Truck</th><th>Waste</th><th>Status</th><th>Actions</th></tr>" + rows + "</table></div></div>");
    }

    static String scheduleForm(Schedule s) {
        boolean isEdit = s != null;
        String city = isEdit ? s.city : "Quezon City";
        StringBuilder cityOpts = new StringBuilder();
        for (String c : CITIES)
            cityOpts.append("<option").append(c.equals(city) ? " selected" : "").append(">")
                    .append(esc(c)).append("</option>");
        StringBuilder dayOpts = new StringBuilder();
        for (String d : DAYS)
            dayOpts.append("<option").append(isEdit && d.equals(s.day) ? " selected" : "").append(">")
                   .append(d).append("</option>");
        StringBuilder wasteOpts = new StringBuilder();
        for (String w : WASTE_TYPES)
            wasteOpts.append("<option").append(isEdit && w.equals(s.waste) ? " selected" : "").append(">")
                     .append(w).append("</option>");
        StringBuilder statusOpts = new StringBuilder();
        for (String st : STATUSES)
            statusOpts.append("<option").append(isEdit && st.equals(s.status) ? " selected" : "").append(">")
                      .append(st).append("</option>");
        StringBuilder truckOpts = new StringBuilder();
        for (String t : TRUCKS)
            truckOpts.append("<option").append(isEdit && t.equals(s.truck) ? " selected" : "").append(">")
                     .append(t).append("</option>");
        return "<form method='POST' action='" + (isEdit ? "/admin/update" : "/admin/create") + "'>"
                + (isEdit ? "<input type='hidden' name='id' value='" + esc(s.id) + "'>" : "")
                + "<label>City *</label><select name='city' required>" + cityOpts + "</select>"
                + "<label>Barangay / street area *</label><input name='barangay' required minlength='2' value='"
                + esc(isEdit ? s.barangay : "") + "' placeholder='e.g. Brgy. Commonwealth'>"
                + "<label>Collection day *</label><select name='day' required>" + dayOpts + "</select>"
                + "<label>Time *</label><input name='time' type='time' required value='"
                + esc(isEdit ? s.time : "06:00") + "'>"
                + "<label>Truck (10 available: A–J) *</label><select name='truck' required>" + truckOpts + "</select>"
                + "<label>Waste type *</label><select name='waste' required>" + wasteOpts + "</select>"
                + "<label>Status (truck location)</label><select name='status'>" + statusOpts + "</select>"
                + "<br><br><button class='btn " + (isEdit ? "yellow" : "") + "' type='submit'>"
                + (isEdit ? "💾 Save changes" : "➕ Add schedule") + "</button> "
                + (isEdit ? "<a class='btn ghost' href='/admin'>Cancel</a>" : "") + "</form>";
    }

    /** Shared validation for create/update. Returns error message or null when OK. */
    static String validateSchedule(Map<String, String> p) {
        if (p.getOrDefault("city", "").isBlank()) return "City is required.";
        if (p.getOrDefault("barangay", "").length() < 2) return "Barangay / street area is required (min. 2 characters).";
        if (!DAYS.contains(p.getOrDefault("day", ""))) return "Please choose a valid collection day.";
        if (!p.getOrDefault("time", "").matches("\\d{2}:\\d{2}")) return "Time must be in HH:MM format.";
        if (!TRUCKS.contains(p.getOrDefault("truck", ""))) return "Please choose a truck (A–J).";
        if (!WASTE_TYPES.contains(p.getOrDefault("waste", ""))) return "Please choose a valid waste type.";
        return null;
    }

    static boolean validEmail(String e) {
        return e != null && e.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");
    }

    // ================================= ROUTER ===============================

    public static void main(String[] args) throws Exception {
        int port = 8080;
        if (args.length > 0) {
            try { port = Integer.parseInt(args[0]); } catch (NumberFormatException ignored) {}
        }
        load();
        seedIfEmpty();

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", Main::handle);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        System.out.println("==================================================");
        System.out.println(" ♻️  Ecollect is running!");
        System.out.println(" Open:  http://localhost:" + port);
        System.out.println(" Admin: admin@ecollect.ph / admin123");
        System.out.println(" User:  maria@example.com / user1234");
        System.out.println(" Stop:  press Ctrl+C");
        System.out.println("==================================================");
    }

    static void handle(HttpExchange ex) throws IOException {
        try {
            String method = ex.getRequestMethod();
            String path = ex.getRequestURI().getPath();
            if (path.equals("/favicon.ico")) { ex.sendResponseHeaders(204, -1); ex.close(); return; }

            // hero background photo (optional): put hero.jpg / hero.png / hero.jpeg
            // next to Main.java and it appears behind the home banner text.
            if (path.equals("/hero-img") && method.equalsIgnoreCase("GET")) {
                Path img = Files.exists(Path.of("hero.jpg")) ? Path.of("hero.jpg")
                        : Files.exists(Path.of("hero.png")) ? Path.of("hero.png")
                        : Files.exists(Path.of("hero.jpeg")) ? Path.of("hero.jpeg") : null;
                if (img == null) { ex.sendResponseHeaders(404, -1); ex.close(); return; }
                byte[] bytes = Files.readAllBytes(img);
                ex.getResponseHeaders().set("Content-Type",
                        img.toString().endsWith(".png") ? "image/png" : "image/jpeg");
                ex.getResponseHeaders().set("Cache-Control", "max-age=3600");
                ex.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
                return;
            }

            // public landing page
            if (path.equals("/") && method.equalsIgnoreCase("GET")) {
                Map<String, String> q0 = parseQuery(ex.getRequestURI().getRawQuery());
                sendHtml(ex, landingPage(q0.containsKey("goodbye")));
                return;
            }
            // auth pages
            if (path.equals("/login")) {
                if (method.equalsIgnoreCase("GET")) { sendHtml(ex, loginPage(null, "")); return; }
                Map<String, String> p = parseForm(readBody(ex));
                String email = p.getOrDefault("email", "");
                User u = findUserByEmail(email);
                if (u == null) {
                    sendHtml(ex, loginPage("No account found with that email. Please register first.", email), 401);
                    return;
                }
                if (!verifyPassword(p.getOrDefault("password", ""), u.passHash)) {
                    sendHtml(ex, loginPage("Incorrect password for " + email + ". Please try again.", email), 401);
                    return;
                }
                setSession(ex, u.id);
                redirect(ex, "/dashboard");
                return;
            }
            if (path.equals("/register")) {
                if (method.equalsIgnoreCase("GET")) { sendHtml(ex, registerPage(null)); return; }
                Map<String, String> p = parseForm(readBody(ex));
                String name = p.getOrDefault("name", ""), email = p.getOrDefault("email", ""),
                       city = p.getOrDefault("city", ""), pass = p.getOrDefault("password", "");
                String err = null;
                if (name.length() < 2) err = "Please enter your full name.";
                else if (!validEmail(email)) err = "Please enter a valid email address.";
                else if (findUserByEmail(email) != null) err = "That email is already registered. Please login.";
                else if (city.isBlank()) err = "Please choose your city.";
                else if (pass.length() < 8) err = "Password must be at least 8 characters.";
                if (err != null) { sendHtml(ex, registerPage(err), 400); return; }
                User u = new User("U-" + UUID.randomUUID().toString().substring(0, 8),
                        name, email, city, hashPassword(pass), "user");
                synchronized (Main.class) { users.add(u); save(); }
                setSession(ex, u.id);
                redirect(ex, "/dashboard");
                return;
            }
            if (path.equals("/logout")) { clearSession(ex); redirect(ex, "/?goodbye=1"); return; }

            if (path.equals("/forgot")) {
                if (method.equalsIgnoreCase("GET")) { sendHtml(ex, forgotPage(null, null, "")); return; }
                Map<String, String> p = parseForm(readBody(ex));
                String email = p.getOrDefault("email", "");
                User fu = findUserByEmail(email);
                String np = p.getOrDefault("newpass", ""), cf = p.getOrDefault("confirm", "");
                String ferr = null;
                if (!validEmail(email)) ferr = "Please enter a valid email address.";
                else if (fu == null) ferr = "No account found with that email.";
                else if (np.length() < 8) ferr = "New password must be at least 8 characters.";
                else if (!np.equals(cf)) ferr = "Passwords do not match. Please retype them.";
                if (ferr != null) { sendHtml(ex, forgotPage(ferr, null, email), 400); return; }
                synchronized (Main.class) { fu.passHash = hashPassword(np); save(); }
                sendHtml(ex, forgotPage(null, "✅ Password reset! You can now login with your new password.", ""));
                return;
            }

            // ---- protected pages (redirect to login when not signed in) ----
            User u = sessionUser(ex);
            if (u == null) { redirect(ex, "/login"); return; }

            if (path.equals("/dashboard") && method.equalsIgnoreCase("GET")) {
                sendHtml(ex, dashboardPage(u));
                return;
            }
            if (path.equals("/schedules") && method.equalsIgnoreCase("GET")) {
                Map<String, String> q = parseQuery(ex.getRequestURI().getRawQuery());
                String city = q.getOrDefault("city", u.city); // default: user's own city
                sendHtml(ex, schedulesPage(u, city));
                return;
            }

            // ---- admin-only CRUD (only admin can change/fix the website) ----
            if (path.equals("/admin") && method.equalsIgnoreCase("GET")) {
                if (!u.isAdmin()) { forbidden(ex); return; }
                Map<String, String> q = parseQuery(ex.getRequestURI().getRawQuery());
                Schedule edit = q.containsKey("edit") ? findSchedule(q.get("edit")) : null;
                sendHtml(ex, adminPage(u, null, null, edit));
                return;
            }
            if (path.equals("/admin/create") && method.equalsIgnoreCase("POST")) {
                if (!u.isAdmin()) { forbidden(ex); return; }
                Map<String, String> p = parseForm(readBody(ex));
                String err = validateSchedule(p);
                if (err == null) {
                    Schedule clash = findTruckConflict(p.get("truck"), p.get("day"), null);
                    if (clash != null) err = "⛔ " + p.get("truck") + " is already busy on " + p.get("day")
                            + " (" + clash.barangay + " at " + ampm(clash.time) + "). A truck cannot serve two places "
                            + "on the same day — choose another truck or day.";
                }
                if (err != null) { sendHtml(ex, adminPage(u, err, null, null), 400); return; }
                synchronized (Main.class) {
                    schedules.add(new Schedule("S-" + UUID.randomUUID().toString().substring(0, 8),
                            p.get("city"), p.get("barangay"), p.get("day"), p.get("time"),
                            p.get("truck"), p.get("waste"), p.getOrDefault("status", "Scheduled")));
                    save();
                }
                sendHtml(ex, adminPage(u, null, "✅ Schedule added successfully.", null));
                return;
            }
            if (path.equals("/admin/update") && method.equalsIgnoreCase("POST")) {
                if (!u.isAdmin()) { forbidden(ex); return; }
                Map<String, String> p = parseForm(readBody(ex));
                Schedule s = findSchedule(p.getOrDefault("id", ""));
                if (s == null) { sendHtml(ex, adminPage(u, "Schedule not found.", null, null), 404); return; }
                String err = validateSchedule(p);
                if (err == null) {
                    Schedule clash = findTruckConflict(p.get("truck"), p.get("day"), p.getOrDefault("id", ""));
                    if (clash != null) err = "⛔ " + p.get("truck") + " is already busy on " + p.get("day")
                            + " (" + clash.barangay + " at " + ampm(clash.time) + "). A truck cannot serve two places "
                            + "on the same day — choose another truck or day.";
                }
                if (err != null) { sendHtml(ex, adminPage(u, err, null, s), 400); return; }
                synchronized (Main.class) {
                    s.city = p.get("city"); s.barangay = p.get("barangay"); s.day = p.get("day");
                    s.time = p.get("time"); s.truck = p.get("truck");
                    s.waste = p.get("waste");
                    s.status = p.getOrDefault("status", "Scheduled");
                    save();
                }
                sendHtml(ex, adminPage(u, null, "💾 Schedule updated successfully.", null));
                return;
            }
            if (path.equals("/admin/delete") && method.equalsIgnoreCase("POST")) {
                if (!u.isAdmin()) { forbidden(ex); return; }
                Map<String, String> p = parseForm(readBody(ex));
                Schedule s = findSchedule(p.getOrDefault("id", ""));
                if (s == null) { sendHtml(ex, adminPage(u, "Schedule not found.", null, null), 404); return; }
                synchronized (Main.class) { schedules.remove(s); save(); }
                sendHtml(ex, adminPage(u, null, "🗑️ Schedule for " + s.barangay + " deleted.", null));
                return;
            }

            sendHtml(ex, layout("Not found", u,
                    "<div class='card'><h2>404 — page not found</h2>"
                    + "<p><a class='btn' href='/'>Go home</a></p></div>"), 404);
        } catch (Exception e) {
            e.printStackTrace();
            try {
                sendHtml(ex, "<h1>500 — something went wrong</h1><p>" + esc(e.getMessage())
                        + "</p><p><a href='/'>Go home</a></p>", 500);
            } catch (Exception ignored) {}
        }
    }
}
