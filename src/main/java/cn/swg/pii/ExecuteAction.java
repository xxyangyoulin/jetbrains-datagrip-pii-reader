package cn.swg.pii;

import com.intellij.database.dataSource.LocalDataSource;
import com.intellij.database.console.JdbcConsole;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.ide.passwordSafe.PasswordSafe;
import com.intellij.credentialStore.CredentialAttributes;
import com.intellij.credentialStore.Credentials;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.*;
import org.jetbrains.annotations.NotNull;
import javax.swing.*;
import javax.swing.event.*;
import java.awt.*;
import java.util.Arrays;

public final class ExecuteAction extends AnAction {
    @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }
    @Override public void update(@NotNull AnActionEvent event) {
        event.getPresentation().setEnabled(event.getProject() != null && event.getData(CommonDataKeys.EDITOR) != null);
    }
    @Override public void actionPerformed(@NotNull AnActionEvent event) {
        ActionManager.getInstance().getAction("Console.Jdbc.Execute").actionPerformed(event);
    }
    record Configuration(String endpoint, CredentialAttributes credentials) {}

    static Configuration configure(Project project, JdbcConsole console) {
        if (console == null || console.getDataSource() == null) {
            Messages.showErrorDialog(project, "请在已绑定数据源的 SQL 控制台中执行。", "PII 查询"); return null;
        }
        LocalDataSource source = console.getDataSource();
        String url = source.getUrl();
        if (url == null || !(url.startsWith("jdbc:mysql:") || url.startsWith("jdbc:mariadb:"))) {
            Messages.showErrorDialog(project, "PII 查询仅支持 MySQL / MariaDB。", "PII 查询"); return null;
        }
        QueryDialog dialog = new QueryDialog(project, source, console);
        try {
            if (!dialog.showAndGet()) return null;
            return new Configuration(dialog.endpoint.getText().trim(), dialog.tokenAttributes());
        } finally {
            dialog.clear();
            if (dialog.client != null) dialog.client.clearToken();
        }
    }

    private static final class QueryDialog extends DialogWrapper {
        private final String connectionLabel;
        private final JTextField endpoint;
        private final JPasswordField token = new JPasswordField(44);
        private final Project project;
        private WorkbenchClient client;
        QueryDialog(Project project, LocalDataSource source, JdbcConsole console) {
            super(project); this.project = project;
            connectionLabel = "当前控制台：" + source.getName() + " / " + console.getCurrentNamespace().getDisplayName();
            endpoint = new JTextField(PropertiesComponent.getInstance(project).getValue("workbench.pii.endpoint", "https://你的Workbench域名/jsst/privacy_decrypt_batch"), 44);
            Credentials[] saved = new Credentials[1];
            ProgressManager.getInstance().runProcessWithProgressSynchronously(
                () -> saved[0] = PasswordSafe.getInstance().get(tokenAttributes()), "读取 Workbench 凭证", false, project);
            if (saved[0] != null) token.setText(saved[0].getPasswordAsString());
            endpoint.getDocument().addDocumentListener(new DocumentListener() {
                @Override public void insertUpdate(DocumentEvent e) { clear(); }
                @Override public void removeUpdate(DocumentEvent e) { clear(); }
                @Override public void changedUpdate(DocumentEvent e) { clear(); }
            });
            setTitle("执行 PII 查询"); setOKButtonText("查询并解密"); init();
        }
        private CredentialAttributes tokenAttributes() {
            return new CredentialAttributes("Workbench PII Reader:" + project.getLocationHash() + ":" + endpoint.getText().trim(), "user-token");
        }
        void clear() { token.setText(""); }
        @Override protected JComponent createCenterPanel() {
            JPanel panel = new JPanel(new GridLayout(0, 1, 4, 4));
            panel.add(new JLabel(connectionLabel));
            panel.add(new JLabel("Workbench 批量解密接口（请选择与数据源对应的环境）")); panel.add(endpoint);
            panel.add(new JLabel("Workbench user-token（默认记住，保存到 DataGrip 凭证存储）")); panel.add(token);
            panel.add(new JLabel("结果使用原生分页和导出；解密列只读，数据库排序及筛选按密文执行。"));
            return panel;
        }
        @Override protected void doOKAction() {
            char[] password = token.getPassword();
            try {
                client = new WorkbenchClient(endpoint.getText(), new String(password));
                CredentialAttributes attributes = tokenAttributes();
                Credentials credentials = new Credentials("user-token", password);
                ProgressManager.getInstance().runProcessWithProgressSynchronously(
                    () -> PasswordSafe.getInstance().set(attributes, credentials, false), "保存 Workbench 凭证", false, project);
                PropertiesComponent.getInstance(project).setValue("workbench.pii.endpoint", endpoint.getText().trim());
                super.doOKAction();
            } catch (IllegalArgumentException e) { setErrorText(e.getMessage()); }
            finally { Arrays.fill(password, '\0'); }
        }
    }

}
