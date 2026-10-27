package cn.swg.pii;

import java.lang.reflect.*;
import java.math.BigDecimal;
import java.util.*;

public final class ReaderTest {
    private static int checks;
    private static void check(boolean ok) { checks++; if (!ok) throw new AssertionError("check " + checks); }
    public static void main(String[] args) throws Exception {
        String joinedSql = "SELECT m.id, decr(m.mobile) AS member_mobile, decr(a.mobile) AS delivery_mobile, " +
            "decr(a.receiver) AS receiver, decr(e.mobile) AS erp_mobile " +
            "FROM js_member m LEFT JOIN js_member_address a ON a.member_id=m.id " +
            "INNER JOIN business_member e ON e.id=m.erp_id " +
            "WHERE m.mobile_hash=hash('same') AND a.receiver_hash IN (hash('Alice'), hash('Bob')) " +
            "AND e.mobile_hash=hash('same') ORDER BY m.id LIMIT 20";
        SqlPlan joined = SqlPlan.nativePlan(joinedSql);
        check(joined.scopes.equals(Map.of(1, "member.mobile", 2, "member_address.mobile", 3, "member_address.receiver", 4, "member.mobile")));
        check(joined.projects.equals(Map.of(1, "jsst", 2, "jsst", 3, "jsst", 4, "erp")));
        List<String> lookupCalls = new ArrayList<>();
        joined.resolveLookups((project, scope, values) -> {
            lookupCalls.add(project + ":" + scope + ":" + values.size());
            return Collections.nCopies(values.size(), (project.equals("erp") ? "e" : "a").repeat(64));
        });
        check(lookupCalls.equals(List.of("jsst:member.mobile:1", "jsst:member_address.receiver:2", "erp:member.mobile:1")));
        String joinedNative = joined.nativeSql(null);
        check(joinedNative.contains("LEFT JOIN `js_member_address` AS `a` ON `a`.`member_id` = `m`.`id`"));
        check(joinedNative.contains("`e`.`mobile_hash` = '" + "e".repeat(64) + "'"));
        check(joinedNative.contains("CONCAT('', `a`.`receiver`) AS `receiver`"));
        NativeQueryRegistry joinRegistry = new NativeQueryRegistry();
        String joinId = joinRegistry.register(joined, "http://example.test/prefix/jsst/privacy_decrypt_batch",
            new com.intellij.credentialStore.CredentialAttributes("fixture"));
        var joinXml = com.intellij.util.xmlb.XmlSerializer.serialize(joinRegistry.getState());
        NativeQueryRegistry joinRestored = new NativeQueryRegistry();
        joinRestored.loadState(com.intellij.util.xmlb.XmlSerializer.deserialize(joinXml, NativeQueryRegistry.SavedState.class));
        var joinQuery = joinRestored.require(joinId);
        check(joinQuery.endpointFor("member_mobile").equals("http://example.test/prefix/jsst/privacy_decrypt_batch"));
        check(joinQuery.endpointFor("erp_mobile").equals("http://example.test/prefix/business/privacy_decrypt_batch"));
        check(joinQuery.scopes().get("receiver").equals("member_address.receiver"));
        var legacyState = new NativeQueryRegistry.SavedState();
        var legacyQuery = new NativeQueryRegistry.StoredQuery();
        legacyQuery.endpoint = "http://example.test/jsst/privacy_decrypt_batch";
        legacyQuery.credentialService = "fixture";
        legacyQuery.scopes.put("mobile", "member.mobile");
        legacyState.queries.put("legacy", legacyQuery);
        NativeQueryRegistry legacy = new NativeQueryRegistry();
        legacy.loadState(legacyState);
        check(legacy.require("legacy").endpointFor("mobile").equals(legacyQuery.endpoint));
        for (String kind : List.of("JOIN", "INNER JOIN", "LEFT JOIN", "LEFT OUTER JOIN", "RIGHT JOIN", "RIGHT OUTER JOIN")) {
            SqlPlan self = SqlPlan.nativePlan("SELECT decr(m.mobile) AS own, decr(p.mobile) AS parent FROM js_member m " +
                kind + " js_member p ON (p.id=m.parent_id AND p.mobile_hash=hash('fixture')) WHERE m.id=1");
            check(self.scopes.equals(Map.of(0, "member.mobile", 1, "member.mobile")));
            check(self.parameters.get(0).equals(new SqlPlan.Lookup("jsst", "member.mobile", "fixture")));
        }
        check(SqlPlan.nativePlan("SELECT decr(a.mobile) FROM db.js_member_address a JOIN reference_table r ON r.id=a.region_id")
            .scopes.equals(Map.of(0, "member_address.mobile")));
        check(SqlPlan.nativePlan("SELECT decr(a.mobile) AS mobile FROM business_member_address a WHERE a.phone_fingerprint=hash('x','member_address.mobile')")
            .parameters.get(0).equals(new SqlPlan.Lookup("erp", "member_address.mobile", "x")));
        check(SqlPlan.nativePlan("SELECT decr(a.mobile) AS phone FROM js_member_address a JOIN js_member m ON a.member_id=m.id ORDER BY m.mobile")
            .scopes.equals(Map.of(0, "member_address.mobile")));
        for (String sql : List.of(
                "SELECT decr(mobile) FROM js_member m JOIN js_member_address a ON a.member_id=m.id",
                "SELECT m.id FROM js_member m JOIN business_member e ON e.id=m.id WHERE mobile_hash=hash('x')",
                "SELECT decr(m.mobile), decr(a.mobile) FROM js_member m JOIN js_member_address a ON a.member_id=m.id",
                "SELECT decr(m.mobile) FROM js_member m JOIN js_member m ON m.id=m.id",
                "SELECT decr(a.mobile) FROM js_member m JOIN unknown_address a ON a.member_id=m.id",
                "SELECT decr(z.mobile) FROM js_member m JOIN js_member_address a ON a.member_id=m.id",
                "SELECT decr(m.mobile) FROM js_member m LEFT JOIN js_member_address a",
                "SELECT decr(m.mobile) FROM js_member m JOIN js_member_address a USING (id)",
                "SELECT decr(m.mobile) FROM js_member m CROSS JOIN js_member_address a",
                "SELECT decr(m.mobile) FROM js_member m UNION SELECT mobile FROM js_member",
                "SELECT decr(m.mobile) FROM js_member m GROUP BY m.mobile",
                "SELECT decr(m.mobile) FROM js_member m JOIN js_member_address a ON a.member_id=m.id ORDER BY m.mobile",
                "SELECT decr(m.mobile, '') FROM js_member m",
                "SELECT decr(mobile), * FROM js_member",
                "SELECT decr(m.mobile) FROM (SELECT mobile FROM js_member) m")) {
            try { SqlPlan.nativePlan(sql); throw new AssertionError("invalid join accepted: " + sql); }
            catch (IllegalArgumentException expected) { checks++; }
        }
        check(VirtualFunction.present("SELECT /*M! decr(mobile) */ mobile FROM js_member"));
        try { SqlPlan.nativePlan("SELECT /*M! decr(mobile) */ mobile FROM js_member"); throw new AssertionError("executable comment accepted"); }
        catch (IllegalArgumentException expected) { checks++; }
        for (String constant : List.of("NULL", "TRUE", "FALSE")) {
            check(SqlPlan.nativePlan("SELECT decr(m.mobile) FROM js_member m JOIN js_member_address a ON a.member_id=m.id WHERE a.flag=" + constant)
                .nativeSql(null).endsWith("`a`.`flag` = " + constant));
        }
        SqlPlan inferred = SqlPlan.nativePlan("SELECT decr(m.mobile) FROM db.js_member AS m WHERE m.mobile_hash=hash('fixture')");
        check(inferred.projects.get(0).equals("jsst") && inferred.scopes.equals(Map.of(0, "member.mobile")));
        check(inferred.parameters.get(0).equals(new SqlPlan.Lookup("jsst", "member.mobile", "fixture")));
        SqlPlan erp = SqlPlan.nativePlan("SELECT decr(m.mobile) FROM business_member m WHERE m.mobile_hash IN (hash('one'), hash('two'))");
        check(erp.projects.get(0).equals("erp") && erp.scopes.equals(Map.of(0, "member.mobile")));
        check(erp.parameters.get(1).equals(new SqlPlan.Lookup("erp", "member.mobile", "two")));
        check(WorkbenchClient.endpointFor("http://example.test:90/prefix/jsst/privacy_decrypt_batch", erp.projects.get(0))
            .equals("http://example.test:90/prefix/business/privacy_decrypt_batch"));
        check(WorkbenchClient.endpointFor("https://example.test/business/privacy_decrypt_batch", inferred.projects.get(0))
            .equals("https://example.test/jsst/privacy_decrypt_batch"));
        for (String sql : List.of("SELECT decr(mobile) FROM other_member",
                "SELECT id FROM js_member WHERE mobile=hash('x')",
                "SELECT id FROM js_member m WHERE other.mobile_hash=hash('x')")) {
            try { SqlPlan.nativePlan(sql); throw new AssertionError("ambiguous inference accepted"); }
            catch (IllegalArgumentException expected) { checks++; }
        }
        check(SqlPlan.nativePlan("SELECT id FROM js_member WHERE fingerprint=hash('x','member.mobile')")
            .parameters.get(0).equals(new SqlPlan.Lookup("jsst", "member.mobile", "x")));
        check(VirtualFunction.present("SELECT id FROM js_member WHERE mobile_hash=hash('fixture','member.mobile')"));
        check(!VirtualFunction.present("SELECT 'hash(x)'"));
        SqlPlan lookup = SqlPlan.nativePlan("SELECT id FROM js_member WHERE mobile_hash IN (hash('fixture','member.mobile'), hash('second','member.mobile'))");
        check(lookup.scopes.isEmpty() && lookup.hasLookups());
        try { lookup.nativeSql(null); throw new AssertionError("unresolved lookup accepted"); }
        catch (IllegalArgumentException expected) { checks++; }
        lookup.resolveLookups((project, scope, values) -> {
            check(scope.equals("member.mobile") && values.equals(List.of("fixture", "second")));
            return List.of("a".repeat(64), "b".repeat(64));
        });
        check(!lookup.hasLookups() && lookup.nativeSql(null).contains("'" + "a".repeat(64) + "'"));
        check(!lookup.nativeSql(null).contains("workbench-pii:"));
        for (String condition : List.of("mobile_hash LIKE hash('x','member.mobile')", "mobile_hash > hash('x','member.mobile')",
                "mobile_hash=hash(mobile,'member.mobile')", "mobile_hash=hash('x','')")) {
            try { SqlPlan.nativePlan("SELECT id FROM js_member WHERE " + condition); throw new AssertionError("invalid hash accepted"); }
            catch (IllegalArgumentException expected) { checks++; }
        }
        SqlPlan wrongHash = SqlPlan.nativePlan("SELECT id FROM js_member WHERE mobile_hash=hash('x','member.mobile')");
        try { wrongHash.resolveLookups((project, scope, values) -> List.of("invalid")); throw new AssertionError("invalid response accepted"); }
        catch (java.io.IOException expected) { checks++; }
        SqlPlan largeLookup = SqlPlan.nativePlan("SELECT decr(mobile) FROM js_member WHERE mobile_hash IN (" +
            String.join(",", Collections.nCopies(201, "hash('fixture','member.mobile')")) + ")");
        List<Integer> batches = new ArrayList<>();
        largeLookup.resolveLookups((project, scope, values) -> {
            batches.add(values.size()); return Collections.nCopies(values.size(), "c".repeat(64));
        });
        check(batches.equals(List.of(200, 1)) && largeLookup.scopes.size() == 1);
        check(VirtualFunction.present("SELECT decr(mobile) FROM js_member"));
        check(VirtualFunction.present("SELECT decr (mobile) FROM js_member"));
        check(VirtualFunction.present("SELECT `decr`(mobile) FROM js_member"));
        check(VirtualFunction.present("SELECT decr/**/(mobile) FROM js_member"));
        check(VirtualFunction.present("SELECT /*! decr(mobile) */ 1"));
        check(!VirtualFunction.present("SELECT 'decr(mobile)'"));
        check(!VirtualFunction.present("SELECT 1 -- decr(mobile)\n"));
        check(!VirtualFunction.present("SELECT 1 /* decr(mobile) */"));
        check(!VirtualFunction.present("SELECT my_decr(mobile) FROM js_member"));
        check(!VirtualFunction.present("SELECT decr FROM example"));
        check(!VirtualFunction.present("UPDATE js_member SET mobile = 'test' WHERE id=1"));
        SqlPlan plan = SqlPlan.parse("SELECT id, decr(mobile) AS mobile FROM js_member WHERE id=46986");
        check(plan.sql.equals("SELECT `id` AS `id`, `mobile` AS `mobile` FROM `js_member` WHERE `id` = ?"));
        check(plan.parameters.equals(List.of(new BigDecimal("46986"))));
        check(plan.scopes.equals(Map.of(1, "member.mobile")));
        check(SqlPlan.parse("SELECT decr(mobile) FROM `test`.`js_member`").sql.contains("FROM `test`.`js_member`"));
        for (String sql : List.of("DELETE FROM js_member", "SELECT decr(mobile) FROM js_member; UPDATE js_member SET id=1", "SELECT id FROM js_member WHERE decr(mobile)='x'")) {
            try { SqlPlan.parse(sql); throw new AssertionError("accepted unsupported SQL"); }
            catch (IllegalArgumentException expected) { checks++; }
        }
        String id = "01234567-89ab-cdef-0123-456789abcdef";
        SqlPlan nativePlan = SqlPlan.nativePlan("SELECT id, decr(mobile) AS mobile FROM js_member WHERE id=46986");
        String nativeSql = nativePlan.nativeSql(id);
        check(nativeSql.equals("SELECT /*workbench-pii:" + id + "*/ `id` AS `id`, CONCAT('', `mobile`) AS `mobile` FROM `js_member` WHERE `id` = 46986"));
        check(NativeQueryRegistry.marker(nativeSql).equals(id));
        check(NativeQueryRegistry.marker("SELECT * FROM (" + nativeSql + ") t LIMIT 500 OFFSET 500").equals(id));
        check(NativeQueryRegistry.marker("SELECT 1") == null);
        check(NativeQueryRegistry.marker("SELECT '/*workbench-pii:" + id + "*/'") == null);
        check(NativeQueryRegistry.marker("SELECT 1 -- /*workbench-pii:" + id + "*/") == null);
        SqlPlan literals = SqlPlan.nativePlan("SELECT decr(mobile) AS `a?``b` FROM js_member WHERE name='x?'';y'");
        check(literals.nativeSql(id).contains("AS `a?``b`"));
        check(literals.nativeSql(id).endsWith("WHERE `name` = 'x?'';y'"));
        try {
            SqlPlan.nativePlan("SELECT id AS mobile, decr(mobile) AS mobile FROM js_member");
            throw new AssertionError("duplicate label accepted");
        } catch (IllegalArgumentException expected) { checks++; }
        NativeQueryRegistry registry = new NativeQueryRegistry();
        String registration = registry.register(nativePlan, "http://example.test/jsst/privacy_decrypt_batch", new com.intellij.credentialStore.CredentialAttributes("fixture"));
        check(registry.require(registration).scopes().equals(Map.of("mobile", "member.mobile")));
        NativeQueryRegistry restored = new NativeQueryRegistry();
        restored.loadState(registry.getState());
        check(restored.require(registration).scopes().equals(Map.of("mobile", "member.mobile")));
        check(restored.require(registration).credentials().getServiceName().equals("fixture"));
        var xml = com.intellij.util.xmlb.XmlSerializer.serialize(registry.getState());
        NativeQueryRegistry diskRestored = new NativeQueryRegistry();
        diskRestored.loadState(com.intellij.util.xmlb.XmlSerializer.deserialize(xml, NativeQueryRegistry.SavedState.class));
        check(diskRestored.require(registration).scopes().equals(Map.of("mobile", "member.mobile")));
        registry.loadState(new NativeQueryRegistry.SavedState());
        try { registry.require(registration); throw new AssertionError("stale query accepted"); }
        catch (IllegalStateException expected) { checks++; }
        String[] statementSql = {"SELECT decr(mobile) FROM js_member"};
        com.intellij.database.script.ScriptModel.StatementIt<Object> statement = proxy(com.intellij.database.script.ScriptModel.StatementIt.class,
            (o,m,a) -> switch (m.getName()) {
                case "query", "consoleQuery", "text" -> statementSql[0];
                case "parameters", "externals" -> com.intellij.util.containers.JBIterable.empty();
                default -> throw new AssertionError(m.getName());
            });
        com.intellij.database.script.ScriptModel<Object> model = new com.intellij.database.script.ScriptModel<>() {
            @Override public boolean isActual() { return true; }
            @Override public com.intellij.database.script.ScriptModel<Object> subModel(com.intellij.openapi.util.TextRange range) { return this; }
            @Override public com.intellij.util.containers.JBIterable<Object> everything() { return com.intellij.util.containers.JBIterable.empty(); }
            @Override public com.intellij.util.containers.JBIterable<? extends StatementIt<Object>> statements() { return com.intellij.util.containers.JBIterable.of(statement); }
            @Override public com.intellij.util.containers.JBIterable<? extends ParamIt<Object>> parameters() { return com.intellij.util.containers.JBIterable.empty(); }
            @Override public com.intellij.util.containers.JBIterable<? extends ExternalIt<Object>> externals() { return com.intellij.util.containers.JBIterable.empty(); }
            @Override public com.intellij.openapi.vfs.VirtualFile getVirtualFile() { return null; }
            @Override public com.intellij.openapi.util.TextRange getTextRange() { return new com.intellij.openapi.util.TextRange(0, statementSql[0].length()); }
            @Override public com.intellij.lang.Language getLanguage() { return null; }
            @Override public <T> com.intellij.database.script.ScriptModel<T> rawTransform(com.intellij.util.Function<? super com.intellij.psi.SyntaxTraverser<Object>, ? extends com.intellij.psi.SyntaxTraverser<T>> transform) { throw new UnsupportedOperationException(); }
        };
        check(!SqlPlan.nativePlan("SELECT decr(mobile) FROM js_member").nativeSql(null).contains("workbench-pii"));
        check(SqlPlan.nativePlan("-- members\n SELECT decr(mobile) /* display */ FROM js_member; # end").scopes.equals(Map.of(0, "member.mobile")));
        try {
            SqlPlan.nativePlan("SELECT /*! decr(mobile) */ mobile FROM js_member");
            throw new AssertionError("executable comment accepted");
        } catch (IllegalArgumentException expected) { checks++; }
        String firstSql = statementSql[0];
        String secondSql = "SELECT decr(phone, 'contact.phone') AS phone FROM js_contact";
        String plainSql = "SELECT 123";
        var secondStatement = proxy(com.intellij.database.script.ScriptModel.StatementIt.class,
            (o,m,a) -> switch (m.getName()) {
                case "query", "consoleQuery", "text" -> secondSql;
                case "parameters", "externals" -> com.intellij.util.containers.JBIterable.empty();
                default -> throw new AssertionError(m.getName());
            });
        var plainStatement = proxy(com.intellij.database.script.ScriptModel.StatementIt.class,
            (o,m,a) -> switch (m.getName()) {
                case "query", "consoleQuery", "text" -> plainSql;
                case "parameters", "externals" -> com.intellij.util.containers.JBIterable.empty();
                default -> throw new AssertionError(m.getName());
            });
        com.intellij.database.script.ScriptModel<Object> batch = new com.intellij.database.script.ScriptModel<>() {
            @Override public boolean isActual() { return model.isActual(); }
            @Override public com.intellij.database.script.ScriptModel<Object> subModel(com.intellij.openapi.util.TextRange range) { return this; }
            @Override public com.intellij.util.containers.JBIterable<Object> everything() { return model.everything(); }
            @Override public com.intellij.util.containers.JBIterable<? extends StatementIt<Object>> statements() {
                return com.intellij.util.containers.JBIterable.of(statement, plainStatement, secondStatement);
            }
            @Override public com.intellij.util.containers.JBIterable<? extends ParamIt<Object>> parameters() { return model.parameters(); }
            @Override public com.intellij.util.containers.JBIterable<? extends ExternalIt<Object>> externals() { return model.externals(); }
            @Override public com.intellij.openapi.vfs.VirtualFile getVirtualFile() { return null; }
            @Override public com.intellij.openapi.util.TextRange getTextRange() { return model.getTextRange(); }
            @Override public com.intellij.lang.Language getLanguage() { return null; }
            @Override public <T> com.intellij.database.script.ScriptModel<T> rawTransform(com.intellij.util.Function<? super com.intellij.psi.SyntaxTraverser<Object>, ? extends com.intellij.psi.SyntaxTraverser<T>> transform) { throw new UnsupportedOperationException(); }
        };
        var explained = NativeExecution.transform(null, batch, true);
        var explainedSql = explained.statements().map(q -> q.query()).toList();
        check(explainedSql.size() == 3);
        check(explainedSql.get(0).contains("CONCAT('', `mobile`)"));
        check(explainedSql.get(1).equals(plainSql));
        check(explainedSql.get(2).contains("CONCAT('', `phone`)"));
        check(explainedSql.stream().noneMatch(q -> q.contains("workbench-pii")));
        String secondId = "11234567-89ab-cdef-0123-456789abcdef";
        var mixed = new NativeScriptModel<>(batch, Map.of(firstSql, nativeSql, secondSql, SqlPlan.nativePlan(secondSql).nativeSql(secondId)));
        var mixedSql = mixed.statements().map(q -> q.query()).toList();
        check(NativeQueryRegistry.marker(mixedSql.get(0)).equals(id));
        check(mixedSql.get(1).equals(plainSql));
        check(NativeQueryRegistry.marker(mixedSql.get(2)).equals(secondId));
        var descriptor = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse("src/main/resources/META-INF/plugin.xml");
        Set<String> overridden = new HashSet<>();
        var actions = descriptor.getElementsByTagName("action");
        for (int i = 0; i < actions.getLength(); i++) {
            var action = (org.w3c.dom.Element)actions.item(i);
            if ("true".equals(action.getAttribute("overrides"))) {
                overridden.add(action.getAttribute("id"));
                check(com.intellij.openapi.actionSystem.AnAction.class.isAssignableFrom(Class.forName(action.getAttribute("class"))));
            }
        }
        check(overridden.equals(Set.of("Console.Jdbc.Execute", "Console.Jdbc.Execute.2", "Console.Jdbc.Execute.3",
            "Console.Jdbc.Execute.Selection", "Console.Jdbc.ExplainPlan", "Console.Jdbc.ExplainAnalyse",
            "Console.Jdbc.ExplainPlan.Raw", "Console.Jdbc.ExplainAnalyse.Raw")));
        NativeScriptModel<Object> wrapped = new NativeScriptModel<>(model, Map.of(statementSql[0], nativeSql));
        check(wrapped.statements().first().query().equals(nativeSql));
        check(wrapped.statements().first().consoleQuery(null, null).equals(nativeSql));
        check(wrapped.statements().first().text().equals(statementSql[0]));
        check(wrapped.subModel(model.getTextRange()).statements().first().query().equals(nativeSql));
        statementSql[0] = "SELECT 123";
        check(wrapped.statements().first().query().equals("SELECT 123"));
        check(wrapped.statements().first().consoleQuery(null, null).equals("SELECT 123"));
        PiiInspectionSuppressor suppressor = new PiiInspectionSuppressor();
        com.intellij.sql.psi.SqlReferenceExpression[] name = new com.intellij.sql.psi.SqlReferenceExpression[1];
        com.intellij.sql.psi.SqlFunctionCallExpression call = proxy(com.intellij.sql.psi.SqlFunctionCallExpression.class,
            (o,m,a) -> { if (m.getName().equals("getNameElement")) return name[0]; throw new AssertionError(m.getName()); });
        name[0] = proxy(com.intellij.sql.psi.SqlReferenceExpression.class, (o,m,a) -> switch (m.getName()) {
            case "getName" -> "decr";
            case "isQuotedIdentifier" -> false;
            case "getQualifierExpression" -> null;
            case "getParent" -> call;
            default -> throw new AssertionError(m.getName());
        });
        check(suppressor.isSuppressedFor(name[0], "SqlResolve"));
        check(!suppressor.isSuppressedFor(name[0], "SqlType"));
        com.intellij.psi.PsiElement leaf = proxy(com.intellij.psi.PsiElement.class,
            (o,m,a) -> { if (m.getName().equals("getParent")) return name[0]; throw new AssertionError(m.getName()); });
        check(suppressor.isSuppressedFor(leaf, "SqlResolveInspection"));
        com.intellij.sql.psi.SqlReferenceExpression field = proxy(com.intellij.sql.psi.SqlReferenceExpression.class,
            (o,m,a) -> { if (m.getName().equals("getName")) return "missing_mobile"; throw new AssertionError(m.getName()); });
        check(!suppressor.isSuppressedFor(field, "SqlResolve"));
        com.intellij.sql.psi.SqlReferenceExpression ordinaryColumn = proxy(com.intellij.sql.psi.SqlReferenceExpression.class, (o,m,a) -> switch (m.getName()) {
            case "getName" -> "decr";
            case "isQuotedIdentifier" -> false;
            case "getQualifierExpression", "getParent" -> null;
            default -> throw new AssertionError(m.getName());
        });
        check(!suppressor.isSuppressedFor(ordinaryColumn, "SqlResolve"));
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        java.util.concurrent.atomic.AtomicReference<String> received = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<String> auth = new java.util.concurrent.atomic.AtomicReference<>();
        server.createContext("/jsst/privacy_lookup_batch", exchange -> {
            received.set(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            auth.set(exchange.getRequestHeaders().getFirst("user-token") + ":" + exchange.getRequestHeaders().getFirst("view-privacy"));
            byte[] response = ("{\"code\":2000,\"data\":{\"results\":[{\"ok\":true,\"value\":\"" + "d".repeat(64) + "\"}]}}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) { output.write(response); }
        });
        List<String> routedRequests = new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.concurrent.atomic.AtomicBoolean denyErp = new java.util.concurrent.atomic.AtomicBoolean();
        for (String module : List.of("jsst", "business")) {
            for (String operation : List.of("decrypt", "lookup")) {
                if (module.equals("jsst") && operation.equals("lookup")) continue;
                String path = "/" + module + "/privacy_" + operation + "_batch";
                server.createContext(path, exchange -> {
                    var body = com.google.gson.JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
                    routedRequests.add(path + ":" + body.get("scope").getAsString());
                    String responseText;
                    if (module.equals("business") && denyErp.get()) responseText = "{\"code\":6000}";
                    else {
                        String value = operation.equals("lookup") ? "e".repeat(64) : module + "-plain";
                        responseText = "{\"code\":2000,\"data\":{\"results\":[{\"ok\":true,\"value\":\"" + value + "\"}]}}";
                    }
                    byte[] response = responseText.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, response.length);
                    try (var output = exchange.getResponseBody()) { output.write(response); }
                });
            }
        }
        server.start();
        try {
            WorkbenchClient http = new WorkbenchClient("http://127.0.0.1:" + server.getAddress().getPort() + "/jsst/privacy_decrypt_batch", "synthetic-test-token");
            check(http.lookup("member.mobile", List.of("fixture")).equals(List.of("d".repeat(64))));
            var payload = com.google.gson.JsonParser.parseString(received.get()).getAsJsonObject();
            check(payload.get("scope").getAsString().equals("member.mobile"));
            check(payload.getAsJsonArray("values").get(0).getAsString().equals("fixture"));
            check(auth.get().equals("synthetic-test-token:1"));
            http.clearToken();
            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/jsst/privacy_decrypt_batch";
            SqlPlan sameField = SqlPlan.nativePlan("SELECT decr(j.mobile) AS js_mobile, decr(e.mobile) AS erp_mobile " +
                "FROM js_member j JOIN business_member e ON e.id=j.id " +
                "WHERE j.mobile_hash=hash('same') AND e.mobile_hash=hash('same')");
            sameField.resolveLookups((project, scope, values) -> {
                WorkbenchClient client = new WorkbenchClient(WorkbenchClient.endpointFor(endpoint, project), "synthetic-test-token");
                try { return client.lookup(scope, values); } finally { client.clearToken(); }
            });
            check(sameField.nativeSql(null).contains("`j`.`mobile_hash` = '" + "d".repeat(64) + "'"));
            check(sameField.nativeSql(null).contains("`e`.`mobile_hash` = '" + "e".repeat(64) + "'"));
            NativeQueryRegistry httpRegistry = new NativeQueryRegistry();
            var routed = httpRegistry.require(httpRegistry.register(sameField, endpoint,
                new com.intellij.credentialStore.CredentialAttributes("fixture")));
            for (String label : List.of("js_mobile", "erp_mobile")) {
                WorkbenchClient client = new WorkbenchClient(routed.endpointFor(label), "synthetic-test-token");
                try {
                    check(client.decrypt(routed.scopes().get(label), List.of("synthetic-ciphertext")).get(0)
                        .equals(label.equals("js_mobile") ? "jsst-plain" : "business-plain"));
                } finally { client.clearToken(); }
            }
            check(routedRequests.contains("/business/privacy_lookup_batch:member.mobile"));
            check(routedRequests.contains("/jsst/privacy_decrypt_batch:member.mobile"));
            check(routedRequests.contains("/business/privacy_decrypt_batch:member.mobile"));
            denyErp.set(true);
            SqlPlan denied = SqlPlan.nativePlan("SELECT e.id FROM business_member e WHERE e.mobile_hash=hash('same')");
            try {
                denied.resolveLookups((project, scope, values) -> {
                    WorkbenchClient client = new WorkbenchClient(WorkbenchClient.endpointFor(endpoint, project), "synthetic-test-token");
                    try { return client.lookup(scope, values); } finally { client.clearToken(); }
                });
                throw new AssertionError("ERP permission rejection ignored");
            } catch (WorkbenchClient.ServiceException expected) { checks++; }
            try { denied.nativeSql(null); throw new AssertionError("failed lookup executable"); }
            catch (IllegalArgumentException expected) { checks++; }
        } finally { server.stop(0); }
        System.out.println("PASS: " + checks + " checks");
    }
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }
}
