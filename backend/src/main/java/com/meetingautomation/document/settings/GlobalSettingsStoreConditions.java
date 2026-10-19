package com.meetingautomation.document.settings;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.StringUtils;

final class GlobalSettingsStoreConditions {
    private GlobalSettingsStoreConditions() { }

    static final class FileSettingsConfigured implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            var environment = context.getEnvironment();
            return !Boolean.parseBoolean(environment.getProperty("RENDER", "false"))
                    && StringUtils.hasText(environment.getProperty("DOCUMENT_SETTINGS_DIR"))
                    && StringUtils.hasText(environment.getProperty("DOCUMENT_SETTINGS_ENCRYPTION_KEY"));
        }
    }

    static final class RuntimeSettingsRequired implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            var environment = context.getEnvironment();
            return Boolean.parseBoolean(environment.getProperty("RENDER", "false"))
                    || !StringUtils.hasText(environment.getProperty("DOCUMENT_SETTINGS_DIR"))
                    || !StringUtils.hasText(environment.getProperty("DOCUMENT_SETTINGS_ENCRYPTION_KEY"));
        }
    }
}
