package cn.swg.pii;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.components.StoragePathMacros;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import com.intellij.credentialStore.CredentialAttributes;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

@Service(Service.Level.PROJECT)
@State(name = "WorkbenchPiiQueries", storages = @Storage(StoragePathMacros.WORKSPACE_FILE))
public final class NativeQueryRegistry implements PersistentStateComponent<NativeQueryRegistry.SavedState> {
    private static final Pattern MARKER = Pattern.compile("/\\*workbench-pii:([a-f0-9-]{36})\\*/");
    record Query(String endpoint, CredentialAttributes credentials, Map<String, String> scopes, Map<String, String> endpoints) {
        String endpointFor(String label) { return endpoints.getOrDefault(label, endpoint); }
    }
    public static final class StoredQuery {
        public String endpoint;
        public String credentialService;
        public Map<String, String> scopes = new HashMap<>();
        public Map<String, String> endpoints = new HashMap<>();
    }
    public static final class SavedState {
        public Map<String, StoredQuery> queries = new ConcurrentHashMap<>();
    }
    private SavedState state = new SavedState();
    static NativeQueryRegistry get(Project project) { return project.getService(NativeQueryRegistry.class); }
    String register(SqlPlan plan, String endpoint, CredentialAttributes credentials) {
        String id = UUID.randomUUID().toString();
        Map<String, String> scopes = new HashMap<>();
        plan.scopes.forEach((index, scope) -> scopes.put(plan.labels.get(index), scope));
        StoredQuery stored = new StoredQuery();
        stored.endpoint = endpoint;
        stored.credentialService = credentials.getServiceName();
        stored.scopes = Map.copyOf(scopes);
        plan.projects.forEach((index, project) -> stored.endpoints.put(plan.labels.get(index), WorkbenchClient.endpointFor(endpoint, project)));
        state.queries.put(id, stored);
        return id;
    }
    static String marker(String sql) {
        if (sql == null) return null;
        for (int i = 0; i < sql.length();) {
            char c = sql.charAt(i);
            if (c == '\'' || c == '"' || c == '`') {
                char quote = c; i++;
                while (i < sql.length()) {
                    c = sql.charAt(i++);
                    if (c == '\\' && i < sql.length()) { i++; continue; }
                    if (c == quote) {
                        if (i < sql.length() && sql.charAt(i) == quote) i++;
                        else break;
                    }
                }
            } else if (c == '#' || (sql.startsWith("--", i) && i + 2 < sql.length() && Character.isWhitespace(sql.charAt(i + 2)))) {
                while (i < sql.length() && sql.charAt(i) != '\n') i++;
            } else if (sql.startsWith("/*", i)) {
                int end = sql.indexOf("*/", i + 2);
                if (end < 0) return null;
                var matcher = MARKER.matcher(sql.substring(i, end + 2));
                if (matcher.matches()) return matcher.group(1);
                i = end + 2;
            } else i++;
        }
        return null;
    }

    Query require(String id) {
        StoredQuery query = state.queries.get(id);
        if (query == null) throw new IllegalStateException("PII 查询上下文已失效，请重新执行原始 decr 查询。");
        return new Query(query.endpoint, new CredentialAttributes(query.credentialService, "user-token"), Map.copyOf(query.scopes), Map.copyOf(query.endpoints));
    }
    @Override public SavedState getState() { return state; }
    @Override public void loadState(SavedState saved) {
        SavedState restored = new SavedState();
        restored.queries.putAll(saved.queries);
        state = restored;
    }
}
