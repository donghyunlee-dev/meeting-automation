package com.meetingautomation.api.session;

import com.meetingautomation.api.template.TemplateDefinition;

record MinutesRegenerationJob(String operationId, String sessionId, TemplateDefinition template) { }

