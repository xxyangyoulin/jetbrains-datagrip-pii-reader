package cn.swg.pii;

import com.intellij.database.actions.ExplainActionBase;
import com.intellij.database.console.JdbcConsole;
import com.intellij.database.console.JdbcConsoleProvider;
import com.intellij.database.script.ScriptModel;
import com.intellij.openapi.ui.Messages;

public final class NativeExplainActions {
    public static final class Plan extends ExplainActionBase.Ui.Plan {
        @Override protected void invokeImpl(JdbcConsole console, ScriptModel<?> model, JdbcConsoleProvider.Info info) {
            try {
                ScriptModel<?> transformed = NativeExecution.transform(console, model, true);
                if (transformed == null) return;
                super.invokeImpl(console, transformed, info);
            } catch (IllegalArgumentException error) {
                Messages.showErrorDialog(info.file.getProject(), error.getMessage(), "PII Explain");
            }
        }
    }
    public static final class Analyse extends ExplainActionBase.Ui.Analyse {
        @Override protected void invokeImpl(JdbcConsole console, ScriptModel<?> model, JdbcConsoleProvider.Info info) {
            try {
                ScriptModel<?> transformed = NativeExecution.transform(console, model, true);
                if (transformed == null) return;
                super.invokeImpl(console, transformed, info);
            } catch (IllegalArgumentException error) {
                Messages.showErrorDialog(info.file.getProject(), error.getMessage(), "PII Explain");
            }
        }
    }
    public static final class RawPlan extends ExplainActionBase.Raw.Plan {
        @Override protected void invokeImpl(JdbcConsole console, ScriptModel<?> model, JdbcConsoleProvider.Info info) {
            try {
                ScriptModel<?> transformed = NativeExecution.transform(console, model, true);
                if (transformed == null) return;
                super.invokeImpl(console, transformed, info);
            } catch (IllegalArgumentException error) {
                Messages.showErrorDialog(info.file.getProject(), error.getMessage(), "PII Explain");
            }
        }
    }
    public static final class RawAnalyse extends ExplainActionBase.Raw.Analyse {
        @Override protected void invokeImpl(JdbcConsole console, ScriptModel<?> model, JdbcConsoleProvider.Info info) {
            try {
                ScriptModel<?> transformed = NativeExecution.transform(console, model, true);
                if (transformed == null) return;
                super.invokeImpl(console, transformed, info);
            } catch (IllegalArgumentException error) {
                Messages.showErrorDialog(info.file.getProject(), error.getMessage(), "PII Explain");
            }
        }
    }
}
