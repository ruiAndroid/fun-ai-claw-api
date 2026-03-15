package com.fun.ai.claw.api.service;

import com.fun.ai.claw.api.model.InstanceChannelsConfigResponse;
import com.fun.ai.claw.api.model.InstanceConfigResponse;
import com.fun.ai.claw.api.model.UpsertInstanceChannelsConfigRequest;
import com.fun.ai.claw.api.model.UpsertInstanceConfigRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class InstanceChannelsConfigService {
    private static final String MASKED_SECRET = "***MASKED***";
    private static final long DEFAULT_MESSAGE_TIMEOUT_SECS = 300L;

    private static final Pattern TABLE_HEADER_PATTERN = Pattern.compile("(?m)^\\s*(\\[\\[?.+?]]?)\\s*$");
    private static final Pattern CLI_LINE_PATTERN = Pattern.compile("(?m)^\\s*cli\\s*=.*(?:\\R|$)");
    private static final Pattern MESSAGE_TIMEOUT_LINE_PATTERN = Pattern.compile("(?m)^\\s*message_timeout_secs\\s*=.*(?:\\R|$)");
    private static final Pattern DINGTALK_CLIENT_ID_LINE_PATTERN = Pattern.compile("(?m)^\\s*client_id\\s*=.*(?:\\R|$)");
    private static final Pattern DINGTALK_CLIENT_SECRET_LINE_PATTERN = Pattern.compile("(?m)^\\s*client_secret\\s*=.*(?:\\R|$)");
    private static final Pattern QQ_APP_ID_LINE_PATTERN = Pattern.compile("(?m)^\\s*app_id\\s*=.*(?:\\R|$)");
    private static final Pattern QQ_APP_SECRET_LINE_PATTERN = Pattern.compile("(?m)^\\s*app_secret\\s*=.*(?:\\R|$)");
    private static final Pattern WECOM_CORP_ID_LINE_PATTERN = Pattern.compile("(?m)^\\s*corp_id\\s*=.*(?:\\R|$)");
    private static final Pattern WECOM_AGENT_ID_LINE_PATTERN = Pattern.compile("(?m)^\\s*agent_id\\s*=.*(?:\\R|$)");
    private static final Pattern WECOM_SECRET_LINE_PATTERN = Pattern.compile("(?m)^\\s*secret\\s*=.*(?:\\R|$)");
    private static final Pattern WECOM_TOKEN_LINE_PATTERN = Pattern.compile("(?m)^\\s*token\\s*=.*(?:\\R|$)");
    private static final Pattern WECOM_ENCODING_AES_KEY_LINE_PATTERN = Pattern.compile("(?m)^\\s*encoding_aes_key\\s*=.*(?:\\R|$)");

    private static final Pattern CLI_PATTERN = Pattern.compile("(?m)^\\s*cli\\s*=\\s*(true|false)\\s*$");
    private static final Pattern MESSAGE_TIMEOUT_PATTERN = Pattern.compile("(?m)^\\s*message_timeout_secs\\s*=\\s*(\\d+)\\s*$");
    private static final Pattern DINGTALK_CLIENT_ID_BASIC_PATTERN = Pattern.compile("(?m)^\\s*client_id\\s*=\\s*\"((?:\\\\.|[^\"\\\\])*)\"\\s*$");
    private static final Pattern DINGTALK_CLIENT_ID_LITERAL_PATTERN = Pattern.compile("(?m)^\\s*client_id\\s*=\\s*'([^'\\r\\n]*)'\\s*$");
    private static final Pattern DINGTALK_CLIENT_SECRET_BASIC_PATTERN = Pattern.compile("(?m)^\\s*client_secret\\s*=\\s*\"((?:\\\\.|[^\"\\\\])*)\"\\s*$");
    private static final Pattern DINGTALK_CLIENT_SECRET_LITERAL_PATTERN = Pattern.compile("(?m)^\\s*client_secret\\s*=\\s*'([^'\\r\\n]*)'\\s*$");
    private static final Pattern QQ_APP_ID_BASIC_PATTERN = Pattern.compile("(?m)^\\s*app_id\\s*=\\s*\"((?:\\\\.|[^\"\\\\])*)\"\\s*$");
    private static final Pattern QQ_APP_ID_LITERAL_PATTERN = Pattern.compile("(?m)^\\s*app_id\\s*=\\s*'([^'\\r\\n]*)'\\s*$");
    private static final Pattern QQ_APP_SECRET_BASIC_PATTERN = Pattern.compile("(?m)^\\s*app_secret\\s*=\\s*\"((?:\\\\.|[^\"\\\\])*)\"\\s*$");
    private static final Pattern QQ_APP_SECRET_LITERAL_PATTERN = Pattern.compile("(?m)^\\s*app_secret\\s*=\\s*'([^'\\r\\n]*)'\\s*$");
    private static final Pattern WECOM_CORP_ID_BASIC_PATTERN = Pattern.compile("(?m)^\\s*corp_id\\s*=\\s*\"((?:\\\\.|[^\"\\\\])*)\"\\s*$");
    private static final Pattern WECOM_CORP_ID_LITERAL_PATTERN = Pattern.compile("(?m)^\\s*corp_id\\s*=\\s*'([^'\\r\\n]*)'\\s*$");
    private static final Pattern WECOM_AGENT_ID_BASIC_PATTERN = Pattern.compile("(?m)^\\s*agent_id\\s*=\\s*\"((?:\\\\.|[^\"\\\\])*)\"\\s*$");
    private static final Pattern WECOM_AGENT_ID_LITERAL_PATTERN = Pattern.compile("(?m)^\\s*agent_id\\s*=\\s*'([^'\\r\\n]*)'\\s*$");
    private static final Pattern WECOM_SECRET_BASIC_PATTERN = Pattern.compile("(?m)^\\s*secret\\s*=\\s*\"((?:\\\\.|[^\"\\\\])*)\"\\s*$");
    private static final Pattern WECOM_SECRET_LITERAL_PATTERN = Pattern.compile("(?m)^\\s*secret\\s*=\\s*'([^'\\r\\n]*)'\\s*$");
    private static final Pattern WECOM_TOKEN_BASIC_PATTERN = Pattern.compile("(?m)^\\s*token\\s*=\\s*\"((?:\\\\.|[^\"\\\\])*)\"\\s*$");
    private static final Pattern WECOM_TOKEN_LITERAL_PATTERN = Pattern.compile("(?m)^\\s*token\\s*=\\s*'([^'\\r\\n]*)'\\s*$");
    private static final Pattern WECOM_ENCODING_AES_KEY_BASIC_PATTERN = Pattern.compile("(?m)^\\s*encoding_aes_key\\s*=\\s*\"((?:\\\\.|[^\"\\\\])*)\"\\s*$");
    private static final Pattern WECOM_ENCODING_AES_KEY_LITERAL_PATTERN = Pattern.compile("(?m)^\\s*encoding_aes_key\\s*=\\s*'([^'\\r\\n]*)'\\s*$");

    private final InstanceConfigService instanceConfigService;
    private final InstanceConfigMutationService instanceConfigMutationService;

    public InstanceChannelsConfigService(InstanceConfigService instanceConfigService,
                                         InstanceConfigMutationService instanceConfigMutationService) {
        this.instanceConfigService = instanceConfigService;
        this.instanceConfigMutationService = instanceConfigMutationService;
    }

    public InstanceChannelsConfigResponse get(UUID instanceId) {
        InstanceConfigResponse config = instanceConfigService.get(instanceId);
        ParsedChannelsConfig parsed = parseConfig(config.configToml());
        return buildResponse(config, parsed);
    }

    public InstanceChannelsConfigResponse upsert(UUID instanceId, UpsertInstanceChannelsConfigRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "request body is required");
        }

        InstanceConfigResponse currentConfig = instanceConfigService.get(instanceId);
        ParsedChannelsConfig currentParsed = parseConfig(currentConfig.configToml());

        boolean cliEnabled = request.cliEnabled() != null ? request.cliEnabled() : currentParsed.root().cliEnabled();
        long messageTimeoutSecs = request.messageTimeoutSecs() != null
                ? normalizeMessageTimeout(request.messageTimeoutSecs())
                : currentParsed.root().messageTimeoutSecs();

        boolean dingtalkEnabled = request.dingtalkEnabled() != null
                ? request.dingtalkEnabled()
                : currentParsed.dingtalk().enabled();
        boolean qqEnabled = request.qqEnabled() != null ? request.qqEnabled() : currentParsed.qq().enabled();
        boolean wecomEnabled = request.wecomEnabled() != null
                ? request.wecomEnabled()
                : currentParsed.wecom().enabled();

        ChannelSectionState dingtalkState = resolveDingTalkState(currentParsed.dingtalk(), request, dingtalkEnabled);
        ChannelSectionState qqState = resolveQqState(currentParsed.qq(), request, qqEnabled);
        WeComSectionState wecomState = resolveWeComState(currentParsed.wecom(), request, wecomEnabled);

        String updatedToml = rewriteConfig(
                currentParsed,
                new RootSectionState(cliEnabled, messageTimeoutSecs, currentParsed.root().extras()),
                dingtalkState,
                qqState,
                wecomState
        );

        InstanceConfigResponse persisted = instanceConfigMutationService.upsert(
                instanceId,
                new UpsertInstanceConfigRequest(updatedToml, request.updatedBy())
        );
        ParsedChannelsConfig parsedPersisted = parseConfig(persisted.configToml());
        return buildResponse(persisted, parsedPersisted);
    }

    private ChannelSectionState resolveDingTalkState(ParsedDingTalkSection current,
                                                     UpsertInstanceChannelsConfigRequest request,
                                                     boolean enabled) {
        if (!enabled) {
            return new ChannelSectionState(false, null, null, List.of(), current.extras(), "channels_config.dingtalk");
        }
        String clientId = resolveRequiredString(
                request.dingtalkClientId(),
                current.clientId(),
                "dingtalkClientId"
        );
        String clientSecret = resolveRequiredSecret(
                request.dingtalkClientSecret(),
                current.clientSecret(),
                "dingtalkClientSecret"
        );
        List<String> allowedUsers = normalizeStringList(
                request.dingtalkAllowedUsers(),
                current.allowedUsers()
        );
        return new ChannelSectionState(true, clientId, clientSecret, allowedUsers, current.extras(), "channels_config.dingtalk");
    }

    private ChannelSectionState resolveQqState(ParsedQqSection current,
                                               UpsertInstanceChannelsConfigRequest request,
                                               boolean enabled) {
        if (!enabled) {
            return new ChannelSectionState(false, null, null, List.of(), current.extras(), "channels_config.qq");
        }
        String appId = resolveRequiredString(
                request.qqAppId(),
                current.appId(),
                "qqAppId"
        );
        String appSecret = resolveRequiredSecret(
                request.qqAppSecret(),
                current.appSecret(),
                "qqAppSecret"
        );
        List<String> allowedUsers = normalizeStringList(
                request.qqAllowedUsers(),
                current.allowedUsers()
        );
        return new ChannelSectionState(true, appId, appSecret, allowedUsers, current.extras(), "channels_config.qq");
    }

    private WeComSectionState resolveWeComState(ParsedWeComSection current,
                                                UpsertInstanceChannelsConfigRequest request,
                                                boolean enabled) {
        if (!enabled) {
            return new WeComSectionState(false, null, null, null, null, null, List.of(), current.extras());
        }
        String corpId = resolveRequiredString(
                request.wecomCorpId(),
                current.corpId(),
                "wecomCorpId"
        );
        String agentId = resolveRequiredString(
                request.wecomAgentId(),
                current.agentId(),
                "wecomAgentId"
        );
        String secret = resolveRequiredSecret(
                request.wecomSecret(),
                current.secret(),
                "wecomSecret"
        );
        String token = resolveRequiredSecret(
                request.wecomToken(),
                current.token(),
                "wecomToken"
        );
        String encodingAesKey = resolveRequiredSecret(
                request.wecomEncodingAesKey(),
                current.encodingAesKey(),
                "wecomEncodingAesKey"
        );
        List<String> allowedUsers = normalizeStringList(
                request.wecomAllowedUsers(),
                current.allowedUsers()
        );
        return new WeComSectionState(
                true,
                corpId,
                agentId,
                secret,
                token,
                encodingAesKey,
                allowedUsers,
                current.extras()
        );
    }

    private InstanceChannelsConfigResponse buildResponse(InstanceConfigResponse config,
                                                         ParsedChannelsConfig parsed) {
        return new InstanceChannelsConfigResponse(
                config.instanceId(),
                config.runtimeConfigPath(),
                config.source(),
                config.overwriteOnStart(),
                config.overrideExists(),
                parsed.root().cliEnabled(),
                parsed.root().messageTimeoutSecs(),
                parsed.dingtalk().enabled(),
                nullToEmpty(parsed.dingtalk().clientId()),
                maskSecret(parsed.dingtalk().clientSecret()),
                parsed.dingtalk().allowedUsers(),
                parsed.qq().enabled(),
                nullToEmpty(parsed.qq().appId()),
                maskSecret(parsed.qq().appSecret()),
                parsed.qq().allowedUsers(),
                parsed.wecom().enabled(),
                nullToEmpty(parsed.wecom().corpId()),
                nullToEmpty(parsed.wecom().agentId()),
                maskSecret(parsed.wecom().secret()),
                maskSecret(parsed.wecom().token()),
                maskSecret(parsed.wecom().encodingAesKey()),
                parsed.wecom().allowedUsers(),
                config.overrideUpdatedAt(),
                config.overrideUpdatedBy()
        );
    }

    private ParsedChannelsConfig parseConfig(String configToml) {
        String normalizedToml = normalizeToml(configToml);
        ParsedDocument document = parseDocument(normalizedToml);
        SectionBlock rootSection = findSection(document.sections(), "channels_config");
        SectionBlock dingtalkSection = findSection(document.sections(), "channels_config.dingtalk");
        SectionBlock qqSection = findSection(document.sections(), "channels_config.qq");
        SectionBlock wecomSection = findSection(document.sections(), "channels_config.wecom");
        List<SectionBlock> preservedGroupSections = document.sections().stream()
                .filter(section -> isChannelsGroup(section.headerName()))
                .filter(section -> !isManagedChannelsHeader(section.headerName()))
                .toList();

        ParsedRootSection root = parseRootSection(rootSection);
        ParsedDingTalkSection dingtalk = parseDingTalkSection(dingtalkSection);
        ParsedQqSection qq = parseQqSection(qqSection);
        ParsedWeComSection wecom = parseWeComSection(wecomSection);

        return new ParsedChannelsConfig(document, root, dingtalk, qq, wecom, preservedGroupSections);
    }

    private ParsedRootSection parseRootSection(SectionBlock section) {
        String body = extractSectionBody(section);
        boolean cliEnabled = findBooleanValue(body, CLI_PATTERN, true);
        long messageTimeoutSecs = findLongValue(body, MESSAGE_TIMEOUT_PATTERN, DEFAULT_MESSAGE_TIMEOUT_SECS);
        String extras = cleanupExtras(removeKnownRootProperties(body));
        return new ParsedRootSection(cliEnabled, messageTimeoutSecs, extras);
    }

    private ParsedDingTalkSection parseDingTalkSection(SectionBlock section) {
        if (section == null) {
            return new ParsedDingTalkSection(false, null, null, List.of(), "");
        }
        String body = extractSectionBody(section);
        String clientId = findStringValue(body, DINGTALK_CLIENT_ID_BASIC_PATTERN, DINGTALK_CLIENT_ID_LITERAL_PATTERN);
        String clientSecret = findStringValue(body, DINGTALK_CLIENT_SECRET_BASIC_PATTERN, DINGTALK_CLIENT_SECRET_LITERAL_PATTERN);
        List<String> allowedUsers = findStringArrayValue(body, "allowed_users");
        String extras = cleanupExtras(removeAllowedUsersProperty(
                DINGTALK_CLIENT_SECRET_LINE_PATTERN.matcher(
                        DINGTALK_CLIENT_ID_LINE_PATTERN.matcher(body).replaceAll("")
                ).replaceAll("")
        ));
        return new ParsedDingTalkSection(true, clientId, clientSecret, allowedUsers, extras);
    }

    private ParsedQqSection parseQqSection(SectionBlock section) {
        if (section == null) {
            return new ParsedQqSection(false, null, null, List.of(), "");
        }
        String body = extractSectionBody(section);
        String appId = findStringValue(body, QQ_APP_ID_BASIC_PATTERN, QQ_APP_ID_LITERAL_PATTERN);
        String appSecret = findStringValue(body, QQ_APP_SECRET_BASIC_PATTERN, QQ_APP_SECRET_LITERAL_PATTERN);
        List<String> allowedUsers = findStringArrayValue(body, "allowed_users");
        String extras = cleanupExtras(removeAllowedUsersProperty(
                QQ_APP_SECRET_LINE_PATTERN.matcher(
                        QQ_APP_ID_LINE_PATTERN.matcher(body).replaceAll("")
                ).replaceAll("")
        ));
        return new ParsedQqSection(true, appId, appSecret, allowedUsers, extras);
    }

    private ParsedWeComSection parseWeComSection(SectionBlock section) {
        if (section == null) {
            return new ParsedWeComSection(false, null, null, null, null, null, List.of(), "");
        }
        String body = extractSectionBody(section);
        String corpId = findStringValue(body, WECOM_CORP_ID_BASIC_PATTERN, WECOM_CORP_ID_LITERAL_PATTERN);
        String agentId = findStringValue(body, WECOM_AGENT_ID_BASIC_PATTERN, WECOM_AGENT_ID_LITERAL_PATTERN);
        String secret = findStringValue(body, WECOM_SECRET_BASIC_PATTERN, WECOM_SECRET_LITERAL_PATTERN);
        String token = findStringValue(body, WECOM_TOKEN_BASIC_PATTERN, WECOM_TOKEN_LITERAL_PATTERN);
        String encodingAesKey = findStringValue(
                body,
                WECOM_ENCODING_AES_KEY_BASIC_PATTERN,
                WECOM_ENCODING_AES_KEY_LITERAL_PATTERN
        );
        List<String> allowedUsers = findStringArrayValue(body, "allowed_users");
        String extras = cleanupExtras(removeAllowedUsersProperty(
                WECOM_ENCODING_AES_KEY_LINE_PATTERN.matcher(
                        WECOM_TOKEN_LINE_PATTERN.matcher(
                                WECOM_SECRET_LINE_PATTERN.matcher(
                                        WECOM_AGENT_ID_LINE_PATTERN.matcher(
                                                WECOM_CORP_ID_LINE_PATTERN.matcher(body).replaceAll("")
                                        ).replaceAll("")
                                ).replaceAll("")
                        ).replaceAll("")
                ).replaceAll("")
        ));
        return new ParsedWeComSection(true, corpId, agentId, secret, token, encodingAesKey, allowedUsers, extras);
    }

    private String removeKnownRootProperties(String body) {
        return MESSAGE_TIMEOUT_LINE_PATTERN.matcher(CLI_LINE_PATTERN.matcher(body).replaceAll("")).replaceAll("");
    }

    private String rewriteConfig(ParsedChannelsConfig current,
                                 RootSectionState root,
                                 ChannelSectionState dingtalk,
                                 ChannelSectionState qq,
                                 WeComSectionState wecom) {
        String renderedGroup = renderChannelsGroup(root, dingtalk, qq, wecom, current.preservedGroupSections());
        return rebuildDocument(current.document(), renderedGroup);
    }

    private String renderChannelsGroup(RootSectionState root,
                                       ChannelSectionState dingtalk,
                                       ChannelSectionState qq,
                                       WeComSectionState wecom,
                                       List<SectionBlock> preservedGroupSections) {
        List<String> blocks = new ArrayList<>();
        blocks.add(renderRootSection(root));
        if (dingtalk.enabled()) {
            blocks.add(renderChannelSection(dingtalk, "client_id", "client_secret"));
        }
        if (qq.enabled()) {
            blocks.add(renderChannelSection(qq, "app_id", "app_secret"));
        }
        if (wecom.enabled()) {
            blocks.add(renderWeComSection(wecom));
        }
        preservedGroupSections.stream()
                .map(SectionBlock::rawText)
                .map(String::strip)
                .filter(StringUtils::hasText)
                .forEach(blocks::add);
        return String.join("\n\n", blocks);
    }

    private String renderRootSection(RootSectionState state) {
        StringBuilder builder = new StringBuilder();
        builder.append("[channels_config]\n");
        builder.append("cli = ").append(state.cliEnabled()).append('\n');
        builder.append("message_timeout_secs = ").append(state.messageTimeoutSecs()).append('\n');
        appendExtras(builder, state.extras());
        return builder.toString().strip();
    }

    private String renderChannelSection(ChannelSectionState state, String idKey, String secretKey) {
        StringBuilder builder = new StringBuilder();
        builder.append('[').append(state.headerName()).append("]\n");
        builder.append(idKey).append(" = ").append(renderTomlString(state.idValue())).append('\n');
        builder.append(secretKey).append(" = ").append(renderTomlString(state.secretValue())).append('\n');
        builder.append("allowed_users = ").append(renderTomlStringArray(state.allowedUsers())).append('\n');
        appendExtras(builder, state.extras());
        return builder.toString().strip();
    }

    private String renderWeComSection(WeComSectionState state) {
        StringBuilder builder = new StringBuilder();
        builder.append("[channels_config.wecom]\n");
        builder.append("corp_id = ").append(renderTomlString(state.corpId())).append('\n');
        builder.append("agent_id = ").append(renderTomlString(state.agentId())).append('\n');
        builder.append("secret = ").append(renderTomlString(state.secret())).append('\n');
        builder.append("token = ").append(renderTomlString(state.token())).append('\n');
        builder.append("encoding_aes_key = ").append(renderTomlString(state.encodingAesKey())).append('\n');
        builder.append("allowed_users = ").append(renderTomlStringArray(state.allowedUsers())).append('\n');
        appendExtras(builder, state.extras());
        return builder.toString().strip();
    }

    private String rebuildDocument(ParsedDocument document, String renderedChannelsGroup) {
        List<String> blocks = new ArrayList<>();
        if (StringUtils.hasText(document.preamble())) {
            blocks.add(document.preamble().strip());
        }

        boolean insertedChannelsGroup = false;
        for (SectionBlock section : document.sections()) {
            if (isChannelsGroup(section.headerName())) {
                if (!insertedChannelsGroup && StringUtils.hasText(renderedChannelsGroup)) {
                    blocks.add(renderedChannelsGroup.strip());
                    insertedChannelsGroup = true;
                }
                continue;
            }
            if (StringUtils.hasText(section.rawText())) {
                blocks.add(section.rawText().strip());
            }
        }
        if (!insertedChannelsGroup && StringUtils.hasText(renderedChannelsGroup)) {
            blocks.add(renderedChannelsGroup.strip());
        }

        String joined = blocks.stream()
                .filter(StringUtils::hasText)
                .map(String::strip)
                .reduce((left, right) -> left + "\n\n" + right)
                .orElse("");
        return joined.isEmpty() ? "" : joined + "\n";
    }

    private ParsedDocument parseDocument(String normalizedToml) {
        Matcher matcher = TABLE_HEADER_PATTERN.matcher(normalizedToml);
        List<HeaderMatch> headers = new ArrayList<>();
        while (matcher.find()) {
            headers.add(new HeaderMatch(normalizeHeader(matcher.group(1)), matcher.start()));
        }
        if (headers.isEmpty()) {
            return new ParsedDocument(normalizedToml, List.of());
        }

        String preamble = normalizedToml.substring(0, headers.get(0).start());
        List<SectionBlock> sections = new ArrayList<>();
        for (int index = 0; index < headers.size(); index++) {
            int start = headers.get(index).start();
            int end = index + 1 < headers.size() ? headers.get(index + 1).start() : normalizedToml.length();
            String rawText = normalizedToml.substring(start, end).strip();
            if (!rawText.isEmpty()) {
                sections.add(new SectionBlock(headers.get(index).name(), rawText + "\n"));
            }
        }
        return new ParsedDocument(preamble, sections);
    }

    private SectionBlock findSection(List<SectionBlock> sections, String headerName) {
        return sections.stream()
                .filter(section -> headerName.equals(section.headerName()))
                .findFirst()
                .orElse(null);
    }

    private String extractSectionBody(SectionBlock section) {
        if (section == null || !StringUtils.hasText(section.rawText())) {
            return "";
        }
        int newlineIndex = section.rawText().indexOf('\n');
        if (newlineIndex < 0 || newlineIndex + 1 >= section.rawText().length()) {
            return "";
        }
        return section.rawText().substring(newlineIndex + 1);
    }

    private boolean isChannelsGroup(String headerName) {
        return "channels_config".equals(headerName) || headerName.startsWith("channels_config.");
    }

    private boolean isManagedChannelsHeader(String headerName) {
        return "channels_config".equals(headerName)
                || "channels_config.dingtalk".equals(headerName)
                || "channels_config.qq".equals(headerName)
                || "channels_config.wecom".equals(headerName);
    }

    private String normalizeHeader(String rawHeader) {
        String trimmed = rawHeader == null ? "" : rawHeader.trim();
        if (trimmed.startsWith("[[") && trimmed.endsWith("]]")) {
            return trimmed.substring(2, trimmed.length() - 2).trim();
        }
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            return trimmed.substring(1, trimmed.length() - 1).trim();
        }
        return trimmed;
    }

    private boolean findBooleanValue(String content, Pattern pattern, boolean defaultValue) {
        Matcher matcher = pattern.matcher(content);
        if (!matcher.find()) {
            return defaultValue;
        }
        return Boolean.parseBoolean(matcher.group(1).trim());
    }

    private long findLongValue(String content, Pattern pattern, long defaultValue) {
        Matcher matcher = pattern.matcher(content);
        if (!matcher.find()) {
            return defaultValue;
        }
        try {
            return Long.parseLong(matcher.group(1).trim());
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private String findStringValue(String content, Pattern basicPattern, Pattern literalPattern) {
        Matcher basicMatcher = basicPattern.matcher(content);
        if (basicMatcher.find()) {
            return unescapeTomlString(basicMatcher.group(1)).trim();
        }
        Matcher literalMatcher = literalPattern.matcher(content);
        if (literalMatcher.find()) {
            return literalMatcher.group(1).trim();
        }
        return null;
    }

    private List<String> findStringArrayValue(String content, String key) {
        PropertySpan propertySpan = findArrayProperty(content, key);
        if (propertySpan == null) {
            return List.of();
        }
        return parseTomlStringArray(propertySpan.rawValue());
    }

    private PropertySpan findArrayProperty(String content, String key) {
        Pattern pattern = Pattern.compile("(?m)^\\s*" + Pattern.quote(key) + "\\s*=\\s*\\[");
        Matcher matcher = pattern.matcher(content);
        if (!matcher.find()) {
            return null;
        }
        int arrayStart = content.indexOf('[', matcher.start());
        if (arrayStart < 0) {
            return null;
        }

        boolean inDoubleQuote = false;
        boolean inSingleQuote = false;
        boolean escaped = false;
        int depth = 0;
        int cursor = arrayStart;
        int arrayEnd = -1;
        while (cursor < content.length()) {
            char current = content.charAt(cursor);
            if (escaped) {
                escaped = false;
                cursor++;
                continue;
            }
            if (inDoubleQuote) {
                if (current == '\\') {
                    escaped = true;
                } else if (current == '"') {
                    inDoubleQuote = false;
                }
                cursor++;
                continue;
            }
            if (inSingleQuote) {
                if (current == '\'') {
                    inSingleQuote = false;
                }
                cursor++;
                continue;
            }
            if (current == '"') {
                inDoubleQuote = true;
            } else if (current == '\'') {
                inSingleQuote = true;
            } else if (current == '[') {
                depth++;
            } else if (current == ']') {
                depth--;
                if (depth == 0) {
                    arrayEnd = cursor + 1;
                    break;
                }
            }
            cursor++;
        }
        if (arrayEnd < 0) {
            return null;
        }
        int propertyEnd = arrayEnd;
        while (propertyEnd < content.length()) {
            char current = content.charAt(propertyEnd);
            if (current == '\n') {
                propertyEnd++;
                break;
            }
            if (current == '\r') {
                propertyEnd++;
                if (propertyEnd < content.length() && content.charAt(propertyEnd) == '\n') {
                    propertyEnd++;
                }
                break;
            }
            propertyEnd++;
        }
        return new PropertySpan(content.substring(arrayStart + 1, arrayEnd - 1), matcher.start(), propertyEnd);
    }

    private List<String> parseTomlStringArray(String rawArray) {
        List<String> values = new ArrayList<>();
        int cursor = 0;
        while (cursor < rawArray.length()) {
            char current = rawArray.charAt(cursor);
            if (Character.isWhitespace(current) || current == ',') {
                cursor++;
                continue;
            }
            if (current == '#') {
                while (cursor < rawArray.length() && rawArray.charAt(cursor) != '\n') {
                    cursor++;
                }
                continue;
            }
            if (current != '"' && current != '\'') {
                int nextComma = rawArray.indexOf(',', cursor);
                String token = nextComma >= 0 ? rawArray.substring(cursor, nextComma) : rawArray.substring(cursor);
                String normalized = token.trim();
                if (!normalized.isEmpty()) {
                    values.add(normalized);
                }
                cursor = nextComma >= 0 ? nextComma + 1 : rawArray.length();
                continue;
            }

            char quote = current;
            cursor++;
            StringBuilder builder = new StringBuilder();
            boolean escaped = false;
            while (cursor < rawArray.length()) {
                char valueChar = rawArray.charAt(cursor++);
                if (quote == '"' && escaped) {
                    switch (valueChar) {
                        case 'n' -> builder.append('\n');
                        case 'r' -> builder.append('\r');
                        case 't' -> builder.append('\t');
                        case '"' -> builder.append('"');
                        case '\\' -> builder.append('\\');
                        default -> builder.append(valueChar);
                    }
                    escaped = false;
                    continue;
                }
                if (quote == '"' && valueChar == '\\') {
                    escaped = true;
                    continue;
                }
                if (valueChar == quote) {
                    break;
                }
                builder.append(valueChar);
            }
            values.add(builder.toString());
        }
        return values.stream()
                .map(String::trim)
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
    }

    private String removeAllowedUsersProperty(String body) {
        PropertySpan propertySpan = findArrayProperty(body, "allowed_users");
        if (propertySpan == null) {
            return body;
        }
        return body.substring(0, propertySpan.start()) + body.substring(propertySpan.end());
    }

    private List<String> normalizeStringList(List<String> requestedValues, List<String> existingValues) {
        if (requestedValues == null) {
            return existingValues == null ? List.of() : existingValues;
        }
        return requestedValues.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .toList();
    }

    private String resolveRequiredString(String requestedValue, String existingValue, String fieldName) {
        String normalized = firstNonBlank(requestedValue, existingValue);
        if (!StringUtils.hasText(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, fieldName + " is required");
        }
        return normalized.trim();
    }

    private String resolveRequiredSecret(String requestedValue, String existingValue, String fieldName) {
        String resolved = resolveSecret(requestedValue, existingValue);
        if (!StringUtils.hasText(resolved)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, fieldName + " is required");
        }
        return resolved.trim();
    }

    private String resolveSecret(String requestedValue, String existingValue) {
        String normalizedRequested = trimToNull(requestedValue);
        if (normalizedRequested == null) {
            return trimToNull(existingValue);
        }
        if (MASKED_SECRET.equals(normalizedRequested)) {
            return trimToNull(existingValue);
        }
        return normalizedRequested;
    }

    private String firstNonBlank(String preferredValue, String fallbackValue) {
        String normalizedPreferred = trimToNull(preferredValue);
        if (normalizedPreferred != null) {
            return normalizedPreferred;
        }
        return trimToNull(fallbackValue);
    }

    private long normalizeMessageTimeout(Long value) {
        if (value == null || value <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "messageTimeoutSecs must be positive");
        }
        return value;
    }

    private String cleanupExtras(String value) {
        String normalized = normalizeToml(value).strip();
        return normalized;
    }

    private void appendExtras(StringBuilder builder, String extras) {
        if (!StringUtils.hasText(extras)) {
            return;
        }
        builder.append('\n').append(extras.strip()).append('\n');
    }

    private String renderTomlStringArray(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "[]";
        }
        return "[" + values.stream().map(this::renderTomlString).reduce((left, right) -> left + ", " + right).orElse("") + "]";
    }

    private String renderTomlString(String value) {
        return "\"" + escapeTomlString(value == null ? "" : value) + "\"";
    }

    private String maskSecret(String value) {
        return StringUtils.hasText(value) ? MASKED_SECRET : "";
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private String trimToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }

    private String normalizeToml(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\r\n", "\n");
    }

    private String escapeTomlString(String value) {
        StringBuilder builder = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            switch (ch) {
                case '\\' -> builder.append("\\\\");
                case '"' -> builder.append("\\\"");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> builder.append(ch);
            }
        }
        return builder.toString();
    }

    private String unescapeTomlString(String value) {
        StringBuilder builder = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            if (ch != '\\' || index + 1 >= value.length()) {
                builder.append(ch);
                continue;
            }
            char next = value.charAt(++index);
            switch (next) {
                case 'n' -> builder.append('\n');
                case 'r' -> builder.append('\r');
                case 't' -> builder.append('\t');
                case '"' -> builder.append('"');
                case '\\' -> builder.append('\\');
                default -> builder.append(next);
            }
        }
        return builder.toString();
    }

    private record ParsedChannelsConfig(
            ParsedDocument document,
            ParsedRootSection root,
            ParsedDingTalkSection dingtalk,
            ParsedQqSection qq,
            ParsedWeComSection wecom,
            List<SectionBlock> preservedGroupSections
    ) {
    }

    private record ParsedDocument(
            String preamble,
            List<SectionBlock> sections
    ) {
    }

    private record SectionBlock(
            String headerName,
            String rawText
    ) {
    }

    private record HeaderMatch(
            String name,
            int start
    ) {
    }

    private record ParsedRootSection(
            boolean cliEnabled,
            long messageTimeoutSecs,
            String extras
    ) {
    }

    private record ParsedDingTalkSection(
            boolean enabled,
            String clientId,
            String clientSecret,
            List<String> allowedUsers,
            String extras
    ) {
    }

    private record ParsedQqSection(
            boolean enabled,
            String appId,
            String appSecret,
            List<String> allowedUsers,
            String extras
    ) {
    }

    private record ParsedWeComSection(
            boolean enabled,
            String corpId,
            String agentId,
            String secret,
            String token,
            String encodingAesKey,
            List<String> allowedUsers,
            String extras
    ) {
    }

    private record RootSectionState(
            boolean cliEnabled,
            long messageTimeoutSecs,
            String extras
    ) {
    }

    private record ChannelSectionState(
            boolean enabled,
            String idValue,
            String secretValue,
            List<String> allowedUsers,
            String extras,
            String headerName
    ) {
    }

    private record WeComSectionState(
            boolean enabled,
            String corpId,
            String agentId,
            String secret,
            String token,
            String encodingAesKey,
            List<String> allowedUsers,
            String extras
    ) {
    }

    private record PropertySpan(
            String rawValue,
            int start,
            int end
    ) {
    }
}
