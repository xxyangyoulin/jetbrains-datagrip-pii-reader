package cn.swg.pii;

import com.intellij.database.script.ScriptModel;
import com.intellij.database.script.translator.TranslateException;
import com.intellij.lang.Language;
import com.intellij.openapi.util.Condition;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.SyntaxTraverser;
import com.intellij.psi.tree.IElementType;
import com.intellij.util.Function;
import com.intellij.util.containers.JBIterable;
import java.util.Map;

final class NativeScriptModel<E> extends ScriptModel<E> {
    private final ScriptModel<E> delegate;
    private final Map<String, String> replacements;
    NativeScriptModel(ScriptModel<E> delegate, Map<String, String> replacements) { this.delegate = delegate; this.replacements = Map.copyOf(replacements); }
    @Override public boolean isActual() { return delegate.isActual(); }
    @Override public ScriptModel<E> subModel(TextRange range) { return new NativeScriptModel<>(delegate.subModel(range), replacements); }
    @Override public JBIterable<E> everything() { return delegate.everything(); }
    @Override public JBIterable<? extends StatementIt<E>> statements() {
        return delegate.statements().map(statement -> new StatementIt<E>() {
            private String replacement() { return replacements.get(statement.query()); }
            @Override public String query() { return replacement() != null ? replacement() : statement.query(); }
            @Override public String consoleQuery(PStorage storage, Condition<? super ParamIt<E>> condition) throws TranslateException {
                return replacement() != null ? replacement() : statement.consoleQuery(storage, condition);
            }
            @Override public JBIterable<? extends ParamIt<E>> parameters() { return replacement() != null ? JBIterable.empty() : statement.parameters(); }
            @Override public JBIterable<? extends ExternalIt<E>> externals() { return statement.externals(); }
            @Override public String text() { return statement.text(); }
            @Override public TextRange range() { return statement.range(); }
            @Override public IElementType type() { return statement.type(); }
            @Override public SyntaxTraverser.Api<E> api() { return statement.api(); }
            @Override public long rangeOffset() { return statement.rangeOffset(); }
            @Override public E object() { return statement.object(); }
        });
    }
    @Override public JBIterable<? extends ParamIt<E>> parameters() { return delegate.parameters(); }
    @Override public JBIterable<? extends ExternalIt<E>> externals() { return delegate.externals(); }
    @Override public VirtualFile getVirtualFile() { return delegate.getVirtualFile(); }
    @Override public TextRange getTextRange() { return delegate.getTextRange(); }
    @Override public Language getLanguage() { return delegate.getLanguage(); }
    @Override public <T> ScriptModel<T> rawTransform(Function<? super SyntaxTraverser<E>, ? extends SyntaxTraverser<T>> transform) {
        return new NativeScriptModel<>(delegate.rawTransform(transform), replacements);
    }
}
