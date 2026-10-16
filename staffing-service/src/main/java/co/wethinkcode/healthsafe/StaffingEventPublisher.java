package co.wethinkcode.healthsafe;

import org.apache.activemq.ActiveMQConnectionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jms.Connection;
import javax.jms.DeliveryMode;
import javax.jms.JMSException;
import javax.jms.MessageProducer;
import javax.jms.Session;
import javax.jms.Topic;

/**
 * Broadcasts staffing updates onto {@code staffing-events-topic}.
 *
 * <p>A topic is used deliberately: a schedule change is an event that any number
 * of front ends / ward services may want to hear about, nobody needs to wait for
 * it, and there is no consumer to "miss" a message in a way that matters (the
 * authoritative schedule is always recomputable by asking this service again).
 * Contrast with the {@code equipment-failure-queue} consumed by
 * equipment-alert-service, where a lost message is unacceptable and a queue with
 * explicit acknowledgement is the right choice.
 *
 * <p>Because publishing is a side effect of computing a schedule, a broker outage
 * degrades the broadcast but never fails the request: the caller is told the event
 * was not published and the schedule is still returned.
 */
public class StaffingEventPublisher implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(StaffingEventPublisher.class);

    private final String brokerUrl;
    private final String topicName;
    private final Object lock = new Object();

    private Connection connection;
    private Session session;
    private MessageProducer producer;

    public StaffingEventPublisher(String brokerUrl, String topicName) {
        this.brokerUrl = brokerUrl;
        this.topicName = topicName;
    }

    /**
     * Publishes one event. Returns false (instead of throwing) when the broker is
     * unreachable, so a schedule can still be served while the broker is down.
     */
    public boolean publish(String payload) {
        synchronized (lock) {
            try {
                producer().send(session().createTextMessage(payload));
                return true;
            } catch (JMSException e) {
                log.warn("Could not publish to {} ({}). Reconnecting on next publish.", topicName, e.getMessage());
                reset();
                return false;
            }
        }
    }

    private MessageProducer producer() throws JMSException {
        if (producer == null) {
            Connection newConnection = new ActiveMQConnectionFactory(brokerUrl).createConnection();
            try {
                Session newSession = newConnection.createSession(false, Session.AUTO_ACKNOWLEDGE);
                Topic topic = newSession.createTopic(topicName);
                MessageProducer newProducer = newSession.createProducer(topic);
                newProducer.setDeliveryMode(DeliveryMode.PERSISTENT);
                connection = newConnection;
                session = newSession;
                producer = newProducer;
                log.info("Connected to broker at {} (topic: {})", brokerUrl, topicName);
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
