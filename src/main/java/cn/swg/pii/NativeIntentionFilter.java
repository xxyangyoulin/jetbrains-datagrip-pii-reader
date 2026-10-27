package cn.swg.pii;

import com.intellij.codeInsight.daemon.impl.IntentionActionFilter;
import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeInsight.intention.impl.config.IntentionActionWrapper;
import com.intellij.database.intentions.RunQueryInConsoleIntentionAction;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;

public final class NativeIntentionFilter implements IntentionActionFilter {
    @Override public boolean accept(@NotNull IntentionAction action, PsiFile file, int offset) {
        if (file == null) return true;
        boolean original = action instanceof RunQueryInConsoleIntentionAction
            || action instanceof IntentionActionWrapper wrapper
                && wrapper.getImplementationClassName().equals(RunQueryInConsoleIntentionAction.class.getName());
        if (!original) return true;
        var editor = FileEditorManager.getInstance(file.getProject()).getSelectedTextEditor();
        if (editor == null || PsiDocumentManager.getInstance(file.getProject()).getPsiFile(editor.getDocument()) != file) return true;
        return !NativeQueryIntention.applies(file.getProject(), editor, file);
    }
}
