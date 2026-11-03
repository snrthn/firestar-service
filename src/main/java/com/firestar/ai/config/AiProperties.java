package com.firestar.ai.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * AI 助手配置绑定（前缀 ai.*，见 application*.properties）。
 *
 * <p>供应商拓扑（base-url / model / 凭据）全部入库加密，由【模型配置】页维护（运行期唯一真源）；
 * 密钥（bot_id / api_key）入库即加密，不进 git、不进前端。
 * KEK 由环境变量 AI_KEK 注入：缺失或占位符未解析即启动失败（沿用 jwt.secret 的「宁可起不来也不带病上线」纪律）。</p>
 */
@Component
@ConfigurationProperties(prefix = "ai")
public class AiProperties {

    private static final Logger log = LoggerFactory.getLogger(AiProperties.class);

    /**
     * 对话系统指令（工具调用纪律）的唯一真源，供应商无关。
     * 由 FireStar 在每轮对话开头注入为 system 消息，所有标准供应商（OpenAI/DeepSeek 系）原样遵守；
     * 扣子(Coze)因 Bot 提示词在控制台、chat API 不接受外部 system 覆盖，改为以「用户角色前导指令」注入（尽力而为）。
     * 切供应商不掉行为：策略在 FireStar 一侧定义一次，不再是各供应商控制台各写一份。
     */
    public static final String DEFAULT_CHAT_SYSTEM_INSTRUCTION = "你是 FireStar 的 AI 助手，请严格遵守以下工具调用纪律："
            + "1. 仅当用户明确提出业务操作意图时才调用工具；业务意图包括查询/搜索项目、发货单、退货单、应收单，生成发货单草稿，查看经营看板，查询我的待办等。"
            + "2. 以下情况一律用纯文本回复、禁止调用任何工具：打招呼（你好/在吗/hi 等）、闲聊、寒暄、无明确任务的开放式提问、用户仅在测试连通性或确认你能对话。"
            + "3. 判断标准：用户的话里是否包含具体的单据/项目/数据指向（如要查/要做哪张单、哪个项目、哪笔数据）；没有则纯文本，有则调用对应工具。"
            + "4. 不确定用户意图时，先用纯文本追问澄清，不要直接调用工具。";

    private String kek;
    private String egressMode = "strict";
    private String chatSystemInstruction = DEFAULT_CHAT_SYSTEM_INSTRUCTION;
    private List<EgressRule> egressRules = new ArrayList<>();

    /**
     * 启动期配置自检（非致命）。
     * AI 助手是可插拔模块：生效供应商以 DB【模型配置】is_active 行为唯一真源，密钥（KEK）来自环境变量 AI_KEK。
     * 仅以 warn 记录，AI 模块降级为不可用；待【模型配置】页配置了 is_active 行后，
     * ChatProviderManager 会从数据库动态加载并使 AI 功能自动生效。
     */
    @PostConstruct
    void validate() {
        // DB（sys_model_config.is_active=1）为当前生效供应商的唯一真源，不再依赖 env 默认供应商。
        boolean unresolved = kek != null && kek.contains("${");
        if (kek == null || kek.isBlank() || unresolved) {
            log.warn("[AI] ai.kek 未配置：依赖密钥的 AI 加解密功能暂不可用."
                    + "建议通过环境变量 AI_KEK 注入强随机密钥（SM4 KEK，openssl rand -base64 32）。");
        }
    }

    public String getKek() {
        return kek;
    }

    public void setKek(String kek) {
        this.kek = kek;
    }

    public String getEgressMode() {
        return egressMode;
    }

    public void setEgressMode(String egressMode) {
        this.egressMode = egressMode;
    }

    public String getChatSystemInstruction() {
        return chatSystemInstruction;
    }

    public void setChatSystemInstruction(String chatSystemInstruction) {
        this.chatSystemInstruction = chatSystemInstruction;
    }

    public List<EgressRule> getEgressRules() {
        return egressRules;
    }

    public void setEgressRules(List<EgressRule> egressRules) {
        this.egressRules = egressRules;
    }

    public static class EgressRule {
        private String field;
        private String action;

        public String getField() {
            return field;
        }

        public void setField(String field) {
            this.field = field;
        }

        public String getAction() {
            return action;
        }

        public void setAction(String action) {
            this.action = action;
        }
    }
}
