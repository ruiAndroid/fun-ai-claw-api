package com.fun.ai.claw.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@ConfigurationProperties(prefix = "app.images")
public class ImageCatalogProperties {

    private boolean allowCustomImage = false;
    private List<Preset> presets = new ArrayList<>();

    public boolean isAllowCustomImage() {
        return allowCustomImage;
    }

    public void setAllowCustomImage(boolean allowCustomImage) {
        this.allowCustomImage = allowCustomImage;
    }

    public List<Preset> getPresets() {
        return presets;
    }

    public void setPresets(List<Preset> presets) {
        this.presets = presets;
    }

    public static class Preset {
        private String id;
        private String name;
        private String image;
        private String description;
        private boolean recommended;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getImage() {
            return image;
        }

        public void setImage(String image) {
            this.image = image;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public boolean isRecommended() {
            return recommended;
        }

        public void setRecommended(boolean recommended) {
            this.recommended = recommended;
        }
    }
}

