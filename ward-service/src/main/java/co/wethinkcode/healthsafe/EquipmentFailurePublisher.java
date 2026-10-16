package co.wethinkcode.healthsafe;

import org.apache.activemq.ActiveMQConnectionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jms.Connection;
import javax.jms.DeliveryMode;
import javax.jms.JMSException;
import javax.jms.MessageProducer;
import javax.jms.Session;

/**
 * Publishes equipment failure alerts to {@code equipment-failure-queue}.
 *
 * <p>A queue is used deliberately, in contrast to the staffing <i>topic</i> this
 * same service consumes: a failure alert must reach exactly one consumer and must
 * not evaporate if equipment-alert-service happens to be restarting. Messages are
 * persistent and the broker keeps them until the consumer acknowledges, so
 * delivery is guaranteed rather than best-effort.
 *
 * <p>The connection is opened lazily and rebuilt after a failure, so a broker that
 * is down shows up as a clean {@link JMSException} the caller can turn into a 503
 * — the alert is reported as <i>not</i> queued rather than being silently lost.
 */
public class EquipmentFailurePublisher implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(EquipmentFailurePublisher.class);

    private final String brokerUrl;
    private final String queueName;
    private final Object lock = new Object();

    private Connection connection;
    private Session session;
    private MessageProducer producer;

    public EquipmentFailurePublisher(String brokerUrl, String queueName) {
        this.brokerUrl = brokerUrl;
        this.queueName = queueName;
    }

    /**
     * Sends one persistent text message. Throws {@link JMSException} when the broker
     * is unreachable; the caller must treat that as "not queued".
     */
    public void publish(String payload) throws JMSException {
        synchronized (lock) {
            try {
                producer().send(session().createTextMessage(payload));
            } catch (JMSException e) {
                // Drop the broken connection so the next publish starts clean.
                reset();
                throw e;
            }
        }
    }

    private MessageProducer producer() throws JMSException {
        if (producer == null) {
            Connection newConnection = new ActiveMQConnectionFactory(brokerUrl).createConnection();
            try {
                Session newSession = newConnection.createSession(false, Session.AUTO_ACKNOWLEDGE);
                MessageProducer newProducer = newSession.createProducer(newSession.createQueue(queueName));
                newProducer.setDeliveryMode(DeliveryMode.PERSISTENT);
                connection = newConnection;
                session = newSession;
                producer = newProducer;
                log.info("Connected to broker at {} (queue: {})", brokerUrl, queueName);
            } catch (JMSException e) {
                closeQuietly(newConnection);
                throw e;
            }
        }
        return producer;
    }

    private Session session() throws JMSException {
        producer(); // ensures session is initialised
        return session;
    }

    private void reset() {
        closeQuietly(connection);
        connection = null;
        session = null;
        producer = null;
    }

    @Override
    public void close() {
        synchronized (lock) {
            reset();
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
}
