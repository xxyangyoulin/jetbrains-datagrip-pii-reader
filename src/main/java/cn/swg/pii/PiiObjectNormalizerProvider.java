package cn.swg.pii;

import com.intellij.database.console.AbstractEngine;
import com.intellij.database.datagrid.*;
import com.intellij.database.extractors.ObjectFormatter;
import com.intellij.ide.passwordSafe.PasswordSafe;
import com.intellij.util.Function;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class PiiObjectNormalizerProvider implements ObjectNormalizerProvider {
    private final DatabaseObjectNormalizerProvider delegate = new DatabaseObjectNormalizerProvider();
    @Override public boolean isApplicable(GridDataRequest.Context context) { return delegate.isApplicable(context); }
    @Override public ObjectFormatter getCoupledFormatter() { return delegate.getCoupledFormatter(); }
    @Override public Function<GridDataRequest.Context, ObjectNormalizer> createCache() {
        Function<GridDataRequest.Context, ObjectNormalizer> normalizers = delegate.createCache();
        return context -> {
            ObjectNormalizer normalizer = normalizers.fun(context);
            if (!(context instanceof DataRequest.Context database)) return normalizer;
            String id = NativeQueryRegistry.marker(database.getQuery());
            if (id == null) return normalizer;
            if (!(database.producer instanceof AbstractEngine engine))
                throw new IllegalStateException("无法获取 PII 查询会话。");
            NativeQueryRegistry.Query query = NativeQueryRegistry.get(engine.getProject()).require(id);
            var saved = PasswordSafe.getInstance().get(query.credentials());
            if (saved == null || saved.getPasswordAsString() == null)
                throw new IllegalStateException("Workbench 凭证已失效，请重新执行 PII 查询并填写 token。");
            Map<String, WorkbenchClient> clients = new ConcurrentHashMap<>();
            return (value, column) -> {
                Object normalized = normalizer.objectToObject(value, column);
                String scope = query.scopes().get(column.getName());
                if (scope == null || normalized == null) return normalized;
                if (!(normalized instanceof String text))
                    throw new IllegalStateException("解密列必须为字符串，请检查字段类型。");
                WorkbenchClient client = clients.computeIfAbsent(query.endpointFor(column.getName()),
                    endpoint -> new WorkbenchClient(endpoint, saved.getPasswordAsString()));
                try { return client.decrypt(scope, Collections.singletonList(text)).get(0); }
                catch (InterruptedException error) {
                    Thread.currentThread().interrupt(); throw new IllegalStateException("PII 解密已取消。");
                } catch (WorkbenchClient.ServiceException error) {
                    throw new IllegalStateException(error.getMessage());
                } catch (java.io.IOException error) {
                    throw new IllegalStateException("Workbench 解密请求失败，请检查网络及服务状态。");
                }
            };
        };
    }
}
