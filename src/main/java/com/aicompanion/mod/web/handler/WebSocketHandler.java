package com.aicompanion.mod.web.handler;

import com.aicompanion.mod.AICompanionMod;
import com.aicompanion.mod.web.WebServer;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketError;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * WebSocket handler for real-time communication with the web interface
 */
@WebSocket
public class WebSocketHandler {
    private static final Gson gson = new Gson();
    private Session session;
    private UUID clientId;

    /**
     * Handle new WebSocket connection
     */
    @OnWebSocketConnect
    public void onOpen(Session session) {
        this.session = session;
        this.clientId = UUID.randomUUID();
        
        // For the Jetty implementation, we'll check the token from the query parameters directly
        // This simplifies the authentication logic and avoids getServletAttributes() which is not available
        boolean authenticated = false;
        String token = null;
        
        // Get token from query parameter if present
        if (session.getUpgradeRequest().getParameterMap().containsKey("token")) {
            token = session.getUpgradeRequest().getParameterMap().get("token").get(0);
            
            try {
                if (token != null && !token.isEmpty() && com.aicompanion.mod.web.security.JWTManager.getInstance().isTokenValid(token)) {
                    authenticated = true;
                }
            } catch (Exception e) {
                AICompanionMod.LOGGER.error("Error validating WebSocket token", e);
            }
        }
        
        // Store authentication status in static map for future checks
        WebSocketHandlerConfig.setSessionAuthenticated(session, authenticated);
        
        // Check if authentication was successful
        if (!authenticated) {
            // Not authenticated, close the connection
            try {
                JsonObject message = new JsonObject();
                message.addProperty("type", "error");
                message.addProperty("message", "Authentication failed");
                session.getRemote().sendString(gson.toJson(message));
                session.close(1008, "Authentication failed");
                return;
            } catch (IOException e) {
                AICompanionMod.LOGGER.error("Error closing unauthenticated WebSocket", e);
            }
            return;
        }
        
        // Register client with the web server
        WebServer.getInstance().addClient(clientId, new WebSocketSessionAdapter(session));
        
        // Send welcome message
        JsonObject message = new JsonObject();
        message.addProperty("type", "connection");
        message.addProperty("clientId", clientId.toString());
        message.addProperty("message", "Connected to AI Companion WebSocket server");
        
        try {
            session.getRemote().sendString(gson.toJson(message));
        } catch (IOException e) {
            AICompanionMod.LOGGER.error("Error sending welcome message", e);
        }
    }

    /**
     * Handle WebSocket message
     */
    @OnWebSocketMessage
    public void onMessage(String message, Session session) {
        try {
            JsonObject json = JsonParser.parseString(message).getAsJsonObject();
            String type = json.get("type").getAsString();
            
            switch (type) {
                case "ping":
                    handlePing(json);
                    break;
                    
                case "command":
                    handleCommand(json);
                    break;
                    
                default:
                    sendError("Unknown message type: " + type);
                    break;
            }
        } catch (Exception e) {
            sendError("Error processing message: " + e.getMessage());
            AICompanionMod.LOGGER.error("Error processing WebSocket message", e);
        }
    }

    /**
     * Handle WebSocket close
     */
    @OnWebSocketClose
    public void onClose(Session session, int statusCode, String reason) {
        // Unregister client from the web server
        WebServer.getInstance().removeClient(clientId);
        AICompanionMod.LOGGER.info("WebSocket connection closed");
    }

    /**
     * Handle WebSocket error
     */
    @OnWebSocketError
    public void onError(Session session, Throwable throwable) {
        AICompanionMod.LOGGER.error("WebSocket error", throwable);
    }

    /**
     * Handle ping message
     */
    private void handlePing(JsonObject json) {
        JsonObject response = new JsonObject();
        response.addProperty("type", "pong");
        response.addProperty("timestamp", System.currentTimeMillis());
        
        try {
            session.getRemote().sendString(gson.toJson(response));
        } catch (IOException e) {
            AICompanionMod.LOGGER.error("Error sending pong message", e);
        }
    }

    /**
     * Handle command message
     */
    private void handleCommand(JsonObject json) {
        String command = json.get("command").getAsString();
        AICompanionMod.LOGGER.info("WebSocket command received: " + command);
        
        // Handle specific commands
        if (command.equals("skin") && json.has("companionId") && json.has("skinType")) {
            String companionId = json.get("companionId").getAsString();
            String skinType = json.get("skinType").getAsString();
            String skinPath = json.has("skinPath") ? json.get("skinPath").getAsString() : "";
            
            // Process the skin command
            boolean success = processSkinCommand(companionId, skinType, skinPath);
            
            JsonObject response = new JsonObject();
            response.addProperty("type", "command_result");
            response.addProperty("command", command);
            response.addProperty("success", success);
            response.addProperty("message", success ? 
                    "Skin updated successfully" : 
                    "Failed to update skin, companion not found or error occurred");
            
            try {
                session.getRemote().sendString(gson.toJson(response));
            } catch (IOException e) {
                AICompanionMod.LOGGER.error("Error sending skin command result", e);
            }
            return;
        }
        
        // Send acknowledgment for unhandled commands
        JsonObject response = new JsonObject();
        response.addProperty("type", "command_ack");
        response.addProperty("command", command);
        response.addProperty("status", "received");
        
        try {
            session.getRemote().sendString(gson.toJson(response));
        } catch (IOException e) {
            AICompanionMod.LOGGER.error("Error sending command acknowledgment", e);
        }
    }
    
    /**
     * Process a skin change command
     * @param companionId Entity ID of the companion
     * @param skinType Type of skin (default, custom, etc.)
     * @param skinPath Path to skin file (if custom)
     * @return true if successful, false otherwise
     */
    private boolean processSkinCommand(String companionId, String skinType, String skinPath) {
        try {
            // For direct processing, we'd need to get the entity based on ID
            // This implementation depends on your entity management system
            
            // Convert the skinType and skinPath to character codes for BlockPos transfer
            int typeCode = skinType.charAt(0);
            int pathCode = skinPath.isEmpty() ? 0 : skinPath.charAt(0);
            
            // Schedule a task on the main server thread to process the command
            AICompanionMod.SERVER.execute(() -> {
                AICompanionMod.executeEntityCommand(
                    companionId, 
                    "skin", 
                    new net.minecraft.util.math.BlockPos(typeCode, pathCode, 0)
                );
            });
            
            return true;
        } catch (Exception e) {
            AICompanionMod.LOGGER.error("Error processing skin command", e);
            return false;
        }
    }

    /**
     * Send error message to client
     */
    private void sendError(String message) {
        JsonObject response = new JsonObject();
        response.addProperty("type", "error");
        response.addProperty("message", message);
        
        try {
            session.getRemote().sendString(gson.toJson(response));
        } catch (IOException e) {
            AICompanionMod.LOGGER.error("Error sending error message", e);
        }
    }

    /**
     * Adapter class to convert Session to WebSocketSession
     */
    private static class WebSocketSessionAdapter implements WebServer.WebSocketSession {
        private final Session session;
        
        public WebSocketSessionAdapter(Session session) {
            this.session = session;
        }
        
        @Override
        public boolean isOpen() {
            return session.isOpen();
        }
        
        @Override
        public void sendMessage(String message) throws IOException {
            session.getRemote().sendString(message);
        }
    }
}