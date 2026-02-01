# Java ComfyUI Client

A robust and modern Java client library for interacting with [ComfyUI](https://github.com/comfyanonymous/ComfyUI). This library allows you to programmatically submit workflows, monitor execution progress via WebSockets, and retrieve generated images.

## Features

- **WebSocket Integration**: Real-time monitoring of execution status (queue, progress, node completion).
- **Workflow Management**: Load and parse ComfyUI workflow JSON files.
- **Dynamic Input Modification**: Easily modify node parameters (seeds, dimensions, text) using the `NodeMapper` utility without manually traversing complex JSON structures.
- **Image Retrieval**: Automatically fetches generated images upon workflow completion.
- **Modern Java**: Built with Java 25 features and standard `java.net.http.HttpClient`.
- **Precision Handling**: Custom JSON serialization to handle floating-point precision correctly.

## Prerequisites

- **Java 25** (or compatible JDK with preview features enabled if needed)
- **Maven 3.6+**
- A running instance of **ComfyUI** (Default: `localhost:9712`)

## Installation

1. Clone the repository:
   ```bash
   git clone https://github.com/yourusername/java-comfyui-client.git
   ```
2. Build the project using Maven:
   ```bash
   mvn clean install
   ```

## Usage

### 1. Initialize the Client
```java
ComfyUIClient client = new ComfyUIClient();
client.connect(); // Connects to WebSocket at localhost:9712
```

### 2. Load and Modify a Workflow
Use `NodeMapper` to find nodes by title and update their inputs dynamically.
```java
// Load workflow from file
ObjectNode workflow = client.loadWorkflow("path/to/workflow.json");

// Update the seed for a KSampler node
long newSeed = NodeMapper.generateSeed();
NodeMapper.updateNodeInput(workflow, "4", "seed", newSeed); 

// Or find node by title if ID is unknown
String kSamplerId = NodeMapper.findNodeIdByTitle(workflow, "KSampler").orElseThrow();
NodeMapper.updateNodeInput(workflow, kSamplerId, "steps", 25);
```

### 3. Create Optional Handlers/Listeners
```java
ComfyUIClient.ComfyHandler comfyHandler = new ComfyUIClient.ComfyHandler() {
    @Override
    public void onOpen() {
        System.out.println("Connected to WebSocket");
    }

    @Override
    public void onSid(String sid) {
        System.out.println("Received SID: " + sid);
    }

    @Override
    public void onQueueStatus(int remaining) {
        System.out.println("Queue status: " + remaining + " remaining");
    }

    @Override
    public void onClose(int statusCode, String reason) {
        System.out.println("Connection closed: " + statusCode + " " + reason);
    }

    @Override
    public void onError(Throwable error) {
        System.out.println("Error: " + error.getMessage());
    }
};

ComfyUIClient.GenerationHandler generationHandler = new ComfyUIClient.GenerationHandler() {
    @Override
    public void onStart() {
        System.out.println("Generation started");
    }

    @Override
    public void onNode(String nodeId) {
        System.out.println("Node completed: " + nodeId);
    }

    @Override
    public void onDownloadImage(byte[] imageData) {
        System.out.println("Downloaded image: " + imageData.length + " bytes");
    }

    @Override
    public void onCompleted() {
        System.out.println("Generation completed");
    }

    @Override
    public void onProgress(int value, int max) {
        System.out.println("Progress: " + value + "/" + max);
    }

    @Override
    public void onError(IOException ex) {
        System.out.println("Error: " + ex.getMessage());
    }
};
```

### 4. Execute and Wait
Submit the workflow and wait for the specific prompt to complete.
```java
// Block until this specific prompt finishes execution
// Use GenerationHandler.onCompleted() to detect the workflow has completed.
String promptId = client.prompt(workflow, generationHandler);
```

### 5. Cleanup
```java
client.disconnect();
```

## Configuration

By default, the client attempts to connect to:
- **API**: `http://localhost:9712`
- **WebSocket**: `ws://localhost:9712/ws`

*Note: Ensure your ComfyUI instance is running on the correct port or modify `ComfyUIClient.java` constants if your setup differs.*

## Project Structure

- `ComfyUIClient.java`: Main entry point for API interaction and WebSocket handling.
- `NodeMapper.java`: Helper class for traversing and modifying ComfyUI workflow JSON.
- `src/main/resources/sample-workflow.json`: Example workflow for testing.

## License

[MIT License](LICENSE)
