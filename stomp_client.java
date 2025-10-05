import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.WebSocketClient;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;

public class StompWebSocketTestClient extends JFrame {
    private JTextField endpointField;
    private JTextField sendDestinationField;
    private JTextField subscribeDestinationField;
    private JTextField traderField;
    private JTextField bookOwnersField;
    private JButton submitButton;
    private JButton disconnectButton;
    private JTable resultTable;
    private DefaultTableModel tableModel;
    
    private StompSession stompSession;
    private Map<String, Integer> idToRowMap = new HashMap<>();
    private ObjectMapper objectMapper = new ObjectMapper();
    
    public StompWebSocketTestClient() {
        initializeUI();
    }
    
    private void initializeUI() {
        setTitle("STOMP WebSocket Test Client");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(900, 600);
        setLayout(new BorderLayout(10, 10));
        
        // Input panel
        JPanel inputPanel = createInputPanel();
        add(inputPanel, BorderLayout.NORTH);
        
        // Table panel
        JPanel tablePanel = createTablePanel();
        add(tablePanel, BorderLayout.CENTER);
        
        setLocationRelativeTo(null);
    }
    
    private JPanel createInputPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(5, 5, 5, 5);
        
        // WebSocket endpoint
        addLabelAndField(panel, gbc, 0, "WebSocket Endpoint:", 
            endpointField = new JTextField("ws://localhost:8080/ws", 30));
        
        // Send destination
        addLabelAndField(panel, gbc, 1, "Send Destination:", 
            sendDestinationField = new JTextField("/app/request", 30));
        
        // Subscribe destination
        addLabelAndField(panel, gbc, 2, "Subscribe Destination:", 
            subscribeDestinationField = new JTextField("/user/queue/response", 30));
        
        // Trader
        addLabelAndField(panel, gbc, 3, "Trader:", 
            traderField = new JTextField("TRADER001", 30));
        
        // Book Owners
        addLabelAndField(panel, gbc, 4, "Book Owners:", 
            bookOwnersField = new JTextField("OWNER001", 30));
        
        // Buttons
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        submitButton = new JButton("Connect & Submit");
        disconnectButton = new JButton("Disconnect");
        disconnectButton.setEnabled(false);
        
        submitButton.addActionListener(e -> connectAndSubmit());
        disconnectButton.addActionListener(e -> disconnect());
        
        buttonPanel.add(submitButton);
        buttonPanel.add(disconnectButton);
        
        gbc.gridx = 0;
        gbc.gridy = 5;
        gbc.gridwidth = 2;
        panel.add(buttonPanel, gbc);
        
        return panel;
    }
    
    private void addLabelAndField(JPanel panel, GridBagConstraints gbc, 
                                   int row, String labelText, JTextField field) {
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.gridwidth = 1;
        gbc.weightx = 0.0;
        panel.add(new JLabel(labelText), gbc);
        
        gbc.gridx = 1;
        gbc.weightx = 1.0;
        panel.add(field, gbc);
    }
    
    private JPanel createTablePanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(0, 10, 10, 10));
        
        tableModel = new DefaultTableModel() {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        
        resultTable = new JTable(tableModel);
        resultTable.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
        
        JScrollPane scrollPane = new JScrollPane(resultTable);
        panel.add(scrollPane, BorderLayout.CENTER);
        
        return panel;
    }
    
    private void connectAndSubmit() {
        submitButton.setEnabled(false);
        
        SwingWorker<Void, Void> worker = new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                String endpoint = endpointField.getText().trim();
                String sendDest = sendDestinationField.getText().trim();
                String subscribeDest = subscribeDestinationField.getText().trim();
                
                WebSocketClient client = new StandardWebSocketClient();
                WebSocketStompClient stompClient = new WebSocketStompClient(client);
                stompClient.setMessageConverter(new MappingJackson2MessageConverter());
                
                StompSessionHandler sessionHandler = new StompSessionHandlerAdapter() {
                    @Override
                    public void afterConnected(StompSession session, StompHeaders connectedHeaders) {
                        stompSession = session;
                        
                        // Subscribe to destination
                        session.subscribe(subscribeDest, new StompFrameHandler() {
                            @Override
                            public Type getPayloadType(StompHeaders headers) {
                                return Map.class;
                            }
                            
                            @Override
                            public void handleFrame(StompHeaders headers, Object payload) {
                                handleMessage((Map<String, Object>) payload);
                            }
                        });
                        
                        // Send request
                        Map<String, String> request = new HashMap<>();
                        request.put("trader", traderField.getText().trim());
                        request.put("bookOwners", bookOwnersField.getText().trim());
                        
                        session.send(sendDest, request);
                        
                        SwingUtilities.invokeLater(() -> {
                            disconnectButton.setEnabled(true);
                            JOptionPane.showMessageDialog(StompWebSocketTestClient.this, 
                                "Connected and request sent!");
                        });
                    }
                    
                    @Override
                    public void handleException(StompSession session, StompCommand command, 
                                                StompHeaders headers, byte[] payload, Throwable exception) {
                        showError("STOMP error: " + exception.getMessage());
                    }
                    
                    @Override
                    public void handleTransportError(StompSession session, Throwable exception) {
                        showError("Transport error: " + exception.getMessage());
                    }
                };
                
                try {
                    stompClient.connect(endpoint, new WebSocketHttpHeaders(), sessionHandler).get();
                } catch (InterruptedException | ExecutionException ex) {
                    throw new RuntimeException("Failed to connect", ex);
                }
                
                return null;
            }
            
            @Override
            protected void done() {
                try {
                    get();
                } catch (Exception ex) {
                    showError("Connection failed: " + ex.getMessage());
                    submitButton.setEnabled(true);
                }
            }
        };
        
        worker.execute();
    }
    
    private void handleMessage(Map<String, Object> payload) {
        SwingUtilities.invokeLater(() -> {
            try {
                // Initialize table columns on first message
                if (tableModel.getColumnCount() == 0) {
                    for (String key : payload.keySet()) {
                        tableModel.addColumn(key);
                    }
                }
                
                // Extract ID (assumes "id" field exists; adjust as needed)
                String id = String.valueOf(payload.get("id"));
                
                // Convert payload to row data
                Object[] rowData = new Object[tableModel.getColumnCount()];
                for (int i = 0; i < tableModel.getColumnCount(); i++) {
                    String columnName = tableModel.getColumnName(i);
                    rowData[i] = payload.get(columnName);
                }
                
                // Update existing row or add new row
                if (idToRowMap.containsKey(id)) {
                    int rowIndex = idToRowMap.get(id);
                    for (int i = 0; i < rowData.length; i++) {
                        tableModel.setValueAt(rowData[i], rowIndex, i);
                    }
                } else {
                    int newRowIndex = tableModel.getRowCount();
                    tableModel.addRow(rowData);
                    idToRowMap.put(id, newRowIndex);
                }
            } catch (Exception ex) {
                showError("Failed to process message: " + ex.getMessage());
            }
        });
    }
    
    private void disconnect() {
        if (stompSession != null && stompSession.isConnected()) {
            stompSession.disconnect();
            stompSession = null;
        }
        
        disconnectButton.setEnabled(false);
        submitButton.setEnabled(true);
        
        JOptionPane.showMessageDialog(this, "Disconnected");
    }
    
    private void showError(String message) {
        SwingUtilities.invokeLater(() -> 
            JOptionPane.showMessageDialog(this, message, "Error", JOptionPane.ERROR_MESSAGE)
        );
    }
    
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            StompWebSocketTestClient client = new StompWebSocketTestClient();
            client.setVisible(true);
        });
    }
}