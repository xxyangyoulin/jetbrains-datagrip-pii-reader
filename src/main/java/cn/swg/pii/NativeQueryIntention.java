package cn.swg.pii;

import com.intellij.codeInsight.intention.PsiElementBaseIntentionAction;
import com.intellij.codeInsight.intention.HighPriorityAction;
import com.intellij.database.console.JdbcConsoleProvider;
import com.intellij.database.script.ScriptModelUtil;
import com.intellij.database.settings.DatabaseSettings;
import com.intellij.ide.DataManager;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.actionSystem.impl.SimpleDataContext;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;

public final class NativeQueryIntention extends PsiElementBaseIntentionAction implements HighPriorityAction {
    @Override public @NotNull String getText() { return "Run Query in Console (PII)"; }
    @Override public @NotNull String getFamilyName() { return "Workbench PII Query"; }
    static boolean applies(Project project, Editor editor, PsiFile file) {
        if (editor == null || file == null || file.getVirtualFile() == null) return false;
        var console = JdbcConsoleProvider.getConsole(project, file.getVirtualFile());
        if (console == null) return false;
        String selected;
        if (editor.getSelectionModel().hasSelection()) selected = editor.getSelectionModel().getSelectedText();
        else {
            var range = ScriptModelUtil.getSelectedStatementsRange(console, editor, DatabaseSettings.getDefaultExecOption());
            selected = range.substring(editor.getDocument().getText());
        }
        return VirtualFunction.present(selected);
    }
    @Override public boolean isAvailable(@NotNull Project project, Editor editor, @NotNull PsiElement element) {
        return applies(project, editor, element.getContainingFile());
    }
    @Override public void invoke(@NotNull Project project, Editor editor, @NotNull PsiElement element) {
        var action = ActionManager.getInstance().getAction("Console.Jdbc.Execute");
        DataContext parent = DataManager.getInstance().getDataContext(editor.getContentComponent());
        DataContext context = SimpleDataContext.builder().setParent(parent)
            .add(CommonDataKeys.PROJECT, project).add(CommonDataKeys.EDITOR, editor)
            .add(CommonDataKeys.PSI_FILE, element.getContainingFile())
            .add(CommonDataKeys.VIRTUAL_FILE, element.getContainingFile().getVirtualFile()).build();
        action.actionPerformed(AnActionEvent.createEvent(action, context, null, "WorkbenchPiiIntention", ActionUiKind.NONE, null));
    }
    @Override public boolean startInWriteAction() { return false; }
}
