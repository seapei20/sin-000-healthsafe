package co.wethinkcode.healthsafe;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.activemq.ActiveMQConnectionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jms.Connection;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.Session;
import javax.jms.TextMessage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Consumes {@code equipment-failure-queue} with guaranteed delivery.
 *
 * <p>Guaranteed means three things, all of which are here rather than a bare
 * message listener:
 * <ul>
 *   <li><b>The queue, not a topic</b> — exactly one consumer gets each alert, and
 *       the broker holds it while this service is down.</li>
 *   <li><b>Persistent messages</b> (the producer sets
 *       {@code DeliveryMode.PERSISTENT}) with the broker's own persistence store,
 *       so alerts survive a broker restart too.</li>
 *   <li><b>Client acknowledgement</b> — a message is only removed once it has been
 *       handled. If handling throws, {@link Session#recover()} hands it back for
 *       redelivery, and ActiveMQ eventually dead-letters a message that can never
 *       be processed rather than dropping it.</li>
 * </ul>
 *
 * <p>The consumer reconnects on its own schedule, so the service starts and serves
 * {@code /health} even when no broker is running.
 */
public class EquipmentFailureConsumer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(EquipmentFailureConsumer.class);
    private static final long RETRY_MS = 5_000;

    private final String brokerUrl;
    private final String queueName;
    private final List<JsonNode> received = Collections.synchronizedList(new ArrayList<>());
    private final AtomicBoolean stopped = new AtomicBoolean();

    private Thread thread;
    private volatile Connection connection;

    public EquipmentFailureConsumer(String brokerUrl, String queueName) {
        this.brokerUrl = brokerUrl;
        this.queueName = queueName;
    }

    public void start() {
        thread = new Thread(this::run, "equipment-failure-consumer");
        thread.setDaemon(true);
        thread.start();
    }

    /** Alerts handled so far, oldest first. */
    public List<JsonNode> received() {
        synchronized (received) {
            return List.copyOf(received);
        }
    }

    public int count() {
        return received.size();
    }

    private void run() {
        boolean warned = false;
        while (!stopped.get()) {
            try {
                consumeUntilStopped();
                warned = false;
            } catch (JMSException e) {
                if (!warned) {
                    log.warn("Cannot reach broker at {} ({}). Retrying every {}s — the queue keeps holding "
                                    + "alerts until this service is connected.",
                            brokerUrl, e.getMessage(), RETRY_MS / 1000);
                    warned = true;
                }
            }
            pause(RETRY_MS);
        }
    }

    private void consumeUntilStopped() throws JMSException {
        Connection newConnection = new ActiveMQConnectionFactory(brokerUrl).createConnection();
        connection = newConnection;
        try {
            Session session = newConnection.createSession(false, Session.CLIENT_ACKNOWLEDGE);
            MessageConsumer consumer = session.createConsumer(session.createQueue(queueName));
            newConnection.start();
            log.info("Consuming queue {} at {} (client acknowledgement)", queueName, brokerUrl);
            while (!stopped.get()) {
                Message message = consumer.receive(5_000);
                if (message == null) {
                    continue;
                }
                try {
                    handle(message);
                    message.acknowledge(); // only now does the broker drop the alert
                } catch (Exception e) {
                    log.error("Failed to process alert ({}). Requesting redelivery.", e.getMessage());
                    session.recover(); // unacked message goes back on the queue
                }
            }
        } finally {
            closeQuietly(newConnection);
            connection = null;
        }
    }

    private void handle(Message message) throws JMSException {
        if (!(message instanceof TextMessage textMessage)) {
            throw new JMSException("expected a text message, got " + message.getClass().getSimpleName());
        }
        JsonNode alert;
        try {
            alert = HttpJson.MAPPER.readTree(textMessage.getText());
        } catch (JsonProcessingException e) {
            // Treated as a processing failure so the message is redelivered and,
            // if it is permanently poison, dead-lettered rather than dropped.
            throw new JMSException("alert is not valid JSON: " + e.getMessage());
        }
        if (alert.path("wardId").asText("").isBlank()) {
            throw new JMSException("alert has no wardId");
        }
        JsonNode stored = alert.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) stored).put("receivedAt", Instant.now().toString());
        received.add(stored);
        log.error("EQUIPMENT FAILURE ALERT: ward {} ({}) — {} {} [{}]",
                alert.path("wardId").asText("?"),
                alert.path("wing").isNull() ? "?" : alert.path("wing").asText(),
                alert.path("equipment").asText("?"),
                alert.path("fault").asText("").isBlank() ? "" : ": " + alert.path("fault").asText(),
                alert.path("severity").asText("critical"));
    }

    @Override
    public void close() {
        stopped.set(true);
        closeQuietly(connection);
        if (thread != null) {
            thread.interrupt();
        }
    }

    private static void closeQuietly(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (JMSException e) {
            log.debug("Error closing broker connection: {}", e.getMessage());
        }
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
