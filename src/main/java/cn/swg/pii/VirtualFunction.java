package cn.swg.pii;

final class VirtualFunction {
    static boolean isVirtualName(String name) { return "decr".equalsIgnoreCase(name) || "hash".equalsIgnoreCase(name); }
    static boolean present(String sql) {
        if (sql == null) return false;
        for (int i = 0; i < sql.length();) {
            char c = sql.charAt(i);
            if (c == '#' || (c == '-' && i + 2 < sql.length() && sql.charAt(i + 1) == '-' && Character.isWhitespace(sql.charAt(i + 2)))) {
                while (i < sql.length() && sql.charAt(i) != '\n') i++;
            } else if (c == '/' && i + 1 < sql.length() && sql.charAt(i + 1) == '*') {
                int end = sql.indexOf("*/", i + 2);
                // Executable MySQL comments must not bypass the restricted parser.
                if ((sql.startsWith("/*!", i) || sql.startsWith("/*M!", i)) && present(sql.substring(i + 3, end < 0 ? sql.length() : end))) return true;
                i = end < 0 ? sql.length() : end + 2;
            } else if (c == '\'' || c == '"' || c == '`') {
                char quote = c; StringBuilder identifier = new StringBuilder(); i++;
                while (i < sql.length()) {
                    c = sql.charAt(i++);
                    if (c == '\\' && i < sql.length()) { i++; continue; }
                    if (c == quote) {
                        if (i < sql.length() && sql.charAt(i) == quote) { identifier.append(c); i++; }
                        else break;
                    } else identifier.append(c);
                }
                if (quote == '`' && isVirtualName(identifier.toString()) && callFollows(sql, i)) return true;
            } else if (Character.isLetter(c) || c == '_') {
                int start = i++;
                while (i < sql.length() && (Character.isLetterOrDigit(sql.charAt(i)) || sql.charAt(i) == '_' || sql.charAt(i) == '$')) i++;
                if (isVirtualName(sql.substring(start, i)) && callFollows(sql, i)) return true;
            } else i++;
        }
        return false;
    }
    private static boolean callFollows(String sql, int i) {
        while (i < sql.length()) {
            if (Character.isWhitespace(sql.charAt(i))) { i++; continue; }
            if (sql.startsWith("/*", i)) {
                int end = sql.indexOf("*/", i + 2);
                if (end < 0) return false;
                i = end + 2; continue;
            }
            return sql.charAt(i) == '(';
        }
        return false;
    }

}
