package cn.swg.pii;

import com.intellij.database.console.JdbcConsole;
import com.intellij.database.console.JdbcConsoleProvider;
import com.intellij.database.script.ScriptModel;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.ui.Messages;
import java.util.*;
import java.util.function.Consumer;

final class NativeExecution {
    static boolean containsPii(ScriptModel<?> model) {
        for (var statement : model.statements()) if (VirtualFunction.present(statement.query())) return true;
        return false;
    }

    static void run(AnActionEvent event, JdbcConsole console, JdbcConsoleProvider.Info info, Consumer<JdbcConsoleProvider.Info> proceed) {
        if (!containsPii(info.model)) { proceed.accept(info); return; }
        // Let DataGrip resolve caret/selection/statement choices before transforming anything.
        JdbcConsoleProvider.chooseStatements(info, "请选择要执行的 SQL", true, selected -> {
            try {
                ScriptModel<?> transformed = transform(console, selected, false);
                if (transformed == null) return;
                proceed.accept(new JdbcConsoleProvider.Info(info.originalFile, info.file, info.editor,
                    transformed, info.execOption, info.ranger));
            } catch (IllegalArgumentException error) {
                Messages.showErrorDialog(event.getProject(), error.getMessage(), "PII 查询");
            }
        });
    }

    static ScriptModel<?> transform(JdbcConsole console, ScriptModel<?> model, boolean explain) {
        Map<String, SqlPlan> plans = new LinkedHashMap<>();
        for (var statement : model.statements()) {
            String sql = statement.query();
            if (VirtualFunction.present(sql)) plans.put(sql, SqlPlan.nativePlan(sql));
        }
        if (plans.isEmpty()) return model;
        boolean lookup = plans.values().stream().anyMatch(SqlPlan::hasLookups);
        boolean needsConfiguration = !explain || lookup;
        if (console == null && needsConfiguration) throw new IllegalArgumentException("请先将 SQL 控制台绑定到数据源。");
        ExecuteAction.Configuration configuration = !needsConfiguration ? null : ExecuteAction.configure(console.getProject(), console);
        if (needsConfiguration && configuration == null) return null;
        if (lookup) {
            java.util.concurrent.atomic.AtomicReference<String> failure = new java.util.concurrent.atomic.AtomicReference<>();
            boolean completed = com.intellij.openapi.progress.ProgressManager.getInstance().runProcessWithProgressSynchronously(() -> {
                Map<String, WorkbenchClient> clients = new HashMap<>();
                try {
                    var saved = com.intellij.ide.passwordSafe.PasswordSafe.getInstance().get(configuration.credentials());
                    if (saved == null || saved.getPasswordAsString() == null)
                        throw new IllegalArgumentException("Workbench 凭证已失效，请重新填写 token。");
                    for (SqlPlan plan : plans.values()) {
                        if (!plan.hasLookups()) continue;
                        plan.resolveLookups((project, scope, values) -> {
                            com.intellij.openapi.progress.ProgressManager.checkCanceled();
                            WorkbenchClient current = clients.computeIfAbsent(project, key ->
                                new WorkbenchClient(WorkbenchClient.endpointFor(configuration.endpoint(), key), saved.getPasswordAsString()));
                            return current.lookup(scope, values);
                        });
                    }
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    failure.set("PII 查询指纹请求已取消。");
                } catch (WorkbenchClient.ServiceException | IllegalArgumentException error) {
                    failure.set(error.getMessage());
                } catch (java.io.IOException error) {
                    failure.set("Workbench 查询指纹请求失败，请检查网络、接口部署和密钥绑定。");
                } finally {
                    clients.values().forEach(WorkbenchClient::clearToken);
                }
            }, "正在计算 PII 查询指纹", true, console.getProject());
            if (!completed) return null;
            if (failure.get() != null) throw new IllegalArgumentException(failure.get());
        }
        Map<String, String> replacements = new LinkedHashMap<>();
        for (var entry : plans.entrySet()) {
            SqlPlan plan = entry.getValue();
            String id = explain || plan.scopes.isEmpty() ? null : NativeQueryRegistry.get(console.getProject()).register(plan, configuration.endpoint(), configuration.credentials());
            replacements.put(entry.getKey(), plan.nativeSql(id));
        }
        return new NativeScriptModel<>(model, replacements);
    }
}
