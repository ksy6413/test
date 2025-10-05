package com.example;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.springframework.web.socket.sockjs.client.SockJsClient;
import org.springframework.web.socket.sockjs.client.Transport;
import org.springframework.web.socket.sockjs.client.WebSocketTransport;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.lang.reflect.Type;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A simple Java Swing GUI application for testing WebSocket STOMP messaging.
 * This client connects to a STOMP server, subscribes to a topic, sends a message,
 * and displays incoming data in a table, handling both initial snapshots and
 * incremental updates.
 */
public class StompClientGui extends JFrame {

    // --- UI Components ---
    private final JTextField endpointField = new JTextField("ws://localhost:8080/ws", 30);
    private final JTextField sendDestField = new JTextField("/app/request-data", 30);
    private final JTextField subscribeDestField = new JTextField("/user/queue/data", 30);
    private final JTextField traderField = new JTextField("TEST_TRADER", 30);
    private final JTextField bookOwnersField = new JTextField("OWNER1,OWNER2", 30);
    private final JButton submitButton = new JButton("Connect, Subscribe, and Send");
    private final JTable resultTable;
    private final DefaultTableModel tableModel;
    private final JTextArea logArea = new JTextArea(5, 30);

    // --- STOMP and State Management ---
    private WebSocketStompClient stompClient;
    private StompSession stompSession;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // A map to track which row in the table corresponds to which unique ID.
    private final Map<String, Integer> idToRowIndexMap = new ConcurrentHashMap<>();

    public StompClientGui() {
        super("WebSocket STOMP Test Client");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(800, 600);
        setLocationRelativeTo(null);

        // --- Table Setup ---
        String[] columnNames = {"ID", "Symbol", "Price", "Size", "Source"};
        tableModel = new DefaultTableModel(columnNames, 0);
        resultTable = new JTable(tableModel);

        // --- Layout Setup ---
        JPanel inputPanel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.anchor = GridBagConstraints.WEST;

        // Add input components to the panel
        addField(inputPanel, gbc, 0, "WebSocket Endpoint:", endpointField);
        addField(inputPanel, gbc, 1, "Send Destination:", sendDestField);
        addField(inputPanel, gbc, 2, "Subscribe Destination:", subscribeDestField);
        addField(inputPanel, gbc, 3, "Trader:", traderField);
        addField(inputPanel, gbc, 4, "Book Owners:", bookOwnersField);

        // --- Control Panel ---
        JPanel controlPanel = new JPanel(new FlowLayout(FlowLayout.CENTER));
        controlPanel.add(submitButton);

        // --- Main Layout ---
        JPanel topPanel = new JPanel(new BorderLayout());
        topPanel.add(inputPanel, BorderLayout.NORTH);
        topPanel.add(controlPanel, BorderLayout.CENTER);
        
        JScrollPane logScrollPane = new JScrollPane(logArea);
        logArea.setEditable(false);
        logArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
        
        JSplitPane splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(resultTable), logScrollPane);
        splitPane.setResizeWeight(0.7);

        getContentPane().setLayout(new BorderLayout());
        getContentPane().add(topPanel, BorderLayout.NORTH);
        getContentPane().add(splitPane, BorderLayout.CENTER);

