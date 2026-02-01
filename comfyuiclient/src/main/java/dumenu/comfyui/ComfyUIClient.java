package dumenu.comfyui;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.logging.Logger;

public class ComfyUIClient {
    private static final Logger logger = Logger.getLogger(ComfyUIClient.class.getName());
    private static final String HOST = "localhost";
    private static final int PORT = 9712;
    private static final String BASE_URL = "http://" + HOST + ":" + PORT;
    private static final String WS_URL = "ws://" + HOST + ":" + PORT + "/ws";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String clientId;
    private WebSocket webSocket;

    private ComfyHandler comfyHandler;
    private final Map<String, GenerationHandler> handlerMap = new ConcurrentHashMap<>();

    public interface ComfyHandler {
        void onOpen();

        void onSid(String sid);

        void onQueueStatus(int remaining);

        void onClose(int statusCode, String reason);

        void onError(Throwable error);
    }

    public interface GenerationHandler {
        void onStart();

        void onNode(String nodeId);

        void onDownloadImage(byte[] imageData);

        void onCompleted();

        void onProgress(int value, int max);

        void onError(IOException ex);
    }

    public ComfyUIClient() {
        this.httpClient = HttpClient.newHttpClient();
        this.clientId = UUID.randomUUID().toString();
        this.objectMapper = new ObjectMapper();

        // Register custom literal double serializer to avoid 1.0 -> 1.0000000000000002
        // issues
        SimpleModule module = new SimpleModule();
        module.addSerializer(Double.class, new DoubleSerializer());
        module.addSerializer(double.class, new DoubleSerializer());
        this.objectMapper.registerModule(module);
    }

    public ComfyUIClient(ComfyHandler handler) {
        this();
        this.comfyHandler = handler;
    }

    public void connect() {
        CountDownLatch latch = new CountDownLatch(1);
        httpClient.newWebSocketBuilder().buildAsync(URI.create(WS_URL + "?clientId=" + clientId), new WSListener()).thenAccept(s -> {
            webSocket = s;
            latch.countDown();
        });
        logger.info("Connected to WebSocket");

        try {
            latch.await();
        } catch (InterruptedException e) {
            if (comfyHandler != null)
                comfyHandler.onError(e);
        }
    }

