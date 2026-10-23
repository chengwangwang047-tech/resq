package com.resq.support;

import com.rabbitmq.client.ConnectionFactory;
import org.springframework.test.context.DynamicPropertyRegistry;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;

public final class IsolatedTestEnvironment {
    private IsolatedTestEnvironment() {}

    public static void requireGuard(String guard) {
        if (guard == null || !guard.matches("[a-f0-9]{32}")) {
            throw new IllegalStateException("Use python3 scripts/run_integration_tests.py; isolated guard required");
        }
    }

    public static void register(DynamicPropertyRegistry registry) throws Exception {
        String guard = System.getenv("RESQ_IT_GUARD");
        requireGuard(guard); // Must run before accessing any endpoint or connection credentials.
        String password = required("RESQ_IT_DB_PASSWORD");
        String rabbitUser = required("RESQ_IT_RABBIT_USER");
        if (!rabbitUser.startsWith("resq_it_")) throw new IllegalStateException("Synthetic broker account required");
        String rabbitPassword = required("RESQ_IT_RABBIT_PASSWORD");
        String jwtKey = required("RESQ_IT_JWT_SECRET_BASE64");
        String url = "jdbc:mysql://127.0.0.1:13317/resq_day17?allowPublicKeyRetrieval=true&useSSL=false&connectionTimeZone=UTC&connectTimeout=2000&socketTimeout=5000";
        try (var connection = DriverManager.getConnection(url, "day17_test", password);
             var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT token, DATABASE(), @@port, SUBSTRING_INDEX(CURRENT_USER(),'@',1) FROM day17_safety_guard")) {
            if (!result.next() || !guard.equals(result.getString(1))
                    || !"resq_day17".equals(result.getString(2)) || result.getInt(3) != 13317
                    || !"day17_test".equals(result.getString(4)) || result.next()) {
                throw new IllegalStateException("Isolated database guard mismatch");
            }
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", 16317), 2000);
            socket.setSoTimeout(2000);
            // Use the exact key length in RESP; no Redis configuration or scan commands.
            String key = "day17:safety-guard";
            socket.getOutputStream().write(("*2\r\n$3\r\nGET\r\n$" + key.length() + "\r\n" + key + "\r\n").getBytes(StandardCharsets.UTF_8));
            var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            if (!"$32".equals(reader.readLine()) || !guard.equals(reader.readLine())) {
                throw new IllegalStateException("Isolated Redis guard mismatch");
            }
        }
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost("127.0.0.1"); factory.setPort(56719);
        factory.setUsername(rabbitUser); factory.setPassword(rabbitPassword);
        factory.setConnectionTimeout(3000); factory.setHandshakeTimeout(3000);
        try (var connection = factory.newConnection("resq-integration-guard"); var channel = connection.createChannel()) {
            channel.queueDeclarePassive("resq-it-guard-" + guard);
        }
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> "day17_test");
        registry.add("spring.datasource.password", () -> password);
        registry.add("spring.data.redis.host", () -> "127.0.0.1");
        registry.add("spring.rabbitmq.host", () -> "127.0.0.1");
        registry.add("spring.rabbitmq.port", () -> 56719);
        registry.add("spring.rabbitmq.username", () -> rabbitUser);
        registry.add("spring.rabbitmq.password", () -> rabbitPassword);
        registry.add("resq.jwt.secret-base64", () -> jwtKey);
        registry.add("spring.sql.init.mode", () -> "never");
        registry.add("spring.rabbitmq.listener.simple.auto-startup", () -> false);
        registry.add("spring.rabbitmq.listener.direct.auto-startup", () -> false);
    }

    private static String required(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing isolated test setting: " + key);
        return value;
    }
}
