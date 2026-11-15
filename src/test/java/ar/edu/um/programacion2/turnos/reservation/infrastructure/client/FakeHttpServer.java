package ar.edu.um.programacion2.turnos.reservation.infrastructure.client;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Servidor HTTP simulado con el servidor del JDK ({@code com.sun.net.httpserver}), como el {@code FakeCatedraServer} del
 * catálogo: abre un socket real en {@code 127.0.0.1} y un puerto libre, así se pueden probar timeouts, cortes y el
 * servidor apagado, que un mock sin red no cubre. Hace de cátedra o de catálogo: registra cada pedido y responde lo que se
 * le configure.
 */
public final class FakeHttpServer implements AutoCloseable {

    /** Pedido tal como llegó: método, ruta, query, las cabeceras que interesan y el cuerpo. */
    public record ReceivedRequest(
        String method,
        String path,
        String query,
        String authorization,
        String userLogin,
        String contentType,
        String body
    ) {}

    private interface Behavior {
        void handle(HttpExchange exchange) throws IOException, InterruptedException;
    }

    /** Tope para los handlers colgados: si un test no cierra el servidor, igual terminan. */
    private static final long MAX_HANG_SECONDS = 10;

    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final CountDownLatch released = new CountDownLatch(1);
    private final List<ReceivedRequest> requests = new CopyOnWriteArrayList<>();
    private volatile Behavior behavior = exchange -> send(exchange, 200, "application/json", "{}", Map.of());
    /** Respuestas por ruta, para hacer de cátedra en un flujo con varias llamadas (hold y confirm). Tienen prioridad. */
    private final Map<String, Behavior> routes = new java.util.concurrent.ConcurrentHashMap<>();
    /** Lo que pasa cuando llega un pedido a una ruta, antes de responderlo: simula lo que ocurre "mientras tanto". */
    private final Map<String, Runnable> hooks = new java.util.concurrent.ConcurrentHashMap<>();
    /** Si el handler lee el cuerpo antes de comportarse (el registro siempre lo tiene, salvo en hangWithoutReading). */
    private volatile boolean readBody = true;

    private FakeHttpServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        server.start();
    }

    public static FakeHttpServer start() throws IOException {
        return new FakeHttpServer();
    }

    /** Una URL en la que nadie escucha: el puerto se abrió y se cerró recién. */
    public static URI closedPortUrl() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return URI.create("http://127.0.0.1:" + socket.getLocalPort());
        }
    }

    public URI baseUrl() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    public List<ReceivedRequest> requests() {
        return List.copyOf(requests);
    }

    public ReceivedRequest onlyRequest() {
        if (requests.size() != 1) {
            throw new AssertionError("Se esperaba un solo pedido y llegaron " + requests.size());
        }
        return requests.getFirst();
    }

    public void respondJson(int status, String body) {
        respond(status, "application/json", body, Map.of());
    }

    public void respond(int status, String contentType, String body, Map<String, String> headers) {
        behavior = exchange -> send(exchange, status, contentType, body, headers);
    }

    /** Los pedidos cuya ruta termina así reciben esta respuesta, en vez de la general. */
    public void routeJson(String pathSuffix, int status, String body) {
        routes.put(pathSuffix, exchange -> send(exchange, status, "application/json", body, Map.of()));
    }

    public void routeProblem(String pathSuffix, int status, String code) {
        String body = "{\"title\":\"Error\",\"status\":" + status + ",\"detail\":\"detalle libre que no se usa\",\"code\":\"" + code + "\"}";
        routes.put(pathSuffix, exchange -> send(exchange, status, "application/problem+json", body, Map.of()));
    }

    public void routeWithoutBody(String pathSuffix, int status) {
        routes.put(pathSuffix, exchange -> {
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
    }

    /** Ejecuta la acción cuando llega un pedido a esa ruta, antes de responderlo. */
    public void beforeResponding(String pathSuffix, Runnable action) {
        hooks.put(pathSuffix, action);
    }

    /** Vuelve al estado inicial: sin rutas ni ganchos, y con el registro de pedidos vacío. */
    public void reset() {
        routes.clear();
        hooks.clear();
        requests.clear();
        behavior = exchange -> send(exchange, 200, "application/json", "{}", Map.of());
    }

    /** Los pedidos que llegaron a las rutas que terminan así. */
    public List<ReceivedRequest> requestsTo(String pathSuffix) {
        return requests.stream().filter(request -> request.path().endsWith(pathSuffix)).toList();
    }

    /** Un {@code problem+json} con un {@code detail} libre, que el cliente nunca tiene que usar. */
    public void respondProblem(int status, String code) {
        String body =
            "{\"title\":\"Error\",\"status\":" + status + ",\"detail\":\"detalle libre que no se usa\",\"code\":\"" + code + "\"}";
        respond(status, "application/problem+json", body, Map.of());
    }

    public void respondWithoutBody(int status) {
        behavior = exchange -> {
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        };
    }

    public void redirectTo(String location) {
        behavior = exchange -> {
            exchange.getResponseHeaders().add("Location", location);
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        };
    }

    /** Lee el pedido completo y no responde hasta que se cierre el servidor: la operación llegó y quedó colgada. */
    public void readBodyThenHang() {
        behavior = exchange -> released.await(MAX_HANG_SECONDS, TimeUnit.SECONDS);
    }

    /** No lee el cuerpo ni responde: el pedido puede no haber llegado entero. */
    public void hangWithoutReading() {
        readBody = false;
        behavior = exchange -> released.await(MAX_HANG_SECONDS, TimeUnit.SECONDS);
    }

    /** Lee el pedido y cierra la conexión sin responder nada. */
    public void closeWithoutResponding() {
        behavior = HttpExchange::close;
    }

    /** Manda las cabeceras y la mitad del cuerpo, y cierra la conexión. */
    public void cutInTheMiddleOfTheBody(int status, String body) {
        behavior = exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            OutputStream out = exchange.getResponseBody();
            out.write(bytes, 0, bytes.length / 2);
            out.flush();
            exchange.close();
        };
    }

    private void handle(HttpExchange exchange) throws IOException {
        String body = readBody ? new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8) : null;
        requests.add(
            new ReceivedRequest(
                exchange.getRequestMethod(),
                exchange.getRequestURI().getRawPath(),
                exchange.getRequestURI().getRawQuery(),
                exchange.getRequestHeaders().getFirst("Authorization"),
                exchange.getRequestHeaders().getFirst("X-User-Login"),
                exchange.getRequestHeaders().getFirst("Content-Type"),
                body
            )
        );
        String path = exchange.getRequestURI().getRawPath();
        hooks.entrySet().stream().filter(hook -> path.endsWith(hook.getKey())).forEach(hook -> hook.getValue().run());
        Behavior current = routes.entrySet().stream().filter(route -> path.endsWith(route.getKey())).map(Map.Entry::getValue).findFirst().orElse(behavior);
        try {
            current.handle(exchange);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void send(HttpExchange exchange, int status, String contentType, String body, Map<String, String> headers)
        throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", contentType);
        headers.forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Override
    public void close() {
        released.countDown();
        server.stop(0);
        executor.shutdownNow();
    }
}
