/*
 * StompSwingTestClient.java
 * ------------------------------------------------------------
 * Minimal Java 8 Swing app for quick, local testing of a Spring WebSocket STOMP server.
 * Runs on Windows 11 (or anywhere with a JRE 8+). Quality is NOT the goal—speed is.
 *
 * Features
 * - Text fields for: Endpoint (ws://...), Send Destination (/app/..), Subscribe Destination (/user/..), Trader, Book Owners
 * - "Connect" button (optional convenience)
 * - "Submit" button sends a JSON request: { trader, bookOwners[], requestId }
 * - Subscribes to the given destination and renders results in a JTable
 * - First array payload is treated as a full snapshot; subsequent objects are treated as upserts
 * - Rows are upserted by the "id" field (change ID_FIELD to match your payload)
 *
 * Build / Run (Maven)
 * ------------------------------------------------------------
 * Dependencies: Spring WebSocket/STOMP client, Jackson, and a JSR‑356 WebSocket impl (Tyrus).
 * Add something like this to your pom.xml (align versions with your stack):
 *
 * <dependencies>
 *   <dependency>
 *     <groupId>org.springframework</groupId>
 *     <artifactId>spring-websocket</artifactId>
 *     <version>5.3.x</version>
 *   </dependency>
 *   <dependency>
 *     <groupId>org.springframework</groupId>
 *     <artifactId>spring-messaging</artifactId>
 *     <version>5.3.x</version>
 *   </dependency>
 *   <dependency>
 *     <groupId>com.fasterxml.jackson.core</groupId>
 *     <artifactId>jackson-databind</artifactId>
 *     <version>2.17.x</version>
 *   </dependency>
 *   <dependency>
 *     <groupId>org.glassfish.tyrus.bundles</groupId>
 *     <artifactId>tyrus-standalone-client-jdk</artifactId>
 *     <version>1.15</version> <!-- or newer that still supports Java 8 -->
 *   </dependency>
 * </dependencies>
 *
 * Then:
 *   mvn -q -DskipTests package
 *   java -cp target/your-jar-with-deps.jar com.example.StompSwingTestClient
 *
 * Notes
 * ------------------------------------------------------------
 * - If your server uses SockJS-only endpoints, switch to SockJsClient + WebSocketTransport if needed.
 * - ID_FIELD defaults to "id". Change to match your payload (e.g., "recordId").
 * - Non-scalar values (arrays/objects) are shown as compact JSON strings in the table.
 */

