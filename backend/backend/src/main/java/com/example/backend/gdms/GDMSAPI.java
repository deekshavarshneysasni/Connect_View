package com.example.backend.gdms;


import java.net.URI;
import java.net.URLEncoder;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpClient.Redirect;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors; // JDK 11: needed for collect(toList)

public class GDMSAPI {
    // ======== Configuration you set when constructing ========
    private final String gdmsDomain;         // e.g. "www.gdms.cloud"
    private final String baseTokenUrl;       // https://{gdms_domain}/oapi/oauth/token
    private final String username;
    private final String passwordHashed;     // sha256(md5(password))
    private final String clientId;
    private final String clientSecret;
    private final String scope;              // nullable
    private final int expirySkewSeconds;     // refresh before expiry
    private final int timeoutSeconds;        // request timeout
    private final boolean debug;             // optional debug printing

    // ======== HTTP ========
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(20))
            .followRedirects(Redirect.NORMAL)
            .build();

    // ======== Token state ========
    private volatile String accessToken;
    private volatile String refreshToken;
    private volatile String tokenType = "Bearer";
    private volatile long   expiresAtEpoch;

    // ======== Background refresh ========
    private ScheduledExecutorService scheduler;
    private final Object schedLock = new Object();

    // ======== Public constants used by workflow methods (optional) ========
    public static final String DEFAULT_API_VERSION = "v1.0.0";

    // ======== Constructor ========
    public GDMSAPI(
            String gdmsDomain,
            String username,
            String passwordPlain,
            String clientId,
            String clientSecret,
            String scope,             // nullable
            int expirySkewSeconds,    // e.g. 120
            int timeoutSeconds,       // e.g. 20
            boolean debug             // true for verbose logs
    ) {
        this.gdmsDomain = Objects.requireNonNull(gdmsDomain);
        this.baseTokenUrl = "https://" + gdmsDomain + "/oapi/oauth/token";
        this.username = Objects.requireNonNull(username);
        this.passwordHashed = sha256Hex(md5Hex(passwordPlain.getBytes(StandardCharsets.UTF_8))
                .getBytes(StandardCharsets.UTF_8));
        this.clientId = Objects.requireNonNull(clientId);
        this.clientSecret = Objects.requireNonNull(clientSecret);
        this.scope = scope;
        this.expirySkewSeconds = Math.max(expirySkewSeconds, 10);
        this.timeoutSeconds = timeoutSeconds;
        this.debug = debug;
    }

    // =====================================================================
    //                          TOKEN HANDLING
    // =====================================================================

    /** Return a valid access token, refreshing if needed. */
    public synchronized String ensureToken() {
        if (!isTokenValid()) {
            if (refreshToken != null) {
                try { refresh(); } catch (Exception e) { passwordGrant(); }
            } else {
                passwordGrant();
            }
        }
        return accessToken;
    }
    public Map<String, Object> getStatusPayload() {
        return statusPayload;
    }

    /** Authorization header map: { "Authorization": "Bearer <token>" } */
    public Map<String, String> authHeader() {
        String tok = ensureToken();
        String typ = (tokenType == null || tokenType.isBlank()) ? "Bearer" : tokenType;
        typ = typ.substring(0,1).toUpperCase(Locale.ROOT) + typ.substring(1).toLowerCase(Locale.ROOT);
        return Map.of("Authorization", typ + " " + tok);
    }

    /** Start a daemon scheduler that periodically ensures/refreshes the token. */
    public void startRefreshLoop(int minSleepSec, int maxSleepSec) {
        synchronized (schedLock) {
            if (scheduler != null && !scheduler.isShutdown()) return;
            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "gdms-token-refresh");
                t.setDaemon(true);
                return t;
            });
            // (kept original behavior)
            scheduler.scheduleWithFixedDelay(() -> {
                try { ensureToken(); } catch (Exception ignored) {}
            }, Math.max(minSleepSec, 10), Math.max(minSleepSec, 10), TimeUnit.SECONDS);
        }
    }

    /** Stop the background token refresh loop. */
    public void stopRefreshLoop() {
        synchronized (schedLock) {
            if (scheduler != null) {
                scheduler.shutdownNow();
                scheduler = null;
            }
        }
    }

    // ---- Internals ----
    private boolean isTokenValid() {
        return accessToken != null && (Instant.now().getEpochSecond() + expirySkewSeconds) < expiresAtEpoch;
    }

    private void forceRefresh() {
        try { refresh(); } catch (Exception e) { passwordGrant(); }
    }

    private void passwordGrant() {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("username", username);
        q.put("password", passwordHashed);
        q.put("grant_type", "password");
        q.put("client_id", clientId);
        q.put("client_secret", clientSecret);
        if (scope != null && !scope.isBlank()) q.put("scope", scope);
        callTokenEndpoint(q);
    }

    private void refresh() {
        if (refreshToken == null) throw new RuntimeException("No refresh_token available.");
        Map<String, String> q = new LinkedHashMap<>();
        q.put("grant_type", "refresh_token");
        q.put("refresh_token", refreshToken);
        q.put("client_id", clientId);
        q.put("client_secret", clientSecret);
        callTokenEndpoint(q);
    }

    private void dumpResponse(String tag, HttpResponse<String> response) {
        System.out.println("=== " + tag + " ===");
        System.out.println("Status: " + response.statusCode());
        System.out.println("Content-Type: " + response.headers().firstValue("Content-Type").orElse("(none)"));
        System.out.println("Body:\n" + truncate(response.body(), 800));
        System.out.println("===============");
    }

    private void callTokenEndpoint(Map<String, String> params) {
        try {
            String url = baseTokenUrl + "?" + form(params);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(java.time.Duration.ofSeconds(timeoutSeconds))
                    .header("Accept", "*/*")
                    .header("User-Agent", "GDMSTokenClient/1.0")
                    .GET()
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (debug) dumpResponse("Token Response", resp);
            handleTokenResponse(resp);
        } catch (Exception e) {
            throw new RuntimeException("Token request failed", e);
        }
    }

    private void handleTokenResponse(HttpResponse<String> response) {
        String body = response.body();
        if (response.statusCode() != 200) {
            throw new RuntimeException("Token request failed: HTTP " + response.statusCode()
                    + " body=" + truncate(body, 500));
        }

        String ct = response.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
        Map<String, String> kv;
        if (ct.contains("application/x-www-form-urlencoded") || ct.contains("text/plain")) {
            kv = parseFormEncoded(body);
        } else if (ct.contains("application/json")) {
            kv = parseJsonFlat(body);
        } else {
            kv = parseFormEncoded(body);
            if (!kv.containsKey("access_token")) kv = parseJsonFlat(body);
        }

        String at = kv.get("access_token");
        if (at == null || at.isBlank()) {
            throw new RuntimeException("No access_token in token response. Body=" + truncate(body, 500));
        }
        this.accessToken = at;

        String tt = kv.getOrDefault("token_type", "bearer");
        this.tokenType = tt;

        String rt = kv.get("refresh_token");
        if (rt != null && !rt.isBlank()) this.refreshToken = rt;

        int expiresIn = 3600;
        try { expiresIn = Integer.parseInt(kv.getOrDefault("expires_in", "3600")); } catch (Exception ignore) {}
        this.expiresAtEpoch = Instant.now().getEpochSecond() + expiresIn;
    }

    // =====================================================================
    //                          REQUEST HELPER
    // =====================================================================

    public HttpResponse<String> request(
            String method,
            String url,
            boolean useHeader,
            Map<String, Object> params,
            Map<String, String> headers,
            String body
    ) throws Exception {
        String token = ensureToken();
        String urlWithParams = appendQuery(url, params);
        Map<String, String> hdrs = new HashMap<>();
        if (headers != null) hdrs.putAll(headers);

        if (useHeader) hdrs.putAll(authHeader());
        else urlWithParams = addQueryToken(urlWithParams, token);

        HttpResponse<String> resp = send(method, urlWithParams, hdrs, body);
        if (resp.statusCode() == 401 || resp.statusCode() == 403) {
            forceRefresh();
            hdrs = new HashMap<>(headers == null ? Map.of() : headers);
            if (useHeader) hdrs.putAll(authHeader());
            else urlWithParams = addQueryToken(appendQuery(url, params), accessToken);
            resp = send(method, urlWithParams, hdrs, body);
        }
        return resp;
    }

    private HttpResponse<String> send(String method, String url, Map<String, String> headers, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(java.time.Duration.ofSeconds(timeoutSeconds));
        if (body != null) b.method(method.toUpperCase(Locale.ROOT), HttpRequest.BodyPublishers.ofString(body));
        else b.method(method.toUpperCase(Locale.ROOT), HttpRequest.BodyPublishers.noBody());
        if (headers != null) headers.forEach(b::header);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    // =====================================================================
    //                       SIGNED JSON CALL HELPERS
    // =====================================================================

    /** Signed POST JSON → returns parsed Map (GDMS envelope) with retCode check. */
    private Map<String,Object> postJsonSigned(String url, Map<String,Object> body) {
        long ts = System.currentTimeMillis();
        String token = ensureToken();
        Signature.JsonSig sig = Signature.buildSignatureJson(
                url, token, clientId, clientSecret, ts, body, true, true
        );

        String finalUrl = Signature.attachCommonParamsToUrl(url, token, ts, sig.signature);
        Signature.JsonNorm norm = Signature.normalizeBodyJson(body);
        String rawBody = norm.rawBody;
        try {
            HttpResponse<String> resp = request("POST", finalUrl, true, null,
                    Map.of("Content-Type", "application/json", "Accept", "application/json"),
                    rawBody);
            if (resp.statusCode() != 200) {
                throw new RuntimeException("HTTP " + resp.statusCode() + " " + truncate(resp.body(), 500));
            }
            Object parsed = MiniJson.parse(resp.body());
            if (!(parsed instanceof Map)) throw new RuntimeException("Non-object JSON: " + truncate(resp.body(), 500));
            Map<String,Object> m = castMap(parsed);
            Object rc = m.get("retCode");
            if (rc == null || toInt(rc) != 0) {
                throw new RuntimeException("API error: " + rc + " - " + String.valueOf(m.get("msg")));
            }
            return m;
        } catch (Exception e) {
            throw new RuntimeException("POST signed failed: " + e.getMessage(), e);
        }
    }

    // =====================================================================
    //                              STEP APIS
    // =====================================================================

    /** Signed GET JSON (optionally with a JSON body) → returns parsed Map with retCode check. */
    private Map<String, Object> getSigned(String url, Map<String, Object> bodyOrNull) {
        long ts = System.currentTimeMillis();
        String token = ensureToken(); // Ensure we have a valid token

        Signature.JsonSig sig = Signature.buildSignatureJson(
                url, token, clientId, clientSecret, ts, bodyOrNull, true, true
        );

        String finalUrl = Signature.attachCommonParamsToUrl(url, token, ts, sig.signature);
        String rawBody = (bodyOrNull == null) ? null : Signature.normalizeBodyJson(bodyOrNull).rawBody;

        try {
            HttpResponse<String> resp = request("GET", finalUrl, true, null,
                    Map.of("Content-Type", "application/json", "Accept", "application/json"), rawBody);

            if (resp.statusCode() != 200) {
                throw new RuntimeException("HTTP " + resp.statusCode() + " " + truncate(resp.body(), 500));
            }

            Object parsed = MiniJson.parse(resp.body());
            if (!(parsed instanceof Map)) throw new RuntimeException("Non-object JSON: " + truncate(resp.body(), 500));
            Map<String, Object> m = castMap(parsed);

            Object rc = m.get("retCode");
            if (rc == null || toInt(rc) != 0) {
                throw new RuntimeException("API error: " + rc + " - " + m.get("msg"));
            }
            return m;
        } catch (Exception e) {
            throw new RuntimeException("GET signed failed: " + e.getMessage(), e);
        }
    }

    /** Step 1: List all orgs (paged). */
    public List<Map<String,Object>> listOrgsAll(int pageSize) {
        String url = "https://" + gdmsDomain + "/oapi/" + DEFAULT_API_VERSION + "/org/list";
        Map<String,Object> page1 = getSigned(url, mapOf("pageSize", pageSize, "pageNum", 1));
        Map<String,Object> data = castMap(page1.get("data"));
        List<Map<String,Object>> items = castListMap(data.get("result"));
        int pages = toInt(data.get("pages"), 1);
        for (int p = 2; p <= pages; p++) {
            Map<String,Object> nxt = getSigned(url, mapOf("pageSize", pageSize, "pageNum", p));
            Map<String,Object> d2 = castMap(nxt.get("data"));
            items.addAll(castListMap(d2.get("result")));
        }
        return items;
    }

    /** Step 2: Device list for one org (paged), returns the raw device rows. */
    public List<Map<String,Object>> fetchDevicesForOrg(int orgId, int pageSize) {
        String url = "https://" + gdmsDomain + "/oapi/" + DEFAULT_API_VERSION + "/device/list";
        List<Map<String,Object>> out = new ArrayList<>();
        Map<String,Object> body = new LinkedHashMap<>();
        body.put("order", "");
        body.put("pageNum", 1);
        body.put("pageSize", pageSize);
        body.put("type", "");
        body.put("orgId", orgId);

        System.out.println("Requesting device list for Org ID: " + orgId);
        Map<String,Object> page1 = postJsonSigned(url, body);

        System.out.println("Device list response: " + MiniJson.stringify(page1));

        Map<String,Object> data = castMap(page1.get("data"));
        List<Map<String,Object>> devices = castListMap(data.get("result"));

        if (devices.isEmpty()) {
            System.out.println("No devices found for Org " + orgId);
        }

        out.addAll(devices);
        int pages = toInt(data.get("pages"), 1);
        for (int p = 2; p <= pages; p++) {
            body.put("pageNum", p);
            Map<String,Object> nxt = postJsonSigned(url, body);
            Map<String,Object> d2 = castMap(nxt.get("data"));
            out.addAll(castListMap(d2.get("result")));
        }
        return out;
    }

    /** Step 3: Device account status for a MAC. Returns the entire response Map. */
    public Map<String,Object> getDeviceAccountStatus(String mac) {
        if (mac == null || mac.isBlank()) throw new IllegalArgumentException("mac must be non-empty");
        String url = "https://" + gdmsDomain + "/oapi/" + DEFAULT_API_VERSION + "/device/account/status";
        Map<String,Object> body = mapOf("mac", mac.trim());
        long ts = System.currentTimeMillis();
        String token = ensureToken();
        Signature.JsonSig sig = Signature.buildSignatureJson(
                url, token, clientId, clientSecret, ts, body, true, true
        );
        String finalUrl = Signature.attachCommonParamsToUrl(url, token, ts, sig.signature);
        String rawBody = Signature.normalizeBodyJson(body).rawBody;
        try {
            HttpResponse<String> resp = request("POST", finalUrl, true, null,
                    Map.of("Content-Type", "application/json", "Accept", "application/json"), rawBody);
            if (resp.statusCode() != 200) throw new RuntimeException("HTTP " + resp.statusCode() + " " + truncate(resp.body(), 500));
            Object parsed = MiniJson.parse(resp.body());
            Map<String,Object> m = castMap(parsed);
            Object rc = m.get("retCode");
            if (rc == null || toInt(rc) != 0) throw new RuntimeException("API error: " + rc + " - " + m.get("msg"));
            return m;
        } catch (Exception e) {
            throw new RuntimeException("status call failed for mac " + mac + ": " + e.getMessage(), e);
        }
    }

    /** Step 4: SIP account list for an org (paged). */
    public List<Map<String,Object>> sipListForOrg(int orgId, int pageSize) {
        String url = "https://" + gdmsDomain + "/oapi/" + DEFAULT_API_VERSION + "/sip/account/list";
        List<Map<String,Object>> out = new ArrayList<>();
        Map<String,Object> body = new LinkedHashMap<>();
        body.put("order", "");
        body.put("pageNum", 1);
        body.put("pageSize", pageSize);
        body.put("type", "");
        body.put("orgId", orgId);
        Map<String,Object> page1 = postJsonSigned(url, body);
        Map<String,Object> data = castMap(page1.get("data"));
        out.addAll(castListMap(data.get("result")));
        int pages = toInt(data.get("pages"), 1);
        for (int p = 2; p <= pages; p++) {
            body.put("pageNum", p);
            Map<String,Object> nxt = postJsonSigned(url, body);
            Map<String,Object> d2 = castMap(nxt.get("data"));
            out.addAll(castListMap(d2.get("result")));
        }
        return out;
    }

    // =====================================================================
    //                           MINI JSON (no deps)
    // =====================================================================

    static final class MiniJson {
        static Object parse(String s) { return new Parser(s).parse(); }
        static String stringify(Object v) { return toJson(v); }

        @SuppressWarnings("unchecked")
        private static String toJson(Object v) {
            if (v == null) return "null";
            if (v instanceof String) return quote((String) v);
            if (v instanceof Boolean) return (Boolean) v ? "true" : "false";
            if (v instanceof Number) {
                String s = v.toString();
                if (s.endsWith(".0")) s = s.substring(0, s.length() - 2);
                return s;
            }
            if (v instanceof Map) {
                Map<String,Object> in = (Map<String,Object>) v;
                TreeMap<String,Object> sorted = new TreeMap<>(in);
                StringBuilder sb = new StringBuilder("{");
                boolean first = true;
                for (var e : sorted.entrySet()) {
                    if (!first) sb.append(',');
                    first = false;
                    sb.append(quote(e.getKey())).append(':').append(toJson(e.getValue()));
                }
                sb.append('}');
                return sb.toString();
            }
            if (v instanceof Iterable) {
                StringBuilder sb = new StringBuilder("[");
                boolean first = true;
                for (Object x : (Iterable<?>) v) {
                    if (!first) sb.append(',');
                    first = false;
                    sb.append(toJson(x));
                }
                sb.append(']');
                return sb.toString();
            }
            return quote(String.valueOf(v));
        }

        private static String quote(String s) {
            StringBuilder sb = new StringBuilder(s.length() + 2);
            sb.append('"');
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '"': sb.append("\\\""); break;
                    case '\\': sb.append("\\\\"); break;
                    case '\b': sb.append("\\b"); break;
                    case '\f': sb.append("\\f"); break;
                    case '\n': sb.append("\\n"); break;
                    case '\r': sb.append("\\r"); break;
                    case '\t': sb.append("\\t"); break;
                    default:
                        if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                        else sb.append(c);
                }
            }
            sb.append('"');
            return sb.toString();
        }

        private static final class Parser {
            private final String s; private int i;
            Parser(String s) { this.s = s; this.i = 0; }
            Object parse() { skip(); Object v = val(); skip(); return v; }
            private Object val() {
                skip(); if (i >= s.length()) throw err("Unexpected end");
                char c = s.charAt(i);
                if (c == '"') return str();
                if (c == '{') return obj();
                if (c == '[') return arr();
                if (c == 't' || c == 'f') return bool();
                if (c == 'n') return nul();
                return num();
            }
            private Map<String,Object> obj() {
                exp('{'); Map<String,Object> m = new LinkedHashMap<>(); skip();
                if (peek() == '}') { i++; return m; }
                while (true) {
                    String k = str(); skip(); exp(':'); Object v = val(); m.put(k, v); skip();
                    char c = exp(',', '}'); if (c == '}') break;
                } return m;
            }
            private List<Object> arr() {
                exp('['); List<Object> a = new ArrayList<>(); skip();
                if (peek() == ']') { i++; return a; }
                while (true) {
                    a.add(val()); skip(); char c = exp(',', ']'); if (c == ']') break;
                } return a;
            }
            private String str() {
                exp('"'); StringBuilder sb = new StringBuilder();
                while (i < s.length()) {
                    char c = s.charAt(i++);
                    if (c == '"') break;
                    if (c == '\\') {
                        if (i >= s.length()) throw err("Bad escape");
                        char e = s.charAt(i++);
                        switch (e) {
                            case '"': sb.append('"'); break;
                            case '\\': sb.append('\\'); break;
                            case '/': sb.append('/'); break;
                            case 'b': sb.append('\b'); break;
                            case 'f': sb.append('\f'); break;
                            case 'n': sb.append('\n'); break;
                            case 'r': sb.append('\r'); break;
                            case 't': sb.append('\t'); break;
                            case 'u':
                                if (i + 4 > s.length()) throw err("Bad \\u escape");
                                sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break;
                            default: sb.append(e);
                        }
                    } else sb.append(c);
                } return sb.toString();
            }
            private Boolean bool() {
                if (s.startsWith("true", i)) { i += 4; return true; }
                if (s.startsWith("false", i)) { i += 5; return false; }
                throw err("Bad boolean");
            }
            private Object nul() { if (s.startsWith("null", i)) { i += 4; return null; } throw err("Bad null"); }
            private Number num() {
                int st = i; if (s.charAt(i) == '-') i++;
                while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
                if (i < s.length() && s.charAt(i) == '.') { i++; while (i < s.length() && Character.isDigit(s.charAt(i))) i++; }
                if (i < s.length() && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
                    i++; if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) i++;
                    while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
                }
                String n = s.substring(st, i);
                try {
                    if (n.contains(".") || n.contains("e") || n.contains("E")) return Double.parseDouble(n);
                    long lv = Long.parseLong(n);
                    if (lv >= Integer.MIN_VALUE && lv <= Integer.MAX_VALUE) return (int) lv;
                    return lv;
                } catch (Exception e) { return Double.parseDouble(n); }
            }
            private void skip() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }
            private char peek() { return i < s.length() ? s.charAt(i) : '\0'; }
            private void exp(char c) { if (i >= s.length() || s.charAt(i++) != c) throw err("Expected " + c); }
            private char exp(char a, char b) { if (i >= s.length()) throw err("Unexpected end"); char c = s.charAt(i++); if (c==a||c==b) return c; throw err("Expected " + a + " or " + b + " got " + c); }
            private IllegalStateException err(String m) { return new IllegalStateException(m + " at " + i); }
        }
    }

    // =====================================================================
    //                             SIGNATURES
    // =====================================================================
    // SIGNATURE CREATION
    public static final class Signature {
        public static final class JsonSig {
            public final String signature, bodySha256, canonical;
            public JsonSig(String s, String b, String c) { signature = s; bodySha256 = b; canonical = c; }
        }
        public static final class JsonNorm {
            public final String rawBody; public final String bodySha256;
            public JsonNorm(String r, String b) { rawBody = r; bodySha256 = b; }
        }
        public static final class FormSig {
            public final String signature, canonical;
            public FormSig(String s, String c) { signature = s; canonical = c; }
        }

        public static JsonNorm normalizeBodyJson(Object body) {
            if (body == null) return new JsonNorm("", null);
            String raw;
            if (body instanceof String) raw = (String) body;
            else if (body instanceof byte[]) raw = new String((byte[]) body, StandardCharsets.UTF_8);
            else raw = MiniJson.stringify(body);
            return new JsonNorm(raw, sha256Hex(raw.getBytes(StandardCharsets.UTF_8)));
        }

        public static JsonSig buildSignatureJson(
                String url,
                String accessToken,
                String clientId,
                String clientSecret,
                long timestampMs,
                Object body,
                boolean includeUrlParamsInSignature,
                boolean caseSensitiveSort
        ) {
            Map<String,String> sigParams = new LinkedHashMap<>();
            if (includeUrlParamsInSignature) sigParams.putAll(parseQuery(url));
            sigParams.put("access_token", accessToken);
            sigParams.put("client_id", clientId);
            sigParams.put("client_secret", clientSecret);
            sigParams.put("timestamp", String.valueOf(timestampMs));

            JsonNorm norm = normalizeBodyJson(body);
            String canonical = kvJoin(sigParams, caseSensitiveSort);
            String wrapped = (norm.bodySha256 != null && !norm.bodySha256.isEmpty())
                    ? wrapAmpersands(canonical + "&" + norm.bodySha256)
                    : wrapAmpersands(canonical);
            String signature = sha256Hex(wrapped.getBytes(StandardCharsets.UTF_8));
            //System.out.println(signature);
            return new JsonSig(signature, norm.bodySha256, canonical);
        }

        public static FormSig buildSignatureForm(
                String url,
                String accessToken,
                String clientId,
                String clientSecret,
                long timestampMs,
                Map<String, ?> urlencodedFields,
                Map<String, Object> fileFields,
                boolean includeUrlParamsInSignature,
                boolean caseSensitiveSort
        ) {
            Map<String,String> sigParams = new LinkedHashMap<>();
            if (includeUrlParamsInSignature) sigParams.putAll(parseQuery(url));
            sigParams.put("access_token", accessToken);
            sigParams.put("client_id", clientId);
            sigParams.put("client_secret", clientSecret);
            sigParams.put("timestamp", String.valueOf(timestampMs));

            if (urlencodedFields != null) {
                for (var e : urlencodedFields.entrySet()) sigParams.put(e.getKey(), String.valueOf(e.getValue()));
            }
            if (fileFields != null) {
                for (var e : fileFields.entrySet()) {
                    String field = e.getKey(); Object val = e.getValue(); String md5v;
                    try {
                        if (val instanceof byte[]) md5v = md5Hex((byte[]) val);
                        else if (val instanceof String) md5v = md5Hex(Files.readAllBytes(Path.of((String) val)));
                        else throw new IllegalArgumentException("fileFields value must be byte[] or String filepath");
                    } catch (Exception ex) { throw new RuntimeException("Failed md5 for " + field, ex); }
                    sigParams.put(field, md5v);
                }
            }

            String canonical = kvJoin(sigParams, caseSensitiveSort);
            String wrapped = wrapAmpersands(canonical);
            String signature = sha256Hex(wrapped.getBytes(StandardCharsets.UTF_8));
            return new FormSig(signature, canonical);
        }

        public static String attachCommonParamsToUrl(String url, String accessToken, Long timestampMs, String signature) {
            long ts = (timestampMs != null) ? timestampMs : System.currentTimeMillis();
            Map<String,String> q = parseQuery(url);
            q.put("access_token", accessToken);
            q.put("timestamp", String.valueOf(ts));
            q.put("signature", signature);
            // System.out.println("Signature: "signature );
            return rebuildUrlWithQuery(url, q);
        }

        public static Map<String,String> parseQuery(String url) {
            Map<String,String> out = new LinkedHashMap<>();
            try {
                var u = URI.create(url);
                String qs = u.getQuery();
                if (qs == null || qs.isBlank()) return out;
                for (String pair : qs.split("&")) {
                    if (pair.isBlank()) continue;
                    int i = pair.indexOf('=');
                    String k = i >= 0 ? pair.substring(0, i) : pair;
                    String v = i >= 0 ? pair.substring(1 + i) : "";
                    out.put(k, v); // keep raw (not decoding) for signature
                }
                return out;
            } catch (Exception e) { return out; }
        }

        public static String rebuildUrlWithQuery(String url, Map<String, String> q) {
            URI u = URI.create(url);
            String query = joinQueryEncoded(q);
            return u.getScheme() + "://" + u.getHost() + (u.getPort() == -1 ? "" : ":" + u.getPort())
                    + (u.getRawPath() == null ? "" : u.getRawPath())
                    + (query.isBlank() ? "" : "?" + query)
                    + (u.getRawFragment() == null ? "" : "#" + u.getRawFragment());
        }

        private static String joinQueryEncoded(Map<String, String> q) {
            StringBuilder sb = new StringBuilder(); boolean first = true;
            for (var e : q.entrySet()) {
                if (!first) sb.append('&'); first = false;
                sb.append(encode(e.getKey())).append('=').append(encode(e.getValue()));
            }
            return sb.toString();
        }

        public static String kvJoin(Map<String,String> params, boolean caseSensitive) {
            List<Map.Entry<String,String>> entries = new ArrayList<>(params.entrySet());
            if (caseSensitive) entries.sort(Map.Entry.comparingByKey());
            else entries.sort((a,b)->{
                int c = a.getKey().toLowerCase(Locale.ROOT).compareTo(b.getKey().toLowerCase(Locale.ROOT));
                return c != 0 ? c : a.getKey().compareTo(b.getKey());
            });
            StringBuilder sb = new StringBuilder(); boolean first = true;
            for (var e : entries) {
                if (!first) sb.append('&'); first = false;
                sb.append(e.getKey()).append('=').append(e.getValue());
            }
            return sb.toString();
        }

        public static String wrapAmpersands(String s) {
            if (s.startsWith("&") && s.endsWith("&")) return s;
            return "&" + s + "&";
        }

        public static String encode(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
    }


    private static String addQueryToken(String url, String token) {
        String sep = url.contains("?") ? "&" : "?";
        return url + sep + "access_token=" + URLEncoder.encode(token, StandardCharsets.UTF_8);
    }

    private static String appendQuery(String url, Map<String, Object> params) {
        if (params == null || params.isEmpty()) return url;
        StringBuilder sb = new StringBuilder(url);
        sb.append(url.contains("?") ? "&" : "?");
        boolean first = true;
        for (var e : params.entrySet()) {
            if (!first) sb.append('&'); first = false;
            sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(String.valueOf(e.getValue()), StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    private static String form(Map<String, String> params) {
        StringBuilder sb = new StringBuilder(); boolean first = true;
        for (var e : params.entrySet()) {
            if (!first) sb.append('&'); first = false;
            sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    private static Map<String, String> parseFormEncoded(String body) {
        Map<String, String> map = new HashMap<>();
        if (body == null) return map;
        for (String pair : body.split("&")) {
            if (pair.isBlank()) continue;
            String[] kv = pair.split("=", 2);
            String k = urlDecode(kv[0]);
            String v = kv.length > 1 ? urlDecode(kv[1]) : "";
            map.put(k, v);
        }
        return map;
    }

    private static Map<String,String> parseJsonFlat(String json) {
        Object obj = MiniJson.parse(json);
        Map<String,String> out = new HashMap<>();
        if (obj instanceof Map) {
            for (var e : ((Map<?,?>) obj).entrySet()) {
                out.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
            }
        }
        return out;
    }

    private static String urlDecode(String s) { return URLDecoder.decode(s, StandardCharsets.UTF_8); }
    private static String truncate(String s, int n) { return (s == null || s.length() <= n) ? s : s.substring(0, n) + "..."; }

    // Hash helpers
    private static String md5Hex(byte[] data) {
        try { MessageDigest md = MessageDigest.getInstance("MD5"); return toHex(md.digest(data)); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
    private static String sha256Hex(byte[] data) {
        try { MessageDigest md = MessageDigest.getInstance("SHA-256"); return toHex(md.digest(data)); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static Map<String,Object> castMap(Object o) {
        if (o == null) return null;
        if (o instanceof Map) return (Map<String,Object>) o;
        throw new IllegalStateException("Expected Map, got " + o.getClass());
    }
    private static List<Map<String,Object>> castListMap(Object o) {
        List<Map<String,Object>> out = new ArrayList<>();
        if (o == null) return out;
        if (!(o instanceof List)) throw new IllegalStateException("Expected List, got " + o.getClass());
        for (Object x : (List<?>) o) out.add(castMap(x));
        return out;
    }
    private static Map<String,Object> mapOf(Object... kv) {
        Map<String,Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }
    private static int toInt(Object o) { return toInt(o, 0); }
    private static int toInt(Object o, int def) {
        if (o == null) return def;
        if (o instanceof Number) return ((Number) o).intValue();
        try { return Integer.parseInt(String.valueOf(o)); } catch (Exception e) { return def; }
    }

    // ---------- File I/O helpers ----------
    private static void writeJson(String file, Object data) {
        try {
            String compact = MiniJson.stringify(data);
            String pretty  = prettyJson(compact);
            java.nio.file.Files.writeString(
                    java.nio.file.Path.of(file),
                    pretty + System.lineSeparator(),
                    java.nio.charset.StandardCharsets.UTF_8
            );
        } catch (Exception e) {
            throw new RuntimeException("Failed writing " + file + ": " + e.getMessage(), e);
        }
    }

    // ---------- Slug helper (used in filenames) ----------
    private static String safeSlug(String s) {
        if (s == null) return "unnamed";
        s = s.trim().replaceAll("\\s+", "-").replaceAll("[^A-Za-z0-9._-]", "");
        return s.isEmpty() ? "unnamed" : (s.length() > 80 ? s.substring(0, 80) : s);
    }

    private List<Map<String, Object>> selected;

    // ---------- Interactive flow ----------
    public void fetchDevicesForSelectedOrgsInteractive() {
        System.out.println("Step 1: Fetching organizations...");
        java.util.List<java.util.Map<String, Object>> orgs = listOrgsAll(1000);
        writeJson("orgs.json", mapOf("data", orgs));
        // System.out.println("✅ Orgs fetched: " + orgs.size() + "  | 💾 Saved → orgs.json");

        int preview = Math.min(5, orgs.size());
        if (preview > 0) {
            java.util.List<java.util.Map<String, Object>> first = new java.util.ArrayList<>();
            for (int i = 0; i < preview; i++) first.add(orgs.get(i));
            System.out.println(MiniJson.stringify(first));
        }

        System.out.print("\nEnter org name(s), comma-separated (case-insensitive, substring match): ");
        java.util.Scanner sc = new java.util.Scanner(System.in, java.nio.charset.StandardCharsets.UTF_8);
        String raw = sc.nextLine().trim();
        sc.close();

        // JDK 11: replace .toList() with collect(toList())
        java.util.List<String> queries =
                java.util.Arrays.stream(raw.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .map(String::toLowerCase)
                        .collect(Collectors.toList());

        if (queries.isEmpty()) {
            throw new RuntimeException("No organization names entered.");
        }

        java.util.List<java.util.Map<String, Object>> selected = new java.util.ArrayList<>();
        for (java.util.Map<String, Object> o : orgs) {
            String name = String.valueOf(o.getOrDefault("organization", ""));
            String lower = name.toLowerCase();
            System.out.println("Checking organization: " + name + " against queries: " + queries);
            boolean match = queries.stream().anyMatch(lower::contains);
            if (match) {
                selected.add(mapOf("id", o.get("id"), "organization", name));
            }
        }

        // JDK 11: replace .toList() with collect(toList())
        java.util.Set<String> seenIds = new java.util.HashSet<>();
        selected = selected.stream().filter(s -> {
            String id = String.valueOf(s.get("id"));
            if (seenIds.contains(id)) return false;
            seenIds.add(id);
            return true;
        }).collect(Collectors.toList()); // JDK 11

        if (selected.isEmpty()) {
            throw new RuntimeException("No organizations matched your input. Please try again with a different name.");
        }

        System.out.println("\nMatched organizations:");
        for (var s : selected) {
            System.out.println(" - " + s.get("id") + ": " + s.get("organization"));
        }

        this.selected = selected;

        java.util.List<java.util.Map<String, Object>> allDevices = new java.util.ArrayList<>();

        System.out.println("\nStep 2: Fetching device list for selected orgs...");
        for (var s : selected) {
            int oid = toInt(s.get("id"));
            String oname = String.valueOf(s.get("organization"));
            try {
                java.util.List<java.util.Map<String, Object>> devs = fetchDevicesForOrg(oid, 1000);
                for (java.util.Map<String, Object> d : devs) {
                    d.put("orgId", oid);
                    d.put("orgName", oname);
                }
                allDevices.addAll(devs);

                String fname = "devices_" + oid + "_" + safeSlug(oname) + ".json";
                System.out.println("✅ Org " + oid + " (" + oname + "): devices=" + devs.size() + " | 💾 " + fname);
            } catch (Exception e) {
                System.out.println("❌ Org " + oid + ": device list error → " + e.getMessage());
            }
        }

        writeJson("devices_by_org.selected.json", mapOf("data", allDevices));
        //System.out.println("💾 Saved → devices_by_org.selected.json");

        fetchDeviceAccountStatusForSelectedOrgs(allDevices, selected);
    }

    /** Step 4: SIP account list for an org (paged). */
    public List<Map<String,Object>> fetchSIPAccountsForOrg(int orgId, int pageSize) {
        String url = "https://" + gdmsDomain + "/oapi/" + DEFAULT_API_VERSION + "/sip/account/list";
        List<Map<String,Object>> out = new ArrayList<>();
        Map<String,Object> body = new LinkedHashMap<>();
        body.put("order", "");
        body.put("pageNum", 1);
        body.put("pageSize", pageSize);
        body.put("orgId", orgId);

        Map<String,Object> page1 = postJsonSigned(url, body);
        Map<String,Object> data = castMap(page1.get("data"));
        out.addAll(castListMap(data.get("result")));

        int pages = toInt(data.get("pages"), 1);
        for (int p = 2; p <= pages; p++) {
            body.put("pageNum", p);
            Map<String,Object> nextPage = postJsonSigned(url, body);
            Map<String,Object> nextData = castMap(nextPage.get("data"));
            out.addAll(castListMap(nextData.get("result")));
        }

        return out;
    }

    private Map<String, Object> mergeDeviceAndStatus(Map<String, Object> device, Map<String, Object> statusResponse, int orgId, String orgName) {
        Map<String, Object> merged = new LinkedHashMap<>(device);
        merged.put("orgId", orgId);
        merged.put("orgName", orgName);

        Object dataObj = statusResponse.get("data");
        if (dataObj instanceof Map) {
            Map<?, ?> statusData = (Map<?, ?>) dataObj;
            merged.put("accountStatus", statusData.get("accountStatus"));
            merged.put("dnd", statusData.get("dnd"));
            merged.put("sipAccountInfoList", statusData.get("sipAccountInfoList"));
            merged.put("syncFailureMsg", statusData.get("syncFailureMsg"));
        }

        return merged;
    }

    private static String prettyJson(String json) {
        if (json == null || json.isBlank()) return json;
        StringBuilder out = new StringBuilder(json.length() + 128);
        int indent = 0;
        boolean inString = false;
        boolean escape = false;

        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);

            if (inString) {
                out.append(c);
                if (escape) {
                    escape = false;
                } else if (c == '\\') {
                    escape = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }

            switch (c) {
                case ' ':
                case '\t':
                case '\r':
                case '\n':
                    break;
                case '"':
                    inString = true;
                    out.append(c);
                    break;
                case '{':
                case '[':
                    out.append(c).append('\n');
                    indent++;
                    appendIndent(out, indent);
                    break;
                case '}':
                case ']':
                    out.append('\n');
                    indent = Math.max(0, indent - 1);
                    appendIndent(out, indent);
                    out.append(c);
                    break;
                case ',':
                    out.append(c).append('\n');
                    appendIndent(out, indent);
                    break;
                case ':':
                    out.append(c).append(' ');
                    break;
                default:
                    out.append(c);
            }
        }
        return out.toString();
    }

    private static void appendIndent(StringBuilder sb, int indent) {
        for (int i = 0; i < indent; i++) sb.append("  ");
    }

    public Map<String, Object> statusPayload = new LinkedHashMap<>();
    public void fetchDeviceAccountStatusForSelectedOrgs(
            List<Map<String, Object>> allDevices,
            List<Map<String, Object>> selected
    ) {
        List<Map<String, Object>> statusSuccessAll = new ArrayList<>();
        List<Map<String, Object>> statusFailuresAll = new ArrayList<>();

        if (selected == null || selected.isEmpty()) {
            System.out.println("No organizations selected. Skipping device account status fetch.");
            return;
        }

        System.out.println("\nStep 3: Fetching device account status for selected orgs...");

        Map<Integer, List<Map<String, Object>>> devicesByOrg = new HashMap<>();
        for (Map<String, Object> device : allDevices) {
            int orgId = toInt(device.get("orgId"));
            devicesByOrg.computeIfAbsent(orgId, k -> new ArrayList<>()).add(device);
        }
        ExecutorService executor = Executors.newFixedThreadPool(20);
        List<Future<Void>> futures = new ArrayList<>();
        for (Map<String, Object> s : selected) {
            int oid = toInt(s.get("id"));
            String oname = String.valueOf(s.get("organization"));
            List<Map<String, Object>> devices = devicesByOrg.get(oid);
            System.out.println("\nDevices for Org " + oid + " (" + oname + "): " + devices);
            if (devices == null || devices.isEmpty()) {
                System.out.println("No devices found for Org " + oid + " (" + oname + ")");
                continue;
            }
            System.out.println("\n=== Org " + oid + " (" + oname + ") — devices: " + devices.size() + " ===");
            for (Map<String, Object> device : devices) {
                futures.add(executor.submit(() -> {
                    String mac = String.valueOf(device.get("mac"));
                    if (mac == null || mac.isEmpty()) return null;
                    try {
                        System.out.println("Fetching account status for MAC: " + mac);
                        Map<String, Object> statusResponse = getDeviceAccountStatus(mac);
                        System.out.println("Status Response: " + statusResponse);

                        if (statusResponse != null && statusResponse.containsKey("data")) {
                            Map<String, Object> mergedDevice = mergeDeviceAndStatus(device, statusResponse, oid, oname);

                            synchronized (statusSuccessAll) {
                                statusSuccessAll.add(mergedDevice);
                            }

                            System.out.println("✅ " + mac + " | " + device.get("deviceName"));
                        } else {
                            System.out.println("No status data for MAC: " + mac);
                            device.put("error", "No status data");

                            synchronized (statusFailuresAll) {
                                statusFailuresAll.add(device);
                            }
                        }

                    } catch (Exception e) {
                        System.out.println("❌ " + mac + " | " + e.getMessage());
                        device.put("error", e.getMessage());
                        synchronized (statusFailuresAll) {
                            statusFailuresAll.add(device);
                        }
                    }

                    return null;
                }));
            }
        }
        try {
            for (Future<Void> future : futures) {
                future.get();
            }
        } catch (InterruptedException | ExecutionException e) {
            e.printStackTrace();
        }
        statusPayload.put("success", statusSuccessAll);
        statusPayload.put("failures", statusFailuresAll);
        statusPayload.put("meta", mapOf(
                "total", statusSuccessAll.size() + statusFailuresAll.size(),
                "success", statusSuccessAll.size(),
                "failures", statusFailuresAll.size()
        ));
        writeJson("status_by_org.all_devices.json", statusPayload);
        System.out.println("💾 Saved → status_by_org.all_devices.json");
        executor.shutdown();
    }
    java.util.List<Map<String, Object>> allSIPAccounts = new java.util.ArrayList<>();
    public void fetchSIPAccountListForSelectedOrgs() {
        System.out.println("\nStep 3: Fetching SIP account list for selected orgs...");
        for (var s : selected) {
            int oid = toInt(s.get("id"));
            String oname = String.valueOf(s.get("organization"));
            try {
                java.util.List<Map<String, Object>> sipAccounts = fetchSIPAccountsForOrg(oid, 1000);

                for (Map<String, Object> sipAccount : sipAccounts) {
                    sipAccount.put("orgName", oname);
                }

                allSIPAccounts.addAll(sipAccounts);

                String fname = "sip_accounts_" + oid + "_" + safeSlug(oname) + ".json";
                System.out.println("Org " + oid + " (" + oname + "): SIP Accounts=" + sipAccounts.size() + " | 💾 " + fname);
            } catch (Exception e) {
                System.out.println("Org " + oid + ": SIP account list error → " + e.getMessage());
            }
        }
        writeJson("sip_accounts_by_org.selected.json", mapOf("data", allSIPAccounts));
        System.out.println("Saved → sip_accounts_by_org.selected.json");
        mapSIPAccountsToDevices(statusPayload, allSIPAccounts);
    }
    public static Map<String, Object> mapSIPAccountsToDevices(
            Map<String, Object> statusPayload,
            List<Map<String, Object>> allSIPAccounts
    ) {
        List<Map<String, Object>> resultData = new ArrayList<>();

        // Get successful device statuses
        Object successObj = statusPayload.get("success");
        if (!(successObj instanceof List<?>)) successObj = new ArrayList<>();
        List<?> successList = (List<?>) successObj;

        Map<String, List<Map<String, Object>>> deviceMap = new HashMap<>();
        for (Object devObj : successList) {
            if (!(devObj instanceof Map)) continue;
            Map<?, ?> device = (Map<?, ?>) devObj;
            Integer orgId = toInt(device.get("orgId"));

            Object sipAccountsObj = device.get("sipAccountInfoList");
            if (!(sipAccountsObj instanceof List<?>)) continue;

            List<?> sipAccounts = (List<?>) sipAccountsObj;
            for (Object sipInfoObj : sipAccounts) {
                if (!(sipInfoObj instanceof Map)) continue;
                Map<?, ?> sipInfo = (Map<?, ?>) sipInfoObj;

                String sipUserId = String.valueOf(sipInfo.get("sipUserId"));
                String key = sipUserId + "_" + orgId;

                Map<String, Object> deviceWithSipInfo = new LinkedHashMap<>();
                for (Map.Entry<?, ?> e : device.entrySet())
                    deviceWithSipInfo.put(String.valueOf(e.getKey()), e.getValue());
                for (Map.Entry<?, ?> e : sipInfo.entrySet())
                    deviceWithSipInfo.put(String.valueOf(e.getKey()), e.getValue());

                deviceMap.computeIfAbsent(key, k -> new ArrayList<>()).add(deviceWithSipInfo);
            }
        }

        // Now merge SIP accounts
        for (Map<String, Object> sipAccount : allSIPAccounts) {
            String sipUserId = String.valueOf(sipAccount.get("sipUserId"));
            String orgId = String.valueOf(sipAccount.get("orgId"));
            String key = sipUserId + "_" + orgId;

            Map<String, Object> mappedSIPAccount = new LinkedHashMap<>();
            mappedSIPAccount.put("id", sipAccount.get("id"));
            mappedSIPAccount.put("orgId", orgId);
            mappedSIPAccount.put("sipUserId", sipUserId);
            mappedSIPAccount.put("accountName", sipAccount.get("accountName"));
            mappedSIPAccount.put("displayName", sipAccount.get("displayName"));
            mappedSIPAccount.put("serverName", sipAccount.get("serverName"));
            mappedSIPAccount.put("sipServer", sipAccount.get("sipServer"));
            mappedSIPAccount.put("status", sipAccount.get("status"));
            mappedSIPAccount.put("modifyTime", sipAccount.get("modifyTime"));
            mappedSIPAccount.put("source", sipAccount.get("source"));
            mappedSIPAccount.put("extensionEmail", sipAccount.get("extensionEmail"));
            mappedSIPAccount.put("orgName", sipAccount.get("orgName"));

            // attach device info
            List<Map<String, Object>> deviceInfoList = deviceMap.getOrDefault(key, new ArrayList<>());
            mappedSIPAccount.put("deviceInfoList", deviceInfoList);

            // ✅ Flatten MAC so frontend can use acc.get("mac")
            String mac = "—";
            if (!deviceInfoList.isEmpty()) {
                Object firstDev = deviceInfoList.get(0);
                if (firstDev instanceof Map && ((Map<?, ?>) firstDev).get("mac") != null) {
                    mac = String.valueOf(((Map<?, ?>) firstDev).get("mac"));
                }
            }
            mappedSIPAccount.put("mac", mac);

            resultData.add(mappedSIPAccount);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("data", resultData);
        writeJson("sip_accounts_with_devices.json", result);
        return result;
    }


    public static void main(String[] args) throws Exception {
        GDMSAPI client = new GDMSAPI(
                "www.gdms.cloud",
                "product",
                "admin@1234",
                "102993",
                "snp2mDxueVyTC6k6bUC67TqtVeNx9MLg",
                null,
                120,
                20,
                true
        );
        client.startRefreshLoop(20, 120);
        client.fetchDevicesForSelectedOrgsInteractive();
        client.fetchSIPAccountListForSelectedOrgs();
        client.stopRefreshLoop();
    }
}
