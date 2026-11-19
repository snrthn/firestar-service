package com.firestar.ai.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DsmlTokenFilterTest {

    private static String filter(String... chunks) {
        List<String> out = new ArrayList<>();
        DsmlTokenFilter f = new DsmlTokenFilter(out::add);
        for (String c : chunks) f.accept(c);
        f.finish();
        return String.join("", out);
    }

    @Test
    void plainText_passesThroughUnchanged() {
        assertEquals("你好，这是普通回复。\n第二行 带空格", filter("你好，这是普通回复。\n", "第二行 带空格"));
    }

    @Test
    void dsmlBlock_inOneChunk_isStripped() {
        String dsml = "<|DSML|> calls>\n<|DSML|> invoke name=\"search_knowledge\">\n"
                + "<|DSML|> parameter name=\"query\" string=\"true\">源码</|DSML|> parameter>\n"
                + "</|DSML|> invoke>\n</|DSML|> calls>\n";
        assertEquals("我先查一下。\n回答正文。", filter("我先查一下。\n" + dsml + "回答正文。"));
    }

    @Test
    void dsmlMarkersSplitAcrossFrames_areStripped() {
        // 标记被拆成多个 token：单个逐帧正则无法识别，必须缓冲
        String[] chunks = {"查询中<|DS", "ML|> calls>\n<|DSML|> invoke name=\"search_knowledge\">\n"
                + "<|DSML|> parameter name=\"query\" string=\"true\">来源</|DSML|> parameter>\n"
                + "</|DSML|> invoke>\n</|DS", "ML|> calls>\n", "查到的结果如下。"};
        assertEquals("查询中\n查到的结果如下。", filter(chunks));
    }

    @Test
    void unclosedDsmlBlock_isDiscardedAndFollowingTextKept() {
        assertEquals("有效回答。", filter("有效回答。<|DSML|> calls>\n半截协议文本"));
    }

    @Test
    void textEndingWithMarkerPrefix_isFlushedAtFinish() {
        // 末尾恰好是标记前缀（如 "<|D"），不能一直吞着不发
        assertEquals("正常结尾 <|D", filter("正常结尾 <|D"));
        assertEquals("正常结尾 <|", filter("正常结尾 <|"));
    }

    @Test
    void multipleBlocksAndCaseInsensitiveMarkers_areStripped() {
        assertEquals("A。\nB。\nC。", filter("A。<|dsml|> invoke x></|dsml|>\nB。"
                + "<|DSML|> calls> y </|DSML|>\nC。"));
    }
}