package com.example;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.messaging.converter.StringMessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.scheduling.concurrent.ConcurrentTaskScheduler;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.socket.client.WebSocketClient;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class StompSwingTestClient extends JFrame {
    // === Adjust this to match your payload ===
    private static final String ID_FIELD = "id"; // e.g., "recordId" if your server uses that

    private final JTextField endpointField = new JTextField("ws://localhost:8080/ws");
    private final JTextField sendDestField = new JTextField("/app/test");
    private final JTextField subDestField  = new JTextField("/user/queue/test");
    private final JTextField traderField   = new JTextField();
    private final JTextField ownersField   = new JTextField();

    private final JButton connectBtn  = new JButton("Connect");
    private final JButton disconnectBtn = new JButton("Disconnect");
    private final JButton submitBtn   = new JButton("Submit");

    private final JTextArea logArea   = new JTextArea(6, 80);
    private final UpsertTableModel tableModel = new UpsertTableModel(ID_FIELD);
    private final JTable table = new JTable(tableModel);

    private final ObjectMapper mapper = new ObjectMapper();

    private volatile WebSocketStompClient stompClient;
    private volatile StompSession session;
    private volatile boolean subscribed = false;

    public StompSwingTestClient() {
        super("STOMP Test Client (Java 8 / Swing)");
        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        setLayout(new BorderLayout());

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(new EmptyBorder(10, 10, 10, 10));
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(4, 4, 4, 4);
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.weightx = 0.0;

        int row = 0;
        addRow(form, gc, row++, new JLabel("Endpoint (ws://host:port/ws)"), endpointField);
        addRow(form, gc, row++, new JLabel("Send Destination (/app/.. )"), sendDestField);
        addRow(form, gc, row++, new JLabel("Subscribe Destination (/user/.. )"), subDestField);
        addRow(form, gc, row++, new JLabel("Trader"), traderField);
        addRow(form, gc, row++, new JLabel("Book Owners (comma‑separated)"), ownersField);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        buttons.add(connectBtn);
        buttons.add(disconnectBtn);
        buttons.add(submitBtn);

        gc.gridx = 0; gc.gridy = row; gc.gridwidth = 2; gc.weightx = 1.0;
        form.add(buttons, gc);

        add(form, BorderLayout.NORTH);

        // Table
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        table.setFillsViewportHeight(true);
        add(new JScrollPane(table), BorderLayout.CENTER);

        // Log area
        logArea.setEditable(false);
        add(new JScrollPane(logArea), BorderLayout.SOUTH);

        // Wire actions
        connectBtn.addActionListener(e -> safeConnectAndSubscribe());
        disconnectBtn.addActionListener(e -> safeDisconnect());
        submitBtn.addActionListener(e -> safeSubmit());

        setSize(1000, 700);
        setLocationRelativeTo(null);
    }

    private static void addRow(JPanel p, GridBagConstraints gc, int row, JComponent label, JComponent field) {
        gc.gridx = 0; gc.gridy = row; gc.weightx = 0.0; gc.gridwidth = 1;
        p.add(label, gc);
        gc.gridx = 1; gc.gridy = row; gc.weightx = 1.0; gc.gridwidth = 1;
        p.add(field, gc);
    }

    private void safeConnectAndSubscribe() {
        try {
            connectAndSubscribe();
        } catch (Exception ex) {
            log("Connect error: " + ex.getMessage());
        }
    }

    private void safeDisconnect() {
        try {
            disconnect();
        } catch (Exception ex) {
            log("Disconnect error: " + ex.getMessage());
        }
    }

    private void safeSubmit() {
        try {
            if (session == null || !session.isConnected()) {
                connectAndSubscribe();
            }
            sendRequest();
        } catch (Exception ex) {
            log("Submit error: " + ex.getMessage());
        }
    }

    private void connectAndSubscribe() throws Exception {
        if (session != null && session.isConnected() && subscribed) {
            log("Already connected & subscribed.");
            return;
        }

        String url = endpointField.getText().trim();
        if (url.isEmpty()) {
            log("Endpoint is empty.");
            return;
        }

        WebSocketClient wsClient = new StandardWebSocketClient(); // requires Tyrus on classpath
        stompClient = new WebSocketStompClient(wsClient);
        stompClient.setMessageConverter(new StringMessageConverter()); // raw JSON strings
        stompClient.setDefaultHeartbeat(new long[]{10000, 10000});
        stompClient.setTaskScheduler(new ConcurrentTaskScheduler());

        log("Connecting to " + url + " ...");
        session = stompClient.connect(url, new StompSessionHandlerAdapter() {
            @Override public void afterConnected(StompSession s, StompHeaders connectedHeaders) {
                log("Connected. Subscribing...");
                subscribeInternal(s);
            }
            @Override public void handleTransportError(StompSession s, Throwable ex) {
                log("Transport error: " + ex.getMessage());
            }
        }).get(10, TimeUnit.SECONDS);

        // If afterConnected didn't subscribe for some reason, do it now.
        if (!subscribed) subscribeInternal(session);
    }

    private synchronized void subscribeInternal(StompSession s) {
        if (subscribed) return;
        final String destination = subDestField.getText().trim();
        if (destination.isEmpty()) {
            log("Subscribe destination is empty.");
            return;
        }

        s.subscribe(destination, new StompFrameHandler() {
            @Override public Type getPayloadType(StompHeaders headers) { return String.class; }
            @Override public void handleFrame(StompHeaders headers, Object payload) {
                String json = (payload == null) ? "" : payload.toString();
                processIncoming(json);
            }
        });
        subscribed = true;
        log("Subscribed to " + destination);
    }

    private void sendRequest() throws Exception {
        if (session == null || !session.isConnected()) {
            log("Not connected.");
            return;
        }

        String dest = sendDestField.getText().trim();
        if (dest.isEmpty()) {
            log("Send destination is empty.");
            return;
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        String trader = traderField.getText().trim();
        String owners = ownersField.getText().trim();
        payload.put("trader", trader.isEmpty() ? null : trader);
        payload.put("bookOwners", parseCsv(owners));
        payload.put("requestId", UUID.randomUUID().toString());

        String json = mapper.writeValueAsString(payload);

        StompHeaders headers = new StompHeaders();
        headers.setDestination(dest);
        headers.setContentType(MimeTypeUtils.APPLICATION_JSON);
        session.send(headers, json.getBytes(StandardCharsets.UTF_8));

        log("Sent request to " + dest + ": " + json);
    }

    private static List<String> parseCsv(String s) {
        if (s == null || s.trim().isEmpty()) return Collections.emptyList();
        String[] parts = s.split(",");
        List<String> out = new ArrayList<>(parts.length);
        for (String p : parts) {
            String t = p.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    private void processIncoming(String json) {
        if (json == null || json.trim().isEmpty()) return;
        SwingUtilities.invokeLater(() -> {
            try {
                JsonNode root = mapper.readTree(json);
                if (root.isArray()) {
                    // Treat as full snapshot
                    List<Map<String, Object>> snapshot = new ArrayList<>();
                    for (JsonNode n : root) {
                        if (n.isObject()) {
                            snapshot.add(mapper.convertValue(n, new TypeReference<Map<String, Object>>(){}));
                        }
                    }
                    tableModel.resetWithSnapshot(snapshot);
                    log("Snapshot received: " + snapshot.size() + " row(s).");
                } else if (root.isObject()) {
                    Map<String, Object> record = mapper.convertValue(root, new TypeReference<Map<String, Object>>(){});
                    tableModel.upsert(record);
                    log("Upsert received: id=" + String.valueOf(record.get(ID_FIELD)));
                } else {
                    log("Unknown payload, ignored: " + json);
                }
            } catch (Exception ex) {
                log("Payload parse error: " + ex.getMessage());
            }
        });
    }

    private void disconnect() {
        subscribed = false;
        if (session != null) {
            try { session.disconnect(); } catch (Exception ignored) {}
            session = null;
        }
        if (stompClient != null) {
            try { stompClient.stop(); } catch (Exception ignored) {}
            stompClient = null;
        }
        log("Disconnected.");
    }

    private void log(String msg) {
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        logArea.append("[" + ts + "] " + msg + "\n");
        logArea.setCaretPosition(logArea.getDocument().getLength());
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new StompSwingTestClient().setVisible(true));
    }

    // ------------------------------------------------------------
    // Table model with upsert-by-ID behavior
    // ------------------------------------------------------------
    static class UpsertTableModel extends AbstractTableModel {
        private final String idField;
        private final List<Map<String, Object>> rows = new ArrayList<>();
        private final Map<Object, Integer> indexById = new HashMap<>();
        private List<String> columns = new ArrayList<>(); // ordered: id first, then alpha

        UpsertTableModel(String idField) { this.idField = idField; }

        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return columns.size(); }
        @Override public String getColumnName(int column) { return columns.get(column); }

        @Override public Object getValueAt(int rowIndex, int columnIndex) {
            Map<String, Object> row = rows.get(rowIndex);
            String col = columns.get(columnIndex);
            Object v = row.get(col);
            if (v == null) return "";
            if (v instanceof Map || v instanceof List) {
                try { return new ObjectMapper().writeValueAsString(v); } catch (Exception ignored) {}
            }
            return String.valueOf(v);
        }

        synchronized void resetWithSnapshot(List<Map<String, Object>> snapshot) {
            rows.clear();
            indexById.clear();
            columns = computeColumns(snapshot);
            for (Map<String, Object> m : snapshot) {
                rows.add(new HashMap<>(m));
            }
            rebuildIndex();
            fireTableStructureChanged();
        }

        synchronized void upsert(Map<String, Object> record) {
            Object id = record.get(idField);
            if (id == null) {
                // Ignore rows without ID; you may choose to auto-generate
                return;
            }

            boolean newCols = mergeColumns(record);

            Integer pos = indexById.get(id);
            if (pos == null) {
                rows.add(new HashMap<>(record));
                indexById.put(id, rows.size() - 1);
                if (newCols) fireTableStructureChanged(); else fireTableRowsInserted(rows.size() - 1, rows.size() - 1);
            } else {
                Map<String, Object> existing = rows.get(pos);
                existing.putAll(record);
                if (newCols) fireTableStructureChanged(); else fireTableRowsUpdated(pos, pos);
            }
        }

        private List<String> computeColumns(List<Map<String, Object>> data) {
            Set<String> all = new HashSet<>();
            for (Map<String, Object> m : data) all.addAll(m.keySet());
            return orderColumns(all);
        }

        private boolean mergeColumns(Map<String, Object> record) {
            Set<String> all = new HashSet<>(columns);
            if (all.addAll(record.keySet())) {
                columns = orderColumns(all);
                rebuildIndex(); // row indices unchanged; index only
                return true;
            }
            return false;
        }

        private List<String> orderColumns(Set<String> names) {
            List<String> others = new ArrayList<>();
            for (String n : names) if (!n.equals(idField)) others.add(n);
            Collections.sort(others, String.CASE_INSENSITIVE_ORDER);
            List<String> out = new ArrayList<>(others.size() + 1);
            if (names.contains(idField)) out.add(idField);
            out.addAll(others);
            return out;
        }

        private void rebuildIndex() {
            indexById.clear();
            for (int i = 0; i < rows.size(); i++) {
                Object id = rows.get(i).get(idField);
                if (id != null) indexById.put(id, i);
            }
        }
    }
}
