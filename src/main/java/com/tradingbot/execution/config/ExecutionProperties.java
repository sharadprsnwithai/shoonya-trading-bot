package com.tradingbot.execution.config;

import com.tradingbot.model.execution.ExecutionMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "trading-bot.execution")
public class ExecutionProperties {

    private List<ConsumerConfig> consumers = new ArrayList<>();

    public List<ConsumerConfig> getConsumers() {
        return consumers;
    }

    public void setConsumers(List<ConsumerConfig> consumers) {
        this.consumers = consumers;
    }

    public static class ConsumerConfig {
        private String id;
        private String broker = "SHOONYA";
        private ExecutionMode mode = ExecutionMode.PAPER;
        private double quantityMultiplier = 1.0;
        private boolean enabled = true;
        private long maxSignalAgeSeconds = 30;
        private Map<String, String> credentials;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getBroker() {
            return broker;
        }

        public void setBroker(String broker) {
            this.broker = broker;
        }

        public ExecutionMode getMode() {
            return mode;
        }

        public void setMode(ExecutionMode mode) {
            this.mode = mode;
        }

        public double getQuantityMultiplier() {
            return quantityMultiplier;
        }

        public void setQuantityMultiplier(double quantityMultiplier) {
            this.quantityMultiplier = quantityMultiplier;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public long getMaxSignalAgeSeconds() {
            return maxSignalAgeSeconds;
        }

        public void setMaxSignalAgeSeconds(long maxSignalAgeSeconds) {
            this.maxSignalAgeSeconds = maxSignalAgeSeconds;
        }

        public Map<String, String> getCredentials() {
            return credentials;
        }

        public void setCredentials(Map<String, String> credentials) {
            this.credentials = credentials;
        }
    }
}