        // --- Action Listener ---
        submitButton.addActionListener(e -> connectAndSendMessage());
    }

    private void addField(JPanel panel, GridBagConstraints gbc, int y, String label, JComponent component) {
        gbc.gridx = 0;
        gbc.gridy = y;
        gbc.gridwidth = 1;
        panel.add(new JLabel(label), gbc);

        gbc.gridx = 1;
        gbc.gridy = y;
        panel.add(component, gbc);
    }
    
    private void log(String message) {
        SwingUtilities.invokeLater(() -> logArea.append(message + "\n"));
    }

    private void connectAndSendMessage() {
        if (stompSession != null && stompSession.isConnected()) {
            log("Already connected. Please disconnect first.");
            // In a real app, you'd add a disconnect button.
            // For this simple version, we prevent reconnecting.
            return;
        }

        // Reset state
        tableModel.setRowCount(0);
        idToRowIndexMap.clear();
        logArea.setText("");

        // Use SockJS for broader compatibility, though StandardWebSocketClient is fine too.
        List<Transport> transports = Collections.singletonList(new WebSocketTransport(new StandardWebSocketClient()));
        SockJsClient sockJsClient = new SockJsClient(transports);
        this.stompClient = new WebSocketStompClient(sockJsClient);
        this.stompClient.setMessageConverter(new MappingJackson2MessageConverter());

        String url = endpointField.getText();
        log("Connecting to " + url + "...");

        try {
            stompClient.connect(url, new StompClientSessionHandler());
        } catch (Exception e) {
            log("Connection failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * A simple data class to represent the message payload.
     * Using @JsonIgnoreProperties to avoid errors if the server sends extra fields.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MarketData {
        private String id;
        private String symbol;
        private double price;
        private long size;
        private String source;

        // Getters and Setters are required for Jackson deserialization
        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getSymbol() { return symbol; }
        public void setSymbol(String symbol) { this.symbol = symbol; }
        public double getPrice() { return price; }
        public void setPrice(double price) { this.price = price; }
        public long getSize() { return size; }
        public void setSize(long size) { this.size = size; }
        public String getSource() { return source; }
        public void setSource(String source) { this.source = source; }
    }
    
    /**
     * Represents the request payload sent to the server.
     */
    public static class DataRequest {
        private String trader;
        private String bookOwners;
        
        public DataRequest(String trader, String bookOwners) {
            this.trader = trader;
            this.bookOwners = bookOwners;
        }
        
        // Getters are required for Jackson serialization
        public String getTrader() { return trader; }
        public String getBookOwners() { return bookOwners; }
    }

    /**
     * Handles STOMP session events.
     */
    private class StompClientSessionHandler extends StompSessionHandlerAdapter {
        @Override
        public void afterConnected(StompSession session, StompHeaders connectedHeaders) {
            stompSession = session;
            log("Connection successful. Session: " + session.getSessionId());

            String subscribeDestination = subscribeDestField.getText();
            log("Subscribing to: " + subscribeDestination);
            session.subscribe(subscribeDestination, new StompFrameHandler() {
                @Override
                public Type getPayloadType(StompHeaders headers) {
                    // We expect an array for snapshot, single object for updates.
                    // Let's handle it as a raw byte array and parse manually.
                    return byte[].class;
                }

                @Override
                public void handleFrame(StompHeaders headers, Object payload) {
                    try {
                        String json = new String((byte[]) payload);
                        
                        // Check if it's an array (snapshot) or single object (update)
                        if (json.trim().startsWith("[")) {
                            MarketData[] dataPoints = objectMapper.readValue(json, MarketData[].class);
                            log("Received snapshot with " + dataPoints.length + " items.");
                            updateTable(dataPoints);
                        } else {
                            MarketData dataPoint = objectMapper.readValue(json, MarketData.class);
                            updateTable(new MarketData[]{dataPoint});
                        }
                    } catch (Exception e) {
                        log("Error processing message: " + e.getMessage());
                        e.printStackTrace();
                    }
                }
            });

            String sendDestination = sendDestField.getText();
            log("Sending request to: " + sendDestination);
            
            DataRequest request = new DataRequest(traderField.getText(), bookOwnersField.getText());
            session.send(sendDestination, request);
        }

        @Override
        public void handleException(StompSession session, StompCommand command, StompHeaders headers, byte[] payload, Throwable exception) {
            log("STOMP Exception: " + exception.getMessage());
            exception.printStackTrace();
        }

        @Override
        public void handleTransportError(StompSession session, Throwable exception) {
            log("Transport Error: " + exception.getMessage());
            if (stompSession != null) {
                stompSession = null; // Mark as disconnected
            }
            exception.printStackTrace();
        }
    }
    
    private void updateTable(MarketData[] dataPoints) {
        SwingUtilities.invokeLater(() -> {
            for (MarketData data : dataPoints) {
                if (data.getId() == null) continue;

                Object[] rowData = {
                    data.getId(),
                    data.getSymbol(),
                    data.getPrice(),
                    data.getSize(),
                    data.getSource()
                };

                if (idToRowIndexMap.containsKey(data.getId())) {
                    // Update existing row
                    int rowIndex = idToRowIndexMap.get(data.getId());
                    // Check if row still exists (could be cleared)
                    if (rowIndex < tableModel.getRowCount()) {
                       for (int i = 0; i < rowData.length; i++) {
                           tableModel.setValueAt(rowData[i], rowIndex, i);
                       }
                    } else {
                        // Row index is out of bounds, treat as a new add
                        addNewRow(data.getId(), rowData);
                    }
                } else {
                    // Add new row
                    addNewRow(data.getId(), rowData);
                }
            }
        });
    }

    private void addNewRow(String id, Object[] rowData) {
        tableModel.addRow(rowData);
        int newRowIndex = tableModel.getRowCount() - 1;
        idToRowIndexMap.put(id, newRowIndex);
    }

    public static void main(String[] args) {
        // Run the GUI on the Event Dispatch Thread (EDT)
        SwingUtilities.invokeLater(() -> {
            StompClientGui app = new StompClientGui();
            app.setVisible(true);
        });
    }
}
