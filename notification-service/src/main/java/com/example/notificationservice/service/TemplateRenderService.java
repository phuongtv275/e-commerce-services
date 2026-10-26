package com.example.notificationservice.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class TemplateRenderService {

    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{\\{(\\w+)}}");

    public String renderSubject(String subjectTemplate, Map<String, Object> params) {
        if (subjectTemplate == null) {
            return "";
        }
        return replacePlaceholders(subjectTemplate, params, false);
    }

    public String renderBody(String bodyTemplate, Map<String, Object> params) {
        if (bodyTemplate == null) {
            return "";
        }
        return replacePlaceholders(bodyTemplate, params, true);
    }

    private String replacePlaceholders(String template, Map<String, Object> params, boolean escapeHtml) {
        if (params == null || params.isEmpty()) {
            return template;
        }

        Matcher matcher = PLACEHOLDER_PATTERN.matcher(template);
        StringBuilder sb = new StringBuilder();

        while (matcher.find()) {
            String key = matcher.group(1);
            Object value = params.get(key);
            String replacement;

            if (value == null) {
                replacement = "";
            } else if (escapeHtml) {
                // Pre-formatted safe HTML keys should not be double escaped
                if ("orderItemsHtml".equals(key)) {
                    replacement = value.toString();
                } else {
                    replacement = HtmlUtils.htmlEscape(value.toString());
                }
            } else {
                replacement = value.toString();
            }

            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);

        return sb.toString();
    }
}
