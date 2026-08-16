import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Class Bell — a personal class-schedule reminder web app.
 *
 * Built with plain Java (JDK's built-in com.sun.net.httpserver), no
 * external dependencies.
 *
 * Run with:
 *   javac -d out src/Main.java
 *   java -cp out Main
 * then open http://localhost:8080
 */
public class Main {

    static final Path DATA_FILE = Paths.get("data", "schedule.txt");
    static final Path PUBLIC_DIR = Paths.get("public");
    static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    static final List<Course> courses = Collections.synchronizedList(new ArrayList<>());
    static final AtomicInteger idGen = new AtomicInteger(1);

    public static void main(String[] args) throws IOException {
        loadCourses();

        int port = 8080;
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);

        server.createContext("/api/courses", new CoursesHandler());
        server.createContext("/api/dashboard", new DashboardHandler());
        server.createContext("/", new StaticHandler());

        server.setExecutor(null);
        server.start();

        System.out.println("Class Bell running at http://localhost:" + port);
    }

    // ---------------------------------------------------------------
    // Data model
    // ---------------------------------------------------------------
    static class Course {
        int id;
        int day;          // 1 = Monday ... 7 = Sunday
        LocalTime start;
        LocalTime end;
        String name;
        String location;
        String teacher;

        String toLine() {
            return id + "|" + day + "|" + start + "|" + end + "|" +
                    escape(name) + "|" + escape(location) + "|" + escape(teacher);
        }

        static Course fromLine(String line) {
            String[] p = line.split("\\|", -1);
            Course c = new Course();
            c.id = Integer.parseInt(p[0]);
            c.day = Integer.parseInt(p[1]);
            c.start = LocalTime.parse(p[2]);
            c.end = LocalTime.parse(p[3]);
            c.name = unescape(p[4]);
            c.location = unescape(p[5]);
            c.teacher = unescape(p[6]);
            return c;
        }

        String toJson() {
            return "{"
                    + "\"id\":" + id + ","
                    + "\"day\":" + day + ","
                    + "\"start\":\"" + start + "\","
                    + "\"end\":\"" + end + "\","
                    + "\"name\":\"" + jsonEscape(name) + "\","
                    + "\"location\":\"" + jsonEscape(location) + "\","
                    + "\"teacher\":\"" + jsonEscape(teacher) + "\""
                    + "}";
        }

        private static String escape(String s) {
            return s == null ? "" : s.replace("|", "/");
        }

        private static String unescape(String s) {
            return s;
        }
    }

    // ---------------------------------------------------------------
    // Persistence — plain pipe-delimited text file, no JSON library needed
    // ---------------------------------------------------------------
    static synchronized void loadCourses() throws IOException {
        courses.clear();
        if (!Files.exists(DATA_FILE)) {
            Files.createDirectories(DATA_FILE.getParent());
            Files.createFile(DATA_FILE);
            return;
        }
        List<String> lines = Files.readAllLines(DATA_FILE, StandardCharsets.UTF_8);
        int maxId = 0;
        for (String line : lines) {
            if (line.isBlank()) continue;
            Course c = Course.fromLine(line);
            courses.add(c);
            maxId = Math.max(maxId, c.id);
        }
        idGen.set(maxId + 1);
        courses.sort(Comparator.comparingInt((Course c) -> c.day).thenComparing(c -> c.start));
    }

    static synchronized void saveCourses() throws IOException {
        List<String> lines = new ArrayList<>();
        for (Course c : courses) lines.add(c.toLine());
        Files.write(DATA_FILE, lines, StandardCharsets.UTF_8);
    }

    // ---------------------------------------------------------------
    // /api/courses  GET (list) / POST (create) / DELETE (?id=)
    // ---------------------------------------------------------------
    static class CoursesHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            try {
                switch (ex.getRequestMethod()) {
                    case "GET" -> handleGet(ex);
                    case "POST" -> handlePost(ex);
                    case "DELETE" -> handleDelete(ex);
                    case "OPTIONS" -> {
                        addCors(ex);
                        ex.sendResponseHeaders(204, -1);
                    }
                    default -> sendJson(ex, 405, "{\"error\":\"method not allowed\"}");
                }
            } catch (Exception e) {
                sendJson(ex, 500, "{\"error\":\"" + jsonEscape(e.getMessage()) + "\"}");
            }
        }

        void handleGet(HttpExchange ex) throws IOException {
            String json;
            synchronized (courses) {
                json = "[" + courses.stream().map(Course::toJson).collect(Collectors.joining(",")) + "]";
            }
            sendJson(ex, 200, json);
        }

        void handlePost(HttpExchange ex) throws IOException {
            String body = readBody(ex);
            Map<String, String> f = parseJsonObject(body);

            Course c = new Course();
            c.id = idGen.getAndIncrement();
            try {
                c.day = Integer.parseInt(f.getOrDefault("day", "1"));
                c.start = LocalTime.parse(pad(f.get("start")));
                c.end = LocalTime.parse(pad(f.get("end")));
            } catch (Exception e) {
                sendJson(ex, 400, "{\"error\":\"invalid day/start/end\"}");
                return;
            }
            c.name = f.getOrDefault("name", "Untitled course");
            c.location = f.getOrDefault("location", "");
            c.teacher = f.getOrDefault("teacher", "");

            if (c.day < 1 || c.day > 7 || !c.end.isAfter(c.start)) {
                sendJson(ex, 400, "{\"error\":\"day must be 1-7, and end must be after start\"}");
                return;
            }

            courses.add(c);
            courses.sort(Comparator.comparingInt((Course x) -> x.day).thenComparing(x -> x.start));
            saveCourses();
            sendJson(ex, 200, c.toJson());
        }

        void handleDelete(HttpExchange ex) throws IOException {
            Map<String, String> q = parseQuery(ex.getRequestURI());
            String idStr = q.get("id");
            if (idStr == null) {
                sendJson(ex, 400, "{\"error\":\"missing id parameter\"}");
                return;
            }
            int id = Integer.parseInt(idStr);
            boolean removed = courses.removeIf(c -> c.id == id);
            saveCourses();
            sendJson(ex, 200, "{\"removed\":" + removed + "}");
        }
    }

    // ---------------------------------------------------------------
    // /api/dashboard — today's schedule, countdown, and suggestions
    // ---------------------------------------------------------------
    static class DashboardHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            if (!"GET".equals(ex.getRequestMethod())) {
                sendJson(ex, 405, "{\"error\":\"method not allowed\"}");
                return;
            }
            LocalDate today = LocalDate.now();
            LocalTime now = LocalTime.now();
            int todayDow = today.getDayOfWeek().getValue(); // 1=Mon..7=Sun

            List<Course> todayCourses;
            synchronized (courses) {
                todayCourses = courses.stream()
                        .filter(c -> c.day == todayDow)
                        .sorted(Comparator.comparing(c -> c.start))
                        .collect(Collectors.toList());
            }

            // Find the next class: remaining today, or the soonest class on a future day
            Course next = null;
            int daysAhead = 0;
            for (Course c : todayCourses) {
                if (c.end.isAfter(now)) { next = c; daysAhead = 0; break; }
            }
            if (next == null) {
                synchronized (courses) {
                    outer:
                    for (int add = 1; add <= 7; add++) {
                        int d = ((todayDow - 1 + add) % 7) + 1;
                        List<Course> dayCourses = courses.stream()
                                .filter(c -> c.day == d)
                                .sorted(Comparator.comparing(c -> c.start))
                                .collect(Collectors.toList());
                        if (!dayCourses.isEmpty()) {
                            next = dayCourses.get(0);
                            daysAhead = add;
                            break outer;
                        }
                    }
                }
            }

            long minutesUntil = -1;
            boolean inProgress = false;
            if (next != null) {
                if (daysAhead == 0 && next.start.isBefore(now) && next.end.isAfter(now)) {
                    inProgress = true;
                    minutesUntil = java.time.Duration.between(now, next.end).toMinutes();
                } else if (daysAhead == 0) {
                    minutesUntil = java.time.Duration.between(now, next.start).toMinutes();
                } else {
                    long minutesLeftToday = java.time.Duration.between(now, LocalTime.MAX).toMinutes() + 1;
                    long minutesIntoNextDay = java.time.Duration.between(LocalTime.MIDNIGHT, next.start).toMinutes();
                    minutesUntil = minutesLeftToday + (daysAhead - 1) * 24L * 60L + minutesIntoNextDay;
                }
            }

            List<String> suggestions = buildSuggestions(todayCourses, now, next, daysAhead, minutesUntil, inProgress);

            StringBuilder sb = new StringBuilder();
            sb.append("{");
            sb.append("\"now\":\"").append(now.format(TIME_FMT)).append("\",");
            sb.append("\"todayDow\":").append(todayDow).append(",");
            sb.append("\"todayCourses\":[").append(
                    todayCourses.stream().map(Course::toJson).collect(Collectors.joining(","))
            ).append("],");
            if (next != null) {
                sb.append("\"nextClass\":").append(next.toJson()).append(",");
                sb.append("\"daysAhead\":").append(daysAhead).append(",");
                sb.append("\"minutesUntil\":").append(minutesUntil).append(",");
                sb.append("\"inProgress\":").append(inProgress).append(",");
            } else {
                sb.append("\"nextClass\":null,");
            }
            sb.append("\"suggestions\":[").append(
                    suggestions.stream().map(s -> "\"" + jsonEscape(s) + "\"").collect(Collectors.joining(","))
            ).append("]");
            sb.append("}");

            sendJson(ex, 200, sb.toString());
        }
    }

    /** Generates suggestions based on today's schedule density, gaps between classes, and time until the next class. */
    static List<String> buildSuggestions(List<Course> todayCourses, LocalTime now, Course next,
                                          int daysAhead, long minutesUntil, boolean inProgress) {
        List<String> tips = new ArrayList<>();

        if (inProgress) {
            tips.add("In class right now (" + next.name + ") — " + minutesUntil + " min left.");
        } else if (next != null && daysAhead == 0) {
            if (minutesUntil <= 10) {
                tips.add("Running late! " + next.name + " starts in " + minutesUntil
                        + " min — head to " + emptyOr(next.location, "class") + " now.");
            } else if (minutesUntil <= 30) {
                tips.add(minutesUntil + " min until " + next.name + " — time to pack up and head over.");
            } else if (minutesUntil <= 90) {
                tips.add(minutesUntil + " min until " + next.name + " — good window for a quick review of your notes.");
            } else {
                tips.add(minutesUntil + " min until your next class — plenty of time for focused study or assignments.");
            }
        } else if (next != null && daysAhead > 0) {
            tips.add("That's it for today. Next class is " + next.name + " in " + daysAhead + " day(s).");
        } else {
            tips.add("No classes on the schedule yet — add your first one below.");
        }

        // Flag notably long or short gaps between consecutive classes today
        for (int i = 0; i + 1 < todayCourses.size(); i++) {
            Course a = todayCourses.get(i);
            Course b = todayCourses.get(i + 1);
            long gap = java.time.Duration.between(a.end, b.start).toMinutes();
            if (gap >= 120) {
                tips.add(gap + " min gap between " + a.name + " and " + b.name + " — good time to study or grab a proper lunch.");
            } else if (gap > 0 && gap < 20) {
                tips.add("Only " + gap + " min between " + a.name + " and " + b.name + " — pack up early so you're not rushing.");
            }
        }

        long totalMinutes = todayCourses.stream()
                .mapToLong(c -> java.time.Duration.between(c.start, c.end).toMinutes())
                .sum();
        if (todayCourses.size() >= 5 || totalMinutes >= 360) {
            tips.add("Busy day (" + todayCourses.size() + " classes) — remember to take breaks and stay hydrated.");
        }
        if (todayCourses.isEmpty()) {
            tips.add("No classes today — good chance to get ahead on next week's material.");
        }

        // Rotates through a small set of general tips based on the day of the week
        String[] general = {
                "Get to class 5-10 minutes early to grab a good seat.",
                "Double-check your bag before heading out: textbook, notes, charger, water bottle.",
                "Switch your phone to silent or focus mode during class.",
                "Review today's notes within a couple hours — it sticks a lot better.",
                "Get enough sleep — it makes a bigger difference than any amount of last-minute cramming.",
        };
        int dow = LocalDate.now().getDayOfWeek().getValue();
        tips.add(general[dow % general.length]);

        return tips;
    }

    // ---------------------------------------------------------------
    // Static file serving (public/ directory)
    // ---------------------------------------------------------------
    static class StaticHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            String path = ex.getRequestURI().getPath();
            if (path.equals("/")) path = "/index.html";

            Path file = PUBLIC_DIR.resolve(path.substring(1)).normalize();
            if (!file.startsWith(PUBLIC_DIR) || !Files.exists(file) || Files.isDirectory(file)) {
                byte[] notFound = "404 Not Found".getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(404, notFound.length);
                try (OutputStream os = ex.getResponseBody()) { os.write(notFound); }
                return;
            }

            String contentType = guessContentType(file.toString());
            byte[] bytes = Files.readAllBytes(file);
            ex.getResponseHeaders().set("Content-Type", contentType);
            ex.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
        }

        String guessContentType(String name) {
            if (name.endsWith(".html")) return "text/html; charset=utf-8";
            if (name.endsWith(".css")) return "text/css; charset=utf-8";
            if (name.endsWith(".js")) return "application/javascript; charset=utf-8";
            if (name.endsWith(".svg")) return "image/svg+xml";
            return "application/octet-stream";
        }
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------
    static void addCors(HttpExchange ex) {
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.getResponseHeaders().set("Access-Control-Allow-Methods", "GET,POST,DELETE,OPTIONS");
        ex.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
    }

    static void sendJson(HttpExchange ex, int status, String json) throws IOException {
        addCors(ex);
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    static String readBody(HttpExchange ex) throws IOException {
        try (InputStream is = ex.getRequestBody()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    static String pad(String t) {
        if (t == null) return "00:00";
        if (t.length() == 4 && t.charAt(1) == ':') return "0" + t; // "9:30" -> "09:30"
        return t;
    }

    static Map<String, String> parseQuery(URI uri) {
        Map<String, String> map = new HashMap<>();
        String q = uri.getRawQuery();
        if (q == null) return map;
        for (String pair : q.split("&")) {
            int i = pair.indexOf('=');
            if (i < 0) continue;
            map.put(pair.substring(0, i), java.net.URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8));
        }
        return map;
    }

    /** Minimal parser for flat JSON objects like {"key":"value","key2":123} — that's all this app needs. */
    static Map<String, String> parseJsonObject(String json) {
        Map<String, String> map = new HashMap<>();
        if (json == null) return map;
        json = json.trim();
        if (json.startsWith("{")) json = json.substring(1);
        if (json.endsWith("}")) json = json.substring(0, json.length() - 1);

        List<String> pairs = splitTopLevel(json);
        for (String pair : pairs) {
            int colon = findColon(pair);
            if (colon < 0) continue;
            String key = stripQuotes(pair.substring(0, colon).trim());
            String value = stripQuotes(pair.substring(colon + 1).trim());
            map.put(key, jsonUnescape(value));
        }
        return map;
    }

    static List<String> splitTopLevel(String s) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        boolean inQuotes = false;
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' && (i == 0 || s.charAt(i - 1) != '\\')) inQuotes = !inQuotes;
            if (!inQuotes && (c == '{' || c == '[')) depth++;
            if (!inQuotes && (c == '}' || c == ']')) depth--;
            if (!inQuotes && depth == 0 && c == ',') {
                parts.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        if (!cur.isEmpty()) parts.add(cur.toString());
        return parts;
    }

    static int findColon(String pair) {
        boolean inQuotes = false;
        for (int i = 0; i < pair.length(); i++) {
            char c = pair.charAt(i);
            if (c == '"' && (i == 0 || pair.charAt(i - 1) != '\\')) inQuotes = !inQuotes;
            if (!inQuotes && c == ':') return i;
        }
        return -1;
    }

    static String stripQuotes(String s) {
        s = s.trim();
        if (s.length() >= 2 && s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"') {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    static String jsonEscape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    static String jsonUnescape(String s) {
        if (s == null) return "";
        return s.replace("\\\"", "\"").replace("\\n", "\n").replace("\\\\", "\\");
    }

    static String emptyOr(String s, String fallback) {
        return (s == null || s.isBlank()) ? fallback : s;
    }
}
