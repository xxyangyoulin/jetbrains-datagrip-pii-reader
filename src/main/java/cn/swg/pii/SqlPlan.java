package cn.swg.pii;

import java.math.BigDecimal;
import java.util.*;

/** Restricted SELECT grammar; SQL is rebuilt from tokens, never forwarded verbatim. */
public final class SqlPlan {
    public final String sql;
    public final Map<Integer, String> projects;
    public final List<String> labels;
    public final Map<Integer, String> scopes;
    public final List<Object> parameters;

    private SqlPlan(Map<Integer, String> projects, String sql, List<String> labels, Map<Integer, String> scopes, List<Object> parameters) {
        this.projects = projects;
        this.sql = sql; this.labels = labels; this.scopes = scopes; this.parameters = parameters;
    }

    public static SqlPlan parse(String text) {
        try { return new Parser(text, false).parse(); }
        catch (IndexOutOfBoundsException e) { throw invalid(); }
    }
    static SqlPlan nativePlan(String text) {
        try { return new Parser(text, true).parse(); }
        catch (IndexOutOfBoundsException e) { throw invalid(); }
    }

    record Lookup(String project, String scope, String value) {}
    private record LookupGroup(String project, String scope) {}
    private record PendingLookup(Column column, String scope, String value) {}
    interface LookupResolver {
        List<String> resolve(String project, String scope, List<String> values) throws java.io.IOException, InterruptedException;
    }
    boolean hasLookups() { return parameters.stream().anyMatch(value -> value instanceof Lookup); }
    void resolveLookups(LookupResolver resolver) throws java.io.IOException, InterruptedException {
        Map<LookupGroup, List<Integer>> groups = new LinkedHashMap<>();
        for (int i = 0; i < parameters.size(); i++) {
            if (parameters.get(i) instanceof Lookup lookup)
                groups.computeIfAbsent(new LookupGroup(lookup.project(), lookup.scope()), key -> new ArrayList<>()).add(i);
        }
        for (var group : groups.entrySet()) {
            List<Integer> positions = group.getValue();
            for (int start = 0; start < positions.size(); start += 200) {
                List<Integer> batch = positions.subList(start, Math.min(start + 200, positions.size()));
                List<String> values = batch.stream().map(i -> ((Lookup)parameters.get(i)).value()).toList();
                List<String> hashes = resolver.resolve(group.getKey().project(), group.getKey().scope(), values);
                if (hashes.size() != batch.size() || hashes.stream().anyMatch(h -> h == null || !h.matches("[a-f0-9]{64}")))
                    throw new java.io.IOException("Workbench 查询指纹格式错误。");
                for (int i = 0; i < batch.size(); i++) parameters.set(batch.get(i), hashes.get(i));
            }
        }
    }

