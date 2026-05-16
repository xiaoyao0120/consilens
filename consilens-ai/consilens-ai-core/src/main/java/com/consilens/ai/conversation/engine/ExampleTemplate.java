package com.consilens.ai.conversation.engine;

/**
 * A loaded example configuration template.
 */
public class ExampleTemplate {

    private final String name;
    private final String title;
    private final String description;
    private final String sourceType;
    private final String targetType;
    private final String resourceType;
    private final String content;

    public ExampleTemplate(String name, String title, String description,
                           String sourceType, String targetType, String resourceType,
                           String content) {
        this.name = name;
        this.title = title;
        this.description = description;
        this.sourceType = sourceType;
        this.targetType = targetType;
        this.resourceType = resourceType;
        this.content = content;
    }

    public String getName() {
        return name;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public String getSourceType() {
        return sourceType;
    }

    public String getTargetType() {
        return targetType;
    }

    public String getResourceType() {
        return resourceType;
    }

    public String getContent() {
        return content;
    }

    public String toSummaryLine() {
        StringBuilder sb = new StringBuilder();
        sb.append("- ").append(name);
        if (title != null && !title.isBlank()) {
            sb.append(": ").append(title);
        }
        if (sourceType != null || targetType != null) {
            sb.append(" (");
            if (sourceType != null) {
                sb.append(sourceType);
            }
            if (targetType != null && !targetType.equals(sourceType)) {
                sb.append(" → ").append(targetType);
            }
            if (resourceType != null) {
                sb.append(", ").append(resourceType);
            }
            sb.append(")");
        }
        return sb.toString();
    }
}
