package bb.apigol.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Json {
    public static Object parse(String string) {
        return new Parser(string).parseValue();
    }

    public static Map<String, Object> parseObject(String string) {
        Object object = Json.parse(string);
        return object instanceof Map ? (Map)object : new LinkedHashMap();
    }

    public static String write(Object object) {
        StringBuilder stringBuilder = new StringBuilder();
        Json.writeValue(stringBuilder, object);
        return stringBuilder.toString();
    }

    private static void writeValue(StringBuilder stringBuilder, Object object) {
        if (object == null) {
            stringBuilder.append("null");
            return;
        }
        if (object instanceof String) {
            Json.writeStr(stringBuilder, (String)object);
            return;
        }
        if (object instanceof Number || object instanceof Boolean) {
            stringBuilder.append(object.toString());
            return;
        }
        if (object instanceof Map) {
            stringBuilder.append('{');
            boolean bl = true;
            for (Map.Entry<?, ?> entry : ((Map<?, ?>)object).entrySet()) {
                if (!bl) {
                    stringBuilder.append(',');
                }
                bl = false;
                Json.writeStr(stringBuilder, (String)entry.getKey());
                stringBuilder.append(':');
                Json.writeValue(stringBuilder, entry.getValue());
            }
            stringBuilder.append('}');
            return;
        }
        if (object instanceof Iterable) {
            stringBuilder.append('[');
            boolean bl = true;
            for (Object t : (Iterable)object) {
                if (!bl) {
                    stringBuilder.append(',');
                }
                bl = false;
                Json.writeValue(stringBuilder, t);
            }
            stringBuilder.append(']');
            return;
        }
        Json.writeStr(stringBuilder, object.toString());
    }

    private static void writeStr(StringBuilder stringBuilder, String string) {
        stringBuilder.append('\"');
        block7: for (int i = 0; i < string.length(); ++i) {
            char c = string.charAt(i);
            switch (c) {
                case '\"': {
                    stringBuilder.append("\\\"");
                    continue block7;
                }
                case '\\': {
                    stringBuilder.append("\\\\");
                    continue block7;
                }
                case '\n': {
                    stringBuilder.append("\\n");
                    continue block7;
                }
                case '\r': {
                    stringBuilder.append("\\r");
                    continue block7;
                }
                case '\t': {
                    stringBuilder.append("\\t");
                    continue block7;
                }
                default: {
                    if (c < ' ') {
                        stringBuilder.append(String.format("\\u%04x", c));
                        continue block7;
                    }
                    stringBuilder.append(c);
                }
            }
        }
        stringBuilder.append('\"');
    }

    private Json() {
    }

    private static final class Parser {
        private final String s;
        private int i;

        Parser(String string) {
            this.s = string;
        }

        Object parseValue() {
            this.skipWs();
            char c = this.peek();
            switch (c) {
                case '{': {
                    return this.parseObj();
                }
                case '[': {
                    return this.parseArr();
                }
                case '\"': {
                    return this.parseStr();
                }
                case 'f': 
                case 't': {
                    return this.parseBool();
                }
                case 'n': {
                    this.expect("null");
                    return null;
                }
            }
            return this.parseNum();
        }

        private Map<String, Object> parseObj() {
            char c;
            LinkedHashMap<String, Object> linkedHashMap = new LinkedHashMap<String, Object>();
            this.expect('{');
            this.skipWs();
            if (this.peek() == '}') {
                ++this.i;
                return linkedHashMap;
            }
            do {
                this.skipWs();
                String string = this.parseStr();
                this.skipWs();
                this.expect(':');
                linkedHashMap.put(string, this.parseValue());
                this.skipWs();
            } while ((c = this.next()) == ',');
            if (c != '}') {
                throw this.err("esperado , ou }");
            }
            return linkedHashMap;
        }

        private List<Object> parseArr() {
            char c;
            ArrayList<Object> arrayList = new ArrayList<Object>();
            this.expect('[');
            this.skipWs();
            if (this.peek() == ']') {
                ++this.i;
                return arrayList;
            }
            do {
                arrayList.add(this.parseValue());
                this.skipWs();
            } while ((c = this.next()) == ',');
            if (c != ']') {
                throw this.err("esperado , ou ]");
            }
            return arrayList;
        }

        private String parseStr() {
            char c;
            this.expect('\"');
            StringBuilder stringBuilder = new StringBuilder();
            while ((c = this.next()) != '\"') {
                if (c == '\\') {
                    char c2 = this.next();
                    switch (c2) {
                        case '\"': {
                            stringBuilder.append('\"');
                            break;
                        }
                        case '\\': {
                            stringBuilder.append('\\');
                            break;
                        }
                        case '/': {
                            stringBuilder.append('/');
                            break;
                        }
                        case 'n': {
                            stringBuilder.append('\n');
                            break;
                        }
                        case 't': {
                            stringBuilder.append('\t');
                            break;
                        }
                        case 'r': {
                            stringBuilder.append('\r');
                            break;
                        }
                        case 'b': {
                            stringBuilder.append('\b');
                            break;
                        }
                        case 'f': {
                            stringBuilder.append('\f');
                            break;
                        }
                        case 'u': {
                            String string = this.s.substring(this.i, this.i + 4);
                            this.i += 4;
                            stringBuilder.append((char)Integer.parseInt(string, 16));
                            break;
                        }
                        default: {
                            stringBuilder.append(c2);
                            break;
                        }
                    }
                    continue;
                }
                stringBuilder.append(c);
            }
            return stringBuilder.toString();
        }

        private Object parseNum() {
            int n = this.i;
            while (this.i < this.s.length() && "+-0123456789.eE".indexOf(this.s.charAt(this.i)) >= 0) {
                ++this.i;
            }
            String string = this.s.substring(n, this.i);
            if (string.contains(".") || string.contains("e") || string.contains("E")) {
                return Double.parseDouble(string);
            }
            try {
                return Long.parseLong(string);
            }
            catch (NumberFormatException numberFormatException) {
                return Double.parseDouble(string);
            }
        }

        private Boolean parseBool() {
            if (this.peek() == 't') {
                this.expect("true");
                return Boolean.TRUE;
            }
            this.expect("false");
            return Boolean.FALSE;
        }

        private char peek() {
            return this.s.charAt(this.i);
        }

        private char next() {
            return this.s.charAt(this.i++);
        }

        private void expect(char c) {
            if (this.next() != c) {
                throw this.err("esperado '" + c + "'");
            }
        }

        private void expect(String string) {
            if (!this.s.startsWith(string, this.i)) {
                throw this.err("esperado '" + string + "'");
            }
            this.i += string.length();
        }

        private void skipWs() {
            while (this.i < this.s.length() && Character.isWhitespace(this.s.charAt(this.i))) {
                ++this.i;
            }
        }

        private RuntimeException err(String string) {
            return new RuntimeException("JSON @" + this.i + ": " + string);
        }
    }
}