    String nativeSql(String id) {
        if (hasLookups()) throw new IllegalArgumentException("查询指纹尚未解析。");
        if (id != null && !id.matches("[a-f0-9-]{36}")) throw new IllegalArgumentException("Invalid query identifier");
        StringBuilder result = new StringBuilder();
        boolean quoted = false; int parameter = 0;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '`') {
                if (quoted && i + 1 < sql.length() && sql.charAt(i + 1) == '`') {
                    result.append("``"); i++; continue;
                }
                quoted = !quoted;
            }
            if (c == '?' && !quoted) {
                Object value = parameters.get(parameter++);
                result.append(value instanceof BigDecimal ? value.toString() : "'" + ((String)value).replace("'", "''") + "'");
            } else result.append(c);
        }
        return id == null ? result.toString() : result.insert(7, "/*workbench-pii:" + id + "*/ ").toString();
    }

    private record Token(String kind, String value) {}
    private record Column(String sql, String field, String qualifier) {}
    private record Table(String name) {
        String project() {
            if (name.startsWith("js_") && name.length() > 3) return "jsst";
            if (name.startsWith("business_") && name.length() > 9) return "erp";
            throw new IllegalArgumentException("无法从隐私字段所属表的前缀确定项目：仅支持 js_ 和 business_。");
        }
        String scopeTable() { return name.substring(project().equals("jsst") ? 3 : 9); }
    }
    private record Projection(Column column, String label, boolean decrypt, String scope) {}
    private static String quote(String name) { return "`" + name.replace("`", "``") + "`"; }
    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("仅支持 SELECT 显式字段、decr、hash、INNER/LEFT/RIGHT JOIN ON、WHERE、ORDER BY 和 LIMIT；不支持可执行注释、优化器提示、其他函数、子查询或写入语句。");
    }

    private static List<Token> lex(String text) {
        if (text.length() > 65536) throw invalid();
        List<Token> tokens = new ArrayList<>();
        for (int i = 0; i < text.length();) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) { i++; continue; }
            if (c == '#' || (text.startsWith("--", i) && i + 2 < text.length() && Character.isWhitespace(text.charAt(i + 2)))) {
                while (i < text.length() && text.charAt(i) != '\n') i++;
                continue;
            }
            if (text.startsWith("/*", i)) {
                if (text.startsWith("/*!", i) || text.startsWith("/*M!", i) || text.startsWith("/*+", i)) throw invalid();
                int end = text.indexOf("*/", i + 2);
                if (end < 0) throw invalid();
                i = end + 2; continue;
            }
            if (c == '\'' || c == '`') {
                char end = c; StringBuilder value = new StringBuilder(); i++;
                boolean closed = false;
                while (i < text.length()) {
                    c = text.charAt(i++);
                    if (c == '\\' || c == '\0') throw invalid();
                    if (c == end) {
                        if (i < text.length() && text.charAt(i) == end) { value.append(c); i++; }
                        else { closed = true; break; }
                    } else value.append(c);
                }
                if (!closed || (end == '`' && value.isEmpty())) throw invalid();
                tokens.add(new Token(end == '`' ? "quoted" : "string", value.toString()));
            } else if (Character.isLetter(c) || c == '_') {
                int start = i++;
                while (i < text.length() && (Character.isLetterOrDigit(text.charAt(i)) || text.charAt(i) == '_' || text.charAt(i) == '$')) i++;
                tokens.add(new Token("word", text.substring(start, i)));
            } else if (Character.isDigit(c) || ((c == '-' || c == '+') && i + 1 < text.length() && Character.isDigit(text.charAt(i + 1)))) {
                int start = i++;
                while (i < text.length() && Character.isDigit(text.charAt(i))) i++;
                if (i < text.length() && text.charAt(i) == '.') {
                    i++; int decimals = i;
                    while (i < text.length() && Character.isDigit(text.charAt(i))) i++;
                    if (decimals == i) throw invalid();
                }
                tokens.add(new Token("number", text.substring(start, i)));
            } else if (",.();=<>!".indexOf(c) >= 0) {
                String value = String.valueOf(c); i++;
                if (i < text.length() && (("<>!".indexOf(c) >= 0 && text.charAt(i) == '=') || (c == '<' && text.charAt(i) == '>'))) value += text.charAt(i++);
                tokens.add(new Token("symbol", value));
            } else throw invalid();
        }
        tokens.add(new Token("end", ""));
        return tokens;
    }

    private static final class Parser {
        private final List<Token> tokens;
        private final List<Object> parameters = new ArrayList<>();
        private final List<Column> references = new ArrayList<>();
        private int index;
        private final Map<String, Table> tables = new LinkedHashMap<>();
        private final boolean nativeQuery;
        Parser(String text, boolean nativeQuery) { tokens = lex(text); this.nativeQuery = nativeQuery; }
        boolean at(String value) {
            Token t = tokens.get(index);
            return !t.kind.equals("quoted") && !t.kind.equals("string") && t.value.equalsIgnoreCase(value);
        }
        boolean take(String value) { if (!at(value)) return false; index++; return true; }
        void need(String value) { if (!take(value)) throw invalid(); }
        String identifier() {
            Token t = tokens.get(index++);
            if (!t.kind.equals("word") && !t.kind.equals("quoted")) throw invalid();
            return t.value;
        }
        Column column() {
            String first = identifier();
            Column c = take(".") ? new Column(quote(first) + "." + quote(identifier()), "", first) : new Column(quote(first), first, "");
            if (!c.qualifier.isEmpty()) {
                String field = tokens.get(index - 1).value;
                c = new Column(c.sql, field, first);
            }
            references.add(c); return c;
        }
        SqlPlan parse() {
            need("SELECT");
            // DISTINCT is deliberately excluded: ciphertext equality differs from plaintext equality.
            if (at("DISTINCT") || at("ALL")) throw invalid();
            List<Projection> projections = new ArrayList<>();
            do {
                boolean decrypt = take("decr");
                if (decrypt) need("(");
                Column c = column(); String scope = null;
                if (decrypt && take(",")) {
                    Token token = tokens.get(index++);
                    if (!token.kind.equals("string") || token.value.isBlank()) throw invalid();
                    scope = token.value;
                }
                if (decrypt) need(")");
                String label = take("AS") ? identifier() : c.field;
                projections.add(new Projection(c, label, decrypt, scope));
                if (projections.size() > 64) throw invalid();
            } while (take(","));
            need("FROM");
            StringBuilder tail = new StringBuilder(" FROM " + table());
            while (at("JOIN") || at("INNER") || at("LEFT") || at("RIGHT")) {
                String kind = "";
                if (take("INNER")) kind = "INNER ";
                else if (take("LEFT")) { take("OUTER"); kind = "LEFT "; }
                else if (take("RIGHT")) { take("OUTER"); kind = "RIGHT "; }
                need("JOIN");
                tail.append(" ").append(kind).append("JOIN ").append(table());
                need("ON");
                tail.append(" ON ").append(expression(0));
            }
            if (take("WHERE")) tail.append(" WHERE ").append(expression(0));
            List<Column> sort = new ArrayList<>();
            if (take("ORDER")) {
                need("BY"); tail.append(" ORDER BY ");
                do {
                    if (!sort.isEmpty()) tail.append(", ");
                    Column c = column(); sort.add(c); tail.append(c.sql);
                    if (take("ASC")) tail.append(" ASC"); else if (take("DESC")) tail.append(" DESC");
                } while (take(","));
            }
            if (take("LIMIT")) {
                tail.append(" LIMIT ").append(unsigned());
                if (take(",")) tail.append(", ").append(unsigned());
                else if (take("OFFSET")) tail.append(" OFFSET ").append(unsigned());
            }
            take(";");
            if (!tokens.get(index).kind.equals("end")) throw invalid();
            for (Column c : references) {
                if (!c.qualifier.isEmpty() && !tables.containsKey(c.qualifier.toLowerCase(Locale.ROOT)))
                    throw new IllegalArgumentException("字段引用了未知的表名或别名。");
            }
            List<String> labels = new ArrayList<>(), sqlColumns = new ArrayList<>();
            for (int i = 0; i < parameters.size(); i++) {
                if (parameters.get(i) instanceof PendingLookup pending) {
                    Table owner = owner(pending.column);
                    String scope = pending.scope;
                    if (scope == null) {
                        String field = pending.column.field;
                        if (!field.endsWith("_hash") || field.length() <= 5)
                            throw new IllegalArgumentException("无法推导 hash 用途：字段须以 _hash 结尾，或显式指定第二个参数。");
                        scope = owner.scopeTable() + "." + field.substring(0, field.length() - 5);
                    }
                    parameters.set(i, new Lookup(owner.project(), scope, pending.value));
                }
            }
            Map<Integer, String> scopes = new LinkedHashMap<>();
            Map<Integer, String> projects = new LinkedHashMap<>();
            for (Projection p : projections) {
                if (p.decrypt) {
                    Table owner = owner(p.column);
                    String inferred = owner.scopeTable() + "." + p.column.field;
                    projects.put(labels.size(), owner.project());
                    scopes.put(labels.size(), p.scope == null ? inferred : p.scope);
                    for (Column c : sort) {
                        if ((c.qualifier.isEmpty() && c.field.equalsIgnoreCase(p.label))
                            || (c.field.equalsIgnoreCase(p.column.field) && (c.qualifier.isEmpty() || p.column.qualifier.isEmpty()
                                || c.qualifier.equalsIgnoreCase(p.column.qualifier))))
                            throw new IllegalArgumentException("不能对解密列排序；请使用 id 等普通字段排序。");
                    }
                }
                if (nativeQuery && labels.stream().anyMatch(label -> label.equalsIgnoreCase(p.label)))
                    throw new IllegalArgumentException("原生结果中的列名不能重复，请使用不同的 AS 别名。");
                labels.add(p.label);
                sqlColumns.add((nativeQuery && p.decrypt ? "CONCAT('', " + p.column.sql + ")" : p.column.sql) + " AS " + quote(p.label));
            }
            if (scopes.isEmpty() && parameters.stream().noneMatch(value -> value instanceof Lookup)) throw new IllegalArgumentException("查询至少需要 decr 输出字段或 hash 查询条件。");
            return new SqlPlan(projects, "SELECT " + String.join(", ", sqlColumns) + tail, labels, scopes, parameters);
        }
        String table() {
            String name = identifier(), sql = quote(name);
            if (take(".")) { name = identifier(); sql += "." + quote(name); }
            String alias = "";
            if (take("AS")) alias = identifier();
            else {
                Token next = tokens.get(index);
                if (next.kind.equals("quoted") || (next.kind.equals("word")
                        && !Set.of("WHERE", "ORDER", "LIMIT", "JOIN", "INNER", "LEFT", "RIGHT", "ON",
                                   "GROUP", "HAVING", "UNION", "CROSS", "NATURAL", "FULL").contains(next.value.toUpperCase(Locale.ROOT))))
                    alias = identifier();
            }
            String key = (alias.isEmpty() ? name : alias).toLowerCase(Locale.ROOT);
            if (tables.putIfAbsent(key, new Table(name)) != null)
                throw new IllegalArgumentException("表名或别名重复，请为各表设置不同的别名。");
            return sql + (alias.isEmpty() ? "" : " AS " + quote(alias));
        }
        Table owner(Column column) {
            if (column.qualifier.isEmpty()) {
                if (tables.size() != 1)
                    throw new IllegalArgumentException("多表查询中的 decr 字段及 hash 条件字段必须带表名或别名。");
                return tables.values().iterator().next();
            }
            Table table = tables.get(column.qualifier.toLowerCase(Locale.ROOT));
            if (table == null) throw new IllegalArgumentException("无法确定隐私字段所属的表。");
            return table;
        }
        String unsigned() {
            Token t = tokens.get(index++);
            if (!t.kind.equals("number") || !t.value.matches("[0-9]{1,9}")) throw invalid();
            return t.value;
        }
        String expression(int depth) {
            if (depth > 32) throw invalid();
            String left = conjunction(depth);
            while (take("OR")) left += " OR " + conjunction(depth);
            return left;
        }
        String conjunction(int depth) {
            String left = predicate(depth);
            while (take("AND")) left += " AND " + predicate(depth);
            return left;
        }
        String predicate(int depth) {
            if (take("(")) { String value = expression(depth + 1); need(")"); return "(" + value + ")"; }
            Column c = column(); String sql = c.sql;
            if (take("IS")) { boolean not = take("NOT"); need("NULL"); return sql + (not ? " IS NOT NULL" : " IS NULL"); }
            boolean not = take("NOT");
            if (take("IN")) {
                need("("); List<String> values = new ArrayList<>();
                do { values.add(literal(true, c)); } while (take(","));
                need(")"); return sql + (not ? " NOT IN (" : " IN (") + String.join(", ", values) + ")";
            }
            if (take("LIKE")) return sql + (not ? " NOT LIKE " : " LIKE ") + literal(false, c);
            if (not) throw invalid();
            String operator = tokens.get(index++).value;
            if (!Set.of("=", "!=", "<>", "<", ">", "<=", ">=").contains(operator)) throw invalid();
            for (String constant : List.of("NULL", "TRUE", "FALSE")) {
                if (take(constant)) return sql + " " + operator + " " + constant;
            }
            Token next = tokens.get(index);
            if ((next.kind.equals("word") || next.kind.equals("quoted")) && !at("hash"))
                return sql + " " + operator + " " + column().sql;
            return sql + " " + operator + " " + literal(Set.of("=", "!=", "<>").contains(operator), c);
        }
        String literal(boolean allowHash, Column column) {
            if (allowHash && take("hash")) {
                need("(");
                Token value = tokens.get(index++);
                if (!value.kind.equals("string")) throw invalid();
                String scope;
                if (take(",")) {
                    Token explicit = tokens.get(index++);
                    if (!explicit.kind.equals("string") || explicit.value.isBlank()) throw invalid();
                    scope = explicit.value;
                } else scope = null;
                need(")");
                parameters.add(new PendingLookup(column, scope, value.value));
                return "?";
            }
            Token t = tokens.get(index++);
            if (t.kind.equals("string")) parameters.add(t.value);
            else if (t.kind.equals("number")) parameters.add(new BigDecimal(t.value));
            else throw invalid();
            return "?";
        }
    }
}
