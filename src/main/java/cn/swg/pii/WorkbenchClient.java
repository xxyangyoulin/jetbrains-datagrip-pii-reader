package cn.swg.pii;

import com.google.gson.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

public final class WorkbenchClient {
    public static final class ServiceException extends IOException {
        ServiceException(String message) { super(message); }
    }
    private final URI endpoint;
    private String token;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NEVER).build();

    public WorkbenchClient(String endpoint, String token) {
        this.endpoint = validateEndpoint(endpoint);
        if (token.isBlank() || token.contains("\r") || token.contains("\n")) throw new IllegalArgumentException("请输入有效的 Workbench user-token。");
        this.token = token;
    }
    static String endpointFor(String endpoint, String project) {
        URI uri = validateEndpoint(endpoint);
        String module = switch (project) {
            case "jsst" -> "jsst";
            case "erp" -> "business";
            default -> throw new IllegalArgumentException("不支持的隐私项目。");
        };
        String path = uri.getRawPath();
        String suffix = "/privacy_decrypt_batch";
        String base = path.substring(0, path.length() - suffix.length());
        int slash = base.lastIndexOf('/');
        String existing = base.substring(slash + 1);
        if (!existing.equals("jsst") && !existing.equals("business"))
            throw new IllegalArgumentException("接口地址必须以 /jsst/privacy_decrypt_batch 或 /business/privacy_decrypt_batch 结尾。");
        return uri.getScheme() + "://" + uri.getRawAuthority() + base.substring(0, slash + 1) + module + suffix;
    }
    public void clearToken() { token = ""; }
    public static URI validateEndpoint(String url) {
        URI uri;
        try { uri = URI.create(url.trim()); } catch (IllegalArgumentException e) { throw new IllegalArgumentException("接口地址格式错误。"); }
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
            || !("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
            || !uri.getPath().endsWith("/privacy_decrypt_batch"))
            throw new IllegalArgumentException("请填写 HTTP 或 HTTPS 批量解密接口完整地址，以 /privacy_decrypt_batch 结尾。");
        return uri;
    }
    public List<String> decrypt(String scope, List<String> values) throws IOException, InterruptedException {
        return request(endpoint, scope, values);
    }
    public List<String> lookup(String scope, List<String> values) throws IOException, InterruptedException {
        return request(endpoint.resolve("privacy_lookup_batch"), scope, values);
    }
    private List<String> request(URI target, String scope, List<String> values) throws IOException, InterruptedException {
        if (values.isEmpty() || values.size() > 200) throw new IllegalArgumentException("每批需为1至200项。");
        JsonObject payload = new JsonObject(); payload.addProperty("scope", scope);
        JsonArray items = new JsonArray();
        for (String value : values) {
            if (value != null && value.getBytes(StandardCharsets.UTF_8).length > 4096)
                throw new IllegalArgumentException("待解密字段超过4096字节。");
            items.add(value);
        }
        payload.add("values", items);
        HttpRequest request = HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(45))
            .header("Content-Type", "application/json; charset=utf-8").header("user-token", token)
            .header("view-privacy", "1").header("Cache-Control", "no-store")
            .POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8)).build();
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream body = response.body()) {
            if (response.statusCode() != 200) throw new ServiceException("Workbench HTTP " + response.statusCode() + "，请检查接口地址、登录凭证及访问权限。");
            byte[] bytes = body.readNBytes(2 * 1024 * 1024 + 1);
            if (bytes.length > 2 * 1024 * 1024) throw new ServiceException("Workbench 响应超过大小限制。");
            try {
                JsonObject json = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
                int code = json.get("code").getAsInt();
                if (code != 2000) throw new ServiceException("Workbench 拒绝请求（code=" + code + "），请检查登录、工具权限和隐私权限。");
                JsonArray results = json.getAsJsonObject("data").getAsJsonArray("results");
                if (results.size() != values.size()) throw new ServiceException("Workbench 返回的条数不匹配。");
                List<String> decoded = new ArrayList<>();
                for (JsonElement item : results) {
                    JsonObject result = item.getAsJsonObject();
                    if (!result.get("ok").getAsBoolean()) throw new ServiceException("有字段解密失败，本次结果未展示或导出。请核对环境、字段用途和密钥状态。");
                    JsonElement value = result.get("value");
                    if (value == null || (!value.isJsonNull() && (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())))
                        throw new ServiceException("Workbench 返回值格式错误。");
                    decoded.add(value.isJsonNull() ? null : value.getAsString());
                }
                return decoded;
            } catch (JsonParseException | IllegalStateException | NullPointerException | NumberFormatException e) {
                throw new ServiceException("Workbench 返回格式错误；请确认连接的是批量解密接口。");
            }
        }
    }
}
