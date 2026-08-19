package bb.apigol.web;

import java.util.Arrays;
import java.util.List;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;

public final class GolSession {
    private static final List<String> COOKIES_RELEVANTES = Arrays.asList("BBSSOToken", "chaveFuncionario", "ssoacr", "ssoamlbcookie");

    public static String cookieHeader(HttpServletRequest httpServletRequest) {
        String string = httpServletRequest.getHeader("X-GOL-Cookie");
        if (string != null && !string.isEmpty()) {
            return string;
        }
        StringBuilder stringBuilder = new StringBuilder();
        Cookie[] cookieArray = httpServletRequest.getCookies();
        if (cookieArray != null) {
            for (Cookie cookie : cookieArray) {
                if (!COOKIES_RELEVANTES.contains(cookie.getName())) continue;
                if (stringBuilder.length() > 0) {
                    stringBuilder.append("; ");
                }
                stringBuilder.append(cookie.getName()).append("=").append(cookie.getValue());
            }
        }
        if (stringBuilder.indexOf("ssoacr=") < 0) {
            if (stringBuilder.length() > 0) {
                stringBuilder.append("; ");
            }
            stringBuilder.append("ssoacr=sso.intranet.bb.com.br");
        }
        if (stringBuilder.indexOf("ssoamlbcookie=") < 0) {
            if (stringBuilder.length() > 0) {
                stringBuilder.append("; ");
            }
            stringBuilder.append("ssoamlbcookie=30");
        }
        return stringBuilder.toString();
    }

    private GolSession() {
    }
}
