import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.Executors;

public class Main {
    // Edit this array to change the items users vote on. Values are displayed to users.
    private static final List<String> ITEMS = Arrays.asList(
            //"Location",
            //"Athletics",
            //"Alumni Network",
            //"Career Services",
            //"Christian Formation",
            //"Greek Life",
            "Academics",
            "Price",
            "Housing",
            "Food",
            "Campus Community",
            "Scholarships"
    );

    private static final Path VOTES_FILE = Paths.get("votes.txt");
    // Configurable: maximum number of pairs each user session should answer
    private static final int MAX_PAIRS_PER_USER = 15;

    public static void main(String[] args) throws Exception {
        int port = 8000;
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);

        // Serve static files and API endpoints
        server.createContext("/", Main::handleRequest);
        server.createContext("/pair", Main::handlePair);
        server.createContext("/items", Main::handleItems);
        server.createContext("/vote", Main::handleVote);
        server.createContext("/flush", Main::handleFlush);

        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        System.out.println("Server started at http://localhost:" + port + "/");
    }

    private static void handleRequest(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (path.equals("/") || path.equals("/index.html")) {
            serveFile(exchange, "web/index.html", "text/html");
            return;
        }
        if (path.equals("/script.js")) {
            serveFile(exchange, "web/script.js", "application/javascript");
            return;
        }
        if (path.equals("/styles.css")) {
            serveFile(exchange, "web/styles.css", "text/css");
            return;
        }
        exchange.sendResponseHeaders(404, -1);
    }

    private static void handleItems(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equalsIgnoreCase("GET")) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append('[');
        for (int i = 0; i < ITEMS.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(escapeJson(ITEMS.get(i))).append('"');
        }
        sb.append(']');
        sendResponse(exchange, sb.toString(), "application/json");
    }

    private static void handlePair(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equalsIgnoreCase("GET")) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }
        if (ITEMS.size() < 2) {
            sendResponse(exchange, "{}", "application/json");
            return;
        }
        int n = ITEMS.size();
        // Count how many times each unordered pair (i<j) has been compared.
        int[][] counts = new int[n][n];
        synchronized (Main.class) {
            if (Files.exists(VOTES_FILE)) {
                List<String> lines = Files.readAllLines(VOTES_FILE, StandardCharsets.UTF_8);
                for (String line : lines) {
                    if (line == null || line.trim().isEmpty()) continue;
                    String[] parts = line.trim().split("\\s+");
                    if (parts.length < 2) continue;
                    try {
                        int x = Integer.parseInt(parts[0]);
                        int y = Integer.parseInt(parts[1]);
                        if (x < 0 || y < 0 || x >= n || y >= n) continue;
                        int a = Math.min(x, y);
                        int b = Math.max(x, y);
                        counts[a][b]++;
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }

        // Find minimal count among all unordered pairs
        int min = Integer.MAX_VALUE;
        List<int[]> candidates = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                int c = counts[i][j];
                if (c < min) {
                    min = c;
                    candidates.clear();
                    candidates.add(new int[]{i, j});
                } else if (c == min) {
                    candidates.add(new int[]{i, j});
                }
            }
        }

        Random rnd = new Random();
        int[] pick = candidates.get(rnd.nextInt(candidates.size()));
        int a = pick[0];
        int b = pick[1];
        // Randomize left/right order for display
        if (rnd.nextBoolean()) {
            int t = a; a = b; b = t;
        }

        String json = String.format(Locale.ROOT,
                "{\"a\":%d,\"b\":%d,\"aLabel\":\"%s\",\"bLabel\":\"%s\",\"maxPairsPerUser\":%d}",
                a, b, escapeJson(ITEMS.get(a)), escapeJson(ITEMS.get(b)), MAX_PAIRS_PER_USER
        );
        sendResponse(exchange, json, "application/json");
    }

    private static void handleVote(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }
        byte[] bodyBytes = exchange.getRequestBody().readAllBytes();
        String body = new String(bodyBytes, StandardCharsets.UTF_8).trim();

        // Accept either JSON like {"winner":1,"loser":2} or plain form/space-separated "1 2"
        int winner = -1, loser = -1;
        if (body.startsWith("{")) {
            winner = extractIntFromJson(body, "winner");
            loser = extractIntFromJson(body, "loser");
        } else {
            // try to find two integers
            String[] parts = body.split("[^0-9]+");
            List<Integer> nums = new ArrayList<>();
            for (String p : parts) {
                if (p.isEmpty()) continue;
                try { nums.add(Integer.parseInt(p)); } catch (NumberFormatException ignored) {}
            }
            if (nums.size() >= 2) { winner = nums.get(0); loser = nums.get(1); }
        }

        if (!isValidIndex(winner) || !isValidIndex(loser)) {
            sendResponse(exchange, "{\"ok\":false,\"error\":\"invalid indices\"}", "application/json");
            return;
        }

        String line = winner + " " + loser + System.lineSeparator();
        synchronized (Main.class) {
            Files.write(VOTES_FILE, line.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
        sendResponse(exchange, "{\"ok\":true}", "application/json");
    }

    private static void handleFlush(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if (body.trim().isEmpty()) {
            sendResponse(exchange, "{\"ok\":true,\"written\":0}", "application/json");
            return;
        }

        StringBuilder out = new StringBuilder();
        int written = 0;
        String[] lines = body.split("\\R");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            String[] parts = trimmed.split("\\s+");
            if (parts.length < 2) continue;
            try {
                int winner = Integer.parseInt(parts[0]);
                int loser = Integer.parseInt(parts[1]);
                if (!isValidIndex(winner) || !isValidIndex(loser)) continue;
                out.append(winner).append(' ').append(loser).append(System.lineSeparator());
                written++;
            } catch (NumberFormatException ignored) {
            }
        }

        synchronized (Main.class) {
            if (out.length() > 0) {
                Files.write(VOTES_FILE, out.toString().getBytes(StandardCharsets.UTF_8),
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
        }

        sendResponse(exchange, "{\"ok\":true,\"written\":" + written + "}", "application/json");
    }

    private static boolean isValidIndex(int i) {
        return i >= 0 && i < ITEMS.size();
    }

    private static void serveFile(HttpExchange exchange, String filePath, String contentType) throws IOException {
        Path p = Paths.get(filePath);
        if (!Files.exists(p)) {
            exchange.sendResponseHeaders(404, -1);
            return;
        }
        byte[] bytes = Files.readAllBytes(p);
        exchange.getResponseHeaders().set("Content-Type", contentType + "; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static void sendResponse(HttpExchange exchange, String text, String contentType) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType + "; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static int extractIntFromJson(String json, String key) {
        int idx = json.indexOf('"' + key + '"');
        if (idx == -1) idx = json.indexOf(key);
        if (idx == -1) return -1;
        int colon = json.indexOf(':', idx);
        if (colon == -1) return -1;
        int i = colon + 1;
        // skip whitespace
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
        // read optional quote
        boolean quoted = false;
        if (i < json.length() && json.charAt(i) == '"') { quoted = true; i++; }
        StringBuilder sb = new StringBuilder();
        while (i < json.length()) {
            char c = json.charAt(i);
            if (quoted) {
                if (c == '"') break;
                sb.append(c);
            } else {
                if (!Character.isDigit(c) && c != '-') break;
                sb.append(c);
            }
            i++;
        }
        try {
            return Integer.parseInt(sb.toString());
        } catch (Exception e) {
            return -1;
        }
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
