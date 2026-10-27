package cn.swg.pii;

import com.intellij.psi.PsiElement;
import com.intellij.sql.inspections.suppression.SqlInspectionSuppressorDelegate;
import com.intellij.sql.psi.SqlFunctionCallExpression;
import com.intellij.sql.psi.SqlReferenceExpression;
import org.jetbrains.annotations.NotNull;

public final class PiiInspectionSuppressor implements SqlInspectionSuppressorDelegate {
    @Override public boolean isSuppressedFor(@NotNull PsiElement element, @NotNull String toolId) {
        if (!"SqlResolve".equals(toolId) && !"SqlResolveInspection".equals(toolId)) return false;
        PsiElement candidate = element instanceof SqlReferenceExpression ? element : element.getParent();
        if (!(candidate instanceof SqlReferenceExpression reference)) return false;
        return VirtualFunction.isVirtualName(reference.getName())
            && !reference.isQuotedIdentifier()
            && reference.getQualifierExpression() == null
            && reference.getParent() instanceof SqlFunctionCallExpression call
            && call.getNameElement() == reference;
    }
}
