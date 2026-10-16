package co.wethinkcode.healthsafe;

import co.wethinkcode.healthsafe.mq.MqConfig;
import io.javalin.Javalin;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Stage 4 of the HealthSafe pipeline: the guaranteed-delivery consumer for
 * equipment failure alerts raised by ward-service.
 *
 * <p>See {@link EquipmentFailureConsumer} for why this is a queue with client
 * acknowledgement rather than a bare listener.
 */
public class EquipmentAlertServiceApp {

    static final int PORT = 7034;

    private static EquipmentFailureConsumer consumer;

    public static void main(String[] args) {
        consumer = new EquipmentFailureConsumer(MqConfig.BROKER_URL, MqConfig.QUEUE);
        consumer.start();

        Javalin app = Javalin.create().start(PORT);

        app.get("/health", ctx -> ctx.result("OK"));

        app.get("/alerts", ctx -> ctx.json(consumer.received()));

        app.get("/alerts/count", ctx -> {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("queue", MqConfig.QUEUE);
            out.put("broker", MqConfig.BROKER_URL);
            out.put("alertsHandled", consumer.count());
            ctx.json(out);
        });
    }
}