    public void disconnect() {
        if (webSocket != null) {
            webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "Disconnecting");
        }
    }

    public String prompt(ObjectNode workflow, GenerationHandler handler) throws Exception {
        ObjectNode promptWrapper = objectMapper.createObjectNode();
        promptWrapper.set("prompt", workflow);
        promptWrapper.put("client_id", clientId);

        String json = objectMapper.writeValueAsString(promptWrapper);

        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(BASE_URL + "/prompt")).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json)).build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("Failed to submit prompt: " + response.body());
        }

        JsonNode responseNode = objectMapper.readTree(response.body());
        String promptId = responseNode.get("prompt_id").asText();
        handlerMap.put(promptId, handler);
        return promptId;
    }

    private byte[] downloadImage(String filename, String type, String subfolder) throws IOException {
        String url = String.format("%s/view?filename=%s&type=%s&subfolder=%s", BASE_URL, URLEncoder.encode(filename, StandardCharsets.UTF_8), URLEncoder.encode(type, StandardCharsets.UTF_8),
                URLEncoder.encode(subfolder, StandardCharsets.UTF_8));

        logger.info("Downloading image: " + url);

        HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url)).build();
        try {
            HttpResponse<byte[]> res = httpClient.send(req, HttpResponse.BodyHandlers.ofByteArray());
            return res.body();
        } catch (InterruptedException ex) {
            throw new RuntimeException(ex);
        }
    }

    // Custom serializer for doubles to ensure clean formatting
    public static class DoubleSerializer extends StdSerializer<Double> {
        public DoubleSerializer() {
            super(Double.class);
        }

        @Override
        public void serialize(Double value, JsonGenerator gen, SerializerProvider provider) throws IOException {
            if (value == null) {
                gen.writeNull();
            } else {
                // Use BigDecimal to strip trailing zeros and avoid scientific notation if
                // possible
                // or just write as raw value if it's an integer
                if (value == Math.floor(value) && !Double.isInfinite(value)) {
                    gen.writeNumber(value.longValue());
                } else {
                    // writing as BigDecimal can help, or simple toString
                    gen.writeNumber(BigDecimal.valueOf(value).stripTrailingZeros());
                }
            }
        }
    }

    public ObjectNode loadWorkflow(String filename) throws IOException {
        return (ObjectNode) objectMapper.readTree(Files.readString(Path.of(filename), StandardCharsets.UTF_8));
    }

    private class WSListener implements WebSocket.Listener {
        private final StringBuilder textBuffer = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            logger.info("[ComfyUIClient]:::WebSocket Opened");
            if (comfyHandler != null)
                comfyHandler.onOpen();
            WebSocket.Listener.super.onOpen(webSocket);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            logger.info("[ComfyUIClient]:::WebSocket Error Thrown: " + error.getMessage());
            if (comfyHandler != null)
                comfyHandler.onError(error);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            logger.info("[ComfyUIClient]:::WebSocket Closed: " + statusCode + ", " + reason);
            if (comfyHandler != null)
                comfyHandler.onClose(statusCode, reason);

            return WebSocket.Listener.super.onClose(webSocket, statusCode, reason);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            textBuffer.append(data);
            if (last) {
                String message = textBuffer.toString();
                textBuffer.setLength(0); // clear buffer
                handleMessage(message);
            }
            return WebSocket.Listener.super.onText(webSocket, data, last);
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            // Handle binary image data (e.g. from SaveImageWebsocket)
            // For simplicity, we might just log size or save if we knew context
            // But ComfyUI sends an 8-byte header (big endian type + little endian unknown?)
            // then image
            // For now, simply log receipt
            logger.info("Received binary message: " + data.remaining() + " bytes");
            return WebSocket.Listener.super.onBinary(webSocket, data, last);
        }

        private void handleMessage(String message) {
            try {
                JsonNode node = objectMapper.readTree(message);
                String type = node.path("type").asText();

                switch (type) {
                case "status":
                    String sid = node.path("data").path("sid").asText(null);
                    if (sid != null && comfyHandler != null) {
                        comfyHandler.onSid(sid);
                    }
                    var remaining = node.findPath("queue_remaining").asInt(-1);
                    if (remaining >= 0 && comfyHandler != null) {
                        comfyHandler.onQueueStatus(remaining);
                    }
                    break;

                case "execution_start":
                    String pid = node.path("data").path("prompt_id").asText();
                    logger.info("[ComfyUIClient]:::[" + pid + "]:::Execution started");
                    GenerationHandler handler = handlerMap.get(pid);
                    if (handler != null)
                        handler.onStart();
                    break;

                case "executing":
                    String nodeId = node.path("data").path("node").asText();
                    pid = node.path("data").path("prompt_id").asText();
                    if (nodeId != null && !nodeId.equals("null")) {
                        logger.info("[ComfyUIClient]:::[" + pid + "]:::Executing node: " + nodeId);
                        handler = handlerMap.get(pid);
                        if (handler != null)
                            handler.onNode(nodeId);
                    }
                    break;

                case "progress":
                    int value = node.path("data").path("value").asInt();
                    int max = node.path("data").path("max").asInt();
                    pid = node.path("data").path("prompt_id").asText();
                    logger.info("[ComfyUIClient]:::[" + pid + "]:::Progress: " + value + "/" + max);
                    handler = handlerMap.get(pid);
                    if (handler != null)
                        handler.onProgress(value, max);
                    break;

                case "executed":
                    logger.info("Node Executed: " + node.toPrettyString());
                    JsonNode data = node.path("data");
                    pid = data.path("prompt_id").asText();
                    JsonNode images = data.path("output").path("images");
                    if (images.isArray() && images.size() > 0) {
                        ObjectNode img = null;
                        String filename = null;
                        String filetype = null;
                        String subfolder = null;
                        handler = handlerMap.get(pid);
                        for (JsonNode imagNode : images) {
                            img = (ObjectNode) imagNode;
                            filename = img.path("filename").asText();
                            filetype = img.path("type").asText();
                            subfolder = img.path("subfolder").asText();
                            try {
                                byte[] imageData = downloadImage(filename, filetype, subfolder);
                                handler.onDownloadImage(imageData);
                            } catch (IOException ex) {
                                handler.onError(ex);
                            }
                        }
                        handler.onCompleted();
                    }
                    break;

                case "execution_success":
                    pid = node.findPath("prompt_id").asText();
                    handlerMap.remove(pid);
                    break;

                default:
                    break;
                }
            } catch (Exception e) {
                if (comfyHandler != null)
                    comfyHandler.onError(e);
                logger.severe("Error parsing WS message: " + e.getMessage());
            }
        }
    }

    public static void main(String[] args) {
        try {
            ComfyHandler comfyHandler = new ComfyHandler() {
                @Override
                public void onOpen() {
                    logger.info("[ComfyHandler]:::WebSocket opened");
                }

                @Override
                public void onSid(String sid) {
                    logger.info("[ComfyHandler]:::Sid: " + sid);
                }

                @Override
                public void onQueueStatus(int remaining) {
                    logger.info("[ComfyHandler]:::Queue status: " + remaining);
                }

                @Override
                public void onError(Throwable error) {
                    logger.info("[ComfyHandler]:::Error: " + error.getMessage());
                }

                @Override
                public void onClose(int statusCode, String reason) {
                    logger.info("[ComfyHandler]:::Closed: " + statusCode + ", " + reason);
                }
            };
            ComfyUIClient client = new ComfyUIClient(comfyHandler);
            client.connect();

            GenerationHandler generationHandler = new GenerationHandler() {
                @Override
                public void onStart() {
                    logger.info("[GenerationHandler]:::Execution started");
                }

                @Override
                public void onNode(String nodeId) {
                    logger.info("[GenerationHandler]:::Executing node: " + nodeId);
                }

                @Override
                public void onProgress(int value, int max) {
                    logger.info("[GenerationHandler]:::Progress: " + value + "/" + max);
                }

                @Override
                public void onDownloadImage(byte[] imageData) {
                    logger.info("[GenerationHandler]:::Downloaded image: " + imageData.length + " bytes");
                }

                @Override
                public void onCompleted() {
                    logger.info("[GenerationHandler]:::Workflow completed");
                }

                @Override
                public void onError(IOException exception) {
                    logger.info("[GenerationHandler]:::Exception: " + exception.getMessage());
                }
            };

            logger.info("Client started. Waiting for instructions (Workflow JSON needed to proceed).");

            ObjectNode workflow = client.loadWorkflow("comfyuiclient\\src\\main\\resources\\sample-workflow.json");
            // random the seed
            NodeMapper.updateNodeInput(workflow, "44", "seed", NodeMapper.generateSeed());
            String promptId = client.prompt(workflow, generationHandler);
            logger.info("Prompt submitted with ID: " + promptId);

            // wait for the workflow to complete (testing only)
            // use GenerationHandler.onCompleted() to know when the workflow is completed
            Thread.sleep(1000 * 20);

            // disconnect websocket
            client.disconnect();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
