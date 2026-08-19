package bb.apigol.ws;

import bb.apigol.json.Json;
import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.websocket.ClientEndpointConfig;
import javax.websocket.CloseReason;
import javax.websocket.ContainerProvider;
import javax.websocket.Endpoint;
import javax.websocket.EndpointConfig;
import javax.websocket.MessageHandler;
import javax.websocket.Session;
import javax.websocket.WebSocketContainer;

public class GolWebSocketClient {
    public static final String WS_URL = "wss://gol.intranet.bb.com.br/_frames/live?";
    public static final String ORIGIN = "https://gol.intranet.bb.com.br";
    public static final String BLOCOS_PADRAO = "{3030,3031}";
    private static final int MAX_BUFFER = 0x4000000;
    private final String cookieHeader;
    private final int timeoutSec;

    public GolWebSocketClient(String cookieHeader, int timeoutSec) {
        this.cookieHeader = cookieHeader;
        this.timeoutSec = timeoutSec <= 0 ? 20 : timeoutSec;
    }

    public Map<String, Object> consultar(List<String> topicos, String prefixo, String carteira) throws Exception {
        return this.consultar(topicos, prefixo, carteira, BLOCOS_PADRAO);
    }

    public Map<String, Object> consultar(List<String> topicos, String prefixo, String carteira, String blocos) throws Exception {
        String blocosEfetivos = blocos == null || blocos.isEmpty() ? BLOCOS_PADRAO : blocos;
        LinkedHashMap<String, String> topicoBlocos = new LinkedHashMap<String, String>();
        for (String topico : topicos) {
            topicoBlocos.put(topico, blocosEfetivos);
        }
        return this.consultarMulti(topicoBlocos, prefixo, carteira);
    }

    public Map<String, Object> consultarMulti(final LinkedHashMap<String, String> topicoBlocos, final String prefixo, final String carteira) throws Exception {
        final ConcurrentHashMap<String, Object> resultados = new ConcurrentHashMap<String, Object>();
        final Set<String> pendentes = ConcurrentHashMap.newKeySet();
        pendentes.addAll(topicoBlocos.keySet());
        final CountDownLatch latch = new CountDownLatch(1);
        ClientEndpointConfig.Configurator configurator = new ClientEndpointConfig.Configurator() {

            public void beforeRequest(Map<String, List<String>> headers) {
                headers.put("Cookie", Collections.singletonList(GolWebSocketClient.this.cookieHeader));
                headers.put("Origin", Collections.singletonList(GolWebSocketClient.ORIGIN));
            }
        };
        ClientEndpointConfig config = ClientEndpointConfig.Builder.create().configurator(configurator).build();
        Endpoint endpoint = new Endpoint() {

            public void onOpen(Session session, EndpointConfig endpointConfig) {
                final StringBuilder buffer = new StringBuilder();
                session.addMessageHandler((MessageHandler) new MessageHandler.Partial<String>() {

                    public void onMessage(String parte, boolean ultima) {
                        buffer.append(parte);
                        if (ultima) {
                            String mensagem = buffer.toString();
                            buffer.setLength(0);
                            GolWebSocketClient.handle(mensagem, resultados, pendentes, latch);
                        }
                    }
                });
                try {
                    int n = 1;
                    for (Map.Entry<String, String> entry : topicoBlocos.entrySet()) {
                        String params = GolWebSocketClient.montarParams(prefixo, carteira, entry.getValue());
                        session.getBasicRemote().sendText("subscribe:" + n++ + ":" + entry.getKey() + ":" + params);
                    }
                }
                catch (Exception e) {
                    latch.countDown();
                }
            }

            public void onError(Session session, Throwable throwable) {
                latch.countDown();
            }

            public void onClose(Session session, CloseReason closeReason) {
                latch.countDown();
            }
        };
        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        try {
            container.setDefaultMaxTextMessageBufferSize(MAX_BUFFER);
        }
        catch (Exception e) {
            // container pode nao permitir ajuste; segue com o padrao
        }
        try {
            container.setDefaultMaxSessionIdleTimeout((long) this.timeoutSec * 1000L);
        }
        catch (Exception e) {
            // idem
        }
        Session session = container.connectToServer(endpoint, config, URI.create(WS_URL));
        try {
            latch.await(this.timeoutSec, TimeUnit.SECONDS);
        }
        finally {
            try {
                if (session.isOpen()) {
                    session.close(new CloseReason(CloseReason.CloseCodes.NORMAL_CLOSURE, "fim"));
                }
            }
            catch (Exception e) {
                // encerramento best-effort
            }
        }
        return resultados;
    }

    public Object consultarUm(String topico, String prefixo, String carteira) throws Exception {
        return this.consultar(Collections.singletonList(topico), prefixo, carteira).get(topico);
    }

    public Object consultarUm(String topico, String prefixo, String carteira, String blocos) throws Exception {
        return this.consultar(Collections.singletonList(topico), prefixo, carteira, blocos).get(topico);
    }

    private static String montarParams(String prefixo, String carteira, String blocos) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"prefixo\":\"").append(prefixo).append("\",\"blocos\":\"").append(blocos).append("\"");
        if (carteira != null && !carteira.isEmpty()) {
            sb.append(",\"carteira\":\"").append(carteira).append("\"");
        }
        sb.append("}");
        return sb.toString();
    }

    private static void handle(String mensagem, Map<String, Object> resultados, Set<String> pendentes, CountDownLatch latch) {
        Object json;
        try {
            json = Json.parse(mensagem);
        }
        catch (Exception e) {
            return;
        }
        if (!(json instanceof Map)) {
            return;
        }
        Map<?, ?> obj = (Map<?, ?>) json;
        Object event = obj.get("event");
        if (event == null) {
            return;
        }
        String eventoStr = event.toString();
        if ("handshake".equals(eventoStr)) {
            return;
        }
        int barra = eventoStr.lastIndexOf(47);
        if (barra > 0) {
            String topico = eventoStr.substring(0, barra);
            if (pendentes.contains(topico)) {
                resultados.put(topico, obj.get("data"));
                pendentes.remove(topico);
                if (pendentes.isEmpty()) {
                    latch.countDown();
                }
            }
        }
    }
}
