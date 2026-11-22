package com.firestar.ai.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 扣子 answer 首句过程独白过滤器：守护「删掉的只是自述动作，正常回答一字不动」。
 */
class AnswerPreambleFilterTest {

    private static String filter(String... chunks) {
        List<String> out = new ArrayList<>();
        AnswerPreambleFilter f = new AnswerPreambleFilter(out::add);
        for (String c : chunks) {
            f.accept(c);
        }
        f.finish();
        return String.join("", out);
    }

    @Test
    @DisplayName("真实独白（整句一帧）被删除，正式回答保留")
    void realPreambleDropped() {
        String out = filter(
                "用户现在需要查询研发中心下面的部门数量，我将直接调用组织架构查询工具完成本次查询。",
                "研发中心下属共有2个部门，分别是：1.产品部 2.技术部");
        assertEquals("研发中心下属共有2个部门，分别是：1.产品部 2.技术部", out);
    }

    @Test
    @DisplayName("独白跨帧拆开仍整句删除（首帧未到句末不发射）")
    void preambleAcrossFramesDropped() {
        String out = filter(
                "用户现在要查研发中心下面的部门数量，",
                "本次查询将调用对应组织架构查询工具，",
                "无额外参数要求。",
                "答案是2个部门。");
        assertEquals("答案是2个部门。", out);
    }

    @Test
    @DisplayName("正常回答首句不含过程信号词，一字不动")
    void normalAnswerUntouched() {
        String answer = "研发中心共有2个下属部门：产品部和技术部。负责人是杨虎宁。";
        assertEquals(answer, filter(answer));
    }

    @Test
    @DisplayName("用户问『你调用了什么工具』这类正常回答不被误删")
    void questionAboutToolNotMistaken() {
        String answer = "我调用了组织架构查询工具，因为需要核对部门数据。";
        // 以「我」开头 + 含调用 + 含工具，但没有过程信号词组合（"因为需要"非元话语）
        assertEquals(answer, filter(answer));
    }

    @Test
    @DisplayName("无句末标点的正常短回复在 finish 时原样吐出，不被憋住")
    void shortAnswerNoPunctuationFlushed() {
        assertEquals("好的，共2个部门", filter("好的，", "共2个部门"));
    }

    @Test
    @DisplayName("超长无句末标点按正常正文放行，不误删")
    void longNoPunctuationPassesThrough() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            sb.append("这是一段很长的正常正文内容");
        }
        assertEquals(sb.toString(), filter(sb.toString()));
    }
}
