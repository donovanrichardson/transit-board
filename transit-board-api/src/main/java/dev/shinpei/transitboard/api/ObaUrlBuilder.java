package dev.shinpei.transitboard.api;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public class ObaUrlBuilder {

    private static final Pattern ALLOWLIST = Pattern.compile("^[A-Za-z0-9_.:-]+$");

    private final String baseUrl;
    private final String apiKey;
    private final List<String> segments = new ArrayList<>();
    private final Map<String, String> queryParams = new LinkedHashMap<>();

    public ObaUrlBuilder(String baseUrl, String apiKey) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
    }

    public ObaUrlBuilder path(String segment) {
        if (segment == null || segment.isEmpty()) {
            throw new IllegalArgumentException("Path segment must not be null or empty");
        }
        if (!ALLOWLIST.matcher(segment).matches()) {
            throw new IllegalArgumentException(
                    "Path segment contains characters outside the allowlist");
        }
        segments.add(encode(segment));
        return this;
    }

    public ObaUrlBuilder query(String name, String value) {
        queryParams.put(encode(name), encode(value));
        return this;
    }

    public String build() {
        StringBuilder sb = new StringBuilder(baseUrl);
        for (int i = 0; i < segments.size(); i++) {
            sb.append('/').append(segments.get(i));
            if (i == segments.size() - 1) {
                sb.append(".json");
            }
        }
        sb.append("?key=").append(encode(apiKey));
        for (Map.Entry<String, String> entry : queryParams.entrySet()) {
            sb.append('&').append(entry.getKey()).append('=').append(entry.getValue());
        }
        return sb.toString();
    }

    static String encode(String raw) {
        return URLEncoder.encode(raw, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
