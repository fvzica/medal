package bb.apigol.ws;

import bb.apigol.json.Json;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class GolHttpClient {
    private static final String BASE = "https://gol.intranet.bb.com.br/_frames/get";
    private final String cookieHeader;
    private final int timeoutMs;

    public GolHttpClient(String string, int n) {
        this.cookieHeader = string;
        this.timeoutMs = (n <= 0 ? 20 : n) * 1000;
    }

    public List<Map<String, Object>> getData(String string, String string2) {
        try {
            Object v;
            String string3;
            InputStream inputStream;
            String string4 = "https://gol.intranet.bb.com.br/_frames/get/" + string + (string2 == null ? "" : "?" + string2);
            HttpURLConnection httpURLConnection = (HttpURLConnection)new URL(string4).openConnection();
            httpURLConnection.setRequestMethod("GET");
            httpURLConnection.setConnectTimeout(this.timeoutMs);
            httpURLConnection.setReadTimeout(this.timeoutMs);
            httpURLConnection.setRequestProperty("Accept", "application/json, text/plain, */*");
            httpURLConnection.setRequestProperty("Cookie", this.cookieHeader);
            httpURLConnection.setRequestProperty("Referer", "https://gol.intranet.bb.com.br/");
            int n = httpURLConnection.getResponseCode();
            InputStream inputStream2 = inputStream = n >= 200 && n < 400 ? httpURLConnection.getInputStream() : httpURLConnection.getErrorStream();
            if (inputStream == null) {
                return Collections.emptyList();
            }
            StringBuilder stringBuilder = new StringBuilder();
            BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
            while ((string3 = bufferedReader.readLine()) != null) {
                stringBuilder.append(string3);
            }
            bufferedReader.close();
            Object object = Json.parse(stringBuilder.toString());
            if (object instanceof Map && (v = ((Map)object).get("data")) instanceof List) {
                return (List)v;
            }
            return Collections.emptyList();
        }
        catch (Exception exception) {
            return Collections.emptyList();
        }
    }

    public List<Map<String, Object>> listaJurisdicao(String string) {
        return this.getData("web/gol2/_paineis/lista-jurisdicao", "prefixo=" + string);
    }
}
