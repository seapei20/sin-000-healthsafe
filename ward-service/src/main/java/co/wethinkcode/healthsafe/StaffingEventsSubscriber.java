package co.wethinkcode.healthsafe;

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
import javax.jms.Topic;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Subscribes to {@code staffing-events-topic} and pushes every staffing update
 * into {@link StaffingCache}.
 *
 * <p>A topic is the right tool here: staffing updates are a broadcast — every
 * subscriber hears every message, nobody is waiting for an acknowledgement, and a
 * late subscriber simply misses what it was not around for. That is exactly the
 * semantics ward-service needs to stop polling staffing-service. Contrast with the
 * {@code equipment-failure-queue} in this same service, where a lost message is
 * unacceptable and a queue with explicit acknowledgement is used instead.
 *
 * <p>The subscription is plain (non-durable) to keep the broadcast
 * fire-and-forget: ward-service re-reads the authoritative state by calling
 * staffing-service directly whenever its cache is cold, so there is nothing to
 * replay after an outage.
 *
 * <p>Connection failures are never fatal — the service keeps running without a
 * broker and retries every few seconds, so {@code /health} stays up and the REST
 * side of ward-service keeps working with the broker down.
 */
public class StaffingEventsSubscriber implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(StaffingEventsSubscriber.class);
    private static final long RETRY_MS = 5_000;

    private final String brokerUrl;
    private final String topicName;
    private final StaffingCache cache;
    private final AtomicBoolean stopped = new AtomicBoolean();

    private Thread thread;
    private volatile Connection connection;

    public StaffingEventsSubscriber(String brokerUrl, String topicName, StaffingCache cache) {
        this.brokerUrl = brokerUrl;
        this.topicName = topicName;
        this.cache = cache;
    }

    public void start() {
        thread = new Thread(this::run, "staffing-events-subscriber");
        thread.setDaemon(true);
        thread.start();
    }

    private void run() {
        boolean warned = false;
        while (!stopped.get()) {
            try {
                consumeUntilStopped();
                warned = false;
            } catch (JMSException e) {
                if (!warned) {
                    log.warn("Cannot reach broker at {} ({}). Retrying every {}s — ward-service keeps serving "
                                    + "REST traffic and falls back to staffing-service while disconnected.",
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
            Session session = newConnection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            Topic topic = session.createTopic(topicName);
            MessageConsumer consumer = session.createConsumer(topic);
            newConnection.start();
            log.info("Subscribed to topic {} at {}", topicName, brokerUrl);
            while (!stopped.get()) {
                Message message = consumer.receive(5_000);
                if (message != null) {
                    handle(message);
                }
            }
        } finally {
            closeQuietly(newConnection);
            connection = null;
        }
    }

    private void handle(Message message) {
        if (!(message instanceof TextMessage textMessage)) {
            log.warn("Ignoring non-text message on {}", topicName);
            return;
        }
        try {
            JsonNode event = HttpJson.MAPPER.readTree(textMessage.getText());
            cache.record(event);
            log.info("Staffing update for {} (source: topic)", event.path("wardId").asText("?"));
        } catch (Exception e) {
            // AUTO_ACKNOWLEDGE already acked on receive(): a malformed broadcast is
            // dropped rather than redelivered forever, which is fine for a topic.
            log.warn("Ignoring unreadable staffing event: {}", e.getMessage());
        }
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
