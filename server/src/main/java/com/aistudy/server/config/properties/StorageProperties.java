package com.aistudy.server.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Storage policy for {@code aistudy.storage.*}.
 */
@ConfigurationProperties(prefix = "aistudy.storage")
public class StorageProperties {

    private final Local local = new Local();

    public Local getLocal() {
        return local;
    }

    public static class Local {

        /**
         * Local filesystem root for stored assets. The YAML default resolves
         * {@code ${user.home}}; an empty Java default fails safely when
         * neither YAML nor an environment override is present.
         */
        private String root = "";

        public String getRoot() {
            return root;
        }

        public void setRoot(String root) {
            this.root = root;
        }
    }
}
