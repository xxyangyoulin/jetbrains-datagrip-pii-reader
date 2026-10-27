package cn.swg.pii;

import com.intellij.database.actions.RunQueryAction;
import com.intellij.database.console.JdbcConsole;
import com.intellij.database.console.JdbcConsoleProvider;
import com.intellij.openapi.actionSystem.AnActionEvent;
import org.jetbrains.annotations.NotNull;

public final class NativeExecuteAction extends RunQueryAction.Alt1 {
    @Override protected void invokeImpl(@NotNull AnActionEvent event, JdbcConsole console, @NotNull JdbcConsoleProvider.Info info) {
        NativeExecution.run(event, console, info, adapted -> super.invokeImpl(event, console, adapted));
    }
    public static final class Alt2 extends RunQueryAction.Alt2 {
        @Override protected void invokeImpl(@NotNull AnActionEvent event, JdbcConsole console, @NotNull JdbcConsoleProvider.Info info) {
            NativeExecution.run(event, console, info, adapted -> super.invokeImpl(event, console, adapted));
        }
    }
    public static final class Alt3 extends RunQueryAction.Alt3 {
        @Override protected void invokeImpl(@NotNull AnActionEvent event, JdbcConsole console, @NotNull JdbcConsoleProvider.Info info) {
            NativeExecution.run(event, console, info, adapted -> super.invokeImpl(event, console, adapted));
        }
    }
    public static final class Selection extends RunQueryAction.RunSelectionExactlyAsOneStatement {
        @Override protected void invokeImpl(@NotNull AnActionEvent event, JdbcConsole console, @NotNull JdbcConsoleProvider.Info info) {
            NativeExecution.run(event, console, info, adapted -> super.invokeImpl(event, console, adapted));
        }
    }
}
