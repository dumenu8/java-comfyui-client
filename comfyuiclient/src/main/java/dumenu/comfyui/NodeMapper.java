package dumenu.comfyui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

public class NodeMapper {

    private static Random random;

    /**
     * Finds the first node ID that matches the given title in the _meta metadata.
     * 
     * @param workflow The full workflow JSON object (API format)
     * @param title    The title to search for (e.g. "KSampler", "Load Image")
     * @return Optional containing the node ID string if found
     */
    public static Optional<String> findNodeIdByTitle(ObjectNode workflow, String title) {
        Iterator<Map.Entry<String, JsonNode>> fields = workflow.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String nodeId = entry.getKey();
            JsonNode nodeData = entry.getValue();

            if (nodeData.has("_meta") && nodeData.get("_meta").has("title")) {
                String nodeTitle = nodeData.get("_meta").get("title").asText();
                if (title.equals(nodeTitle)) {
                    return Optional.of(nodeId);
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Updates a specific input field for a node identified by its ID.
     * 
     * @param workflow  The full workflow JSON object
     * @param nodeId    The ID of the node to update
     * @param inputName The name of the input field (e.g. "width", "seed")
     * @param value     The new value to set (String, Integer, Double, Boolean,
     *                  Long)
     * @return true if the node was found and updated, false otherwise
     */
    public static boolean updateNodeInput(ObjectNode workflow, String nodeId, String inputName, Object value) {
        if (!workflow.has(nodeId)) {
            return false;
        }

        JsonNode node = workflow.get(nodeId);
        ObjectNode inputs;

        if (node.has("inputs")) {
            inputs = (ObjectNode) node.get("inputs");
        } else {
            // Should usually exist, but if not create it
            inputs = workflow.objectNode();
            ((ObjectNode) node).set("inputs", inputs);
        }

        if (value instanceof String) {
            inputs.put(inputName, (String) value);
        } else if (value instanceof Integer) {
            inputs.put(inputName, (Integer) value);
        } else if (value instanceof Long) {
            inputs.put(inputName, (Long) value);
        } else if (value instanceof Double) {
            inputs.put(inputName, (Double) value);
        } else if (value instanceof Boolean) {
            inputs.put(inputName, (Boolean) value);
        } else {
            // Fallback for other types or null
            inputs.putPOJO(inputName, value);
        }

        return true;
    }

    /**
     * Generate random seed for KSampler or related.
     *
     * @return generate a positive 64-bit long
     */
    public static long generateSeed() {
        if( random == null ) random = new Random();
        return Math.abs(random.nextLong());
    }
}
