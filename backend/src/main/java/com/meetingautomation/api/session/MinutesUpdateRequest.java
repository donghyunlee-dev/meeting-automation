package com.meetingautomation.api.session;

import com.meetingautomation.api.error.ApiValidationException;
import com.meetingautomation.document.StructuredMinutes;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;

final class MinutesUpdateRequest {
    private MinutesUpdateRequest() {
    }

    static StructuredMinutes parse(JsonNode body) {
        if (body == null || !body.isObject() || body.size() != 7
                || body.get("templateId") == null || body.get("templateVersion") == null
                || body.get("summary") == null || body.get("discussionPoints") == null
                || body.get("decisions") == null || body.get("actionItems") == null
                || body.get("followUps") == null) {
            throw invalid("request");
        }
        String templateId = requiredText(body, "templateId");
        String templateVersion = requiredText(body, "templateVersion");
        String summary = requiredText(body, "summary");
        List<String> discussionPoints = readTextArray(body, "discussionPoints");
        List<String> decisions = readTextArray(body, "decisions");
        List<StructuredMinutes.ActionItem> actionItems = readActionItems(body.get("actionItems"));
        List<String> followUps = readTextArray(body, "followUps");
        return new StructuredMinutes(templateId, templateVersion, summary,
                discussionPoints, decisions, actionItems, followUps);
    }

    private static String requiredText(JsonNode object, String field) {
        JsonNode node = object.get(field);
        if (node == null || !node.isString()) throw invalid(field);
        return node.stringValue();
    }

    private static List<String> readTextArray(JsonNode object, String field) {
        JsonNode array = object.get(field);
        if (array == null || !array.isArray()) throw invalid(field);
        List<String> values = new ArrayList<>(array.size());
        for (JsonNode item : array) {
            if (item == null || !item.isString() || item.stringValue().isBlank()) throw invalid(field);
            values.add(item.stringValue());
        }
        return List.copyOf(values);
    }

    private static List<StructuredMinutes.ActionItem> readActionItems(JsonNode array) {
        if (array == null || !array.isArray()) throw invalid("actionItems");
        List<StructuredMinutes.ActionItem> items = new ArrayList<>(array.size());
        for (JsonNode item : array) {
            if (item == null || !item.isObject() || item.size() != 3
                    || item.get("ownerParticipantId") == null || item.get("task") == null
                    || item.get("dueDate") == null) {
                throw invalid("actionItems");
            }
            JsonNode ownerNode = item.get("ownerParticipantId");
            String ownerParticipantId;
            if (ownerNode == null || ownerNode.isNull()) {
                ownerParticipantId = null;
            } else if (ownerNode.isString() && !ownerNode.stringValue().isBlank()) {
                ownerParticipantId = ownerNode.stringValue();
            } else {
                throw invalid("actionItems");
            }

            JsonNode taskNode = item.get("task");
            if (taskNode == null || !taskNode.isString() || taskNode.stringValue().isBlank()) {
                throw invalid("actionItems");
            }

            JsonNode dueDateNode = item.get("dueDate");
            LocalDate dueDate = null;
            if (dueDateNode != null && !dueDateNode.isNull()) {
                if (!dueDateNode.isString() || !dueDateNode.stringValue().matches("\\d{4}-\\d{2}-\\d{2}")) {
                    throw invalid("actionItems");
                }
                try {
                    dueDate = LocalDate.parse(dueDateNode.stringValue());
                } catch (DateTimeParseException invalidDate) {
                    throw invalid("actionItems");
                }
            }
            items.add(new StructuredMinutes.ActionItem(ownerParticipantId, taskNode.stringValue(), dueDate));
        }
        return List.copyOf(items);
    }

    private static ApiValidationException invalid(String field) {
        return new ApiValidationException(field);
    }
}
