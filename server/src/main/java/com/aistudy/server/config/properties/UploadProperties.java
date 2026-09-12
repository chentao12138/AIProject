package com.aistudy.server.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * Upload policy for {@code aistudy.upload.*}.
 */
@ConfigurationProperties(prefix = "aistudy.upload")
public class UploadProperties {

    /**
     * Canonical maximum file size for uploaded assets.
     */
    private DataSize maxFileSize = DataSize.ofMegabytes(1024);

    public DataSize getMaxFileSize() {
        return maxFileSize;
    }

    public void setMaxFileSize(DataSize maxFileSize) {
        this.maxFileSize = maxFileSize;
    }
}
