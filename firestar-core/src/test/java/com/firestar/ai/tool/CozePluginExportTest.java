package com.firestar.ai.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.firestar.ai.spi.ToolSpec;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 工具定义导出器：把 ToolRegistry 真实 spec 导出到 target/coze-tool-specs.json，
 * 供 docs/coze/gen_plugin_yaml.py 生成扣子端插件 plugin.yaml。直接取代码里的 schema，
 * 避免手工抄写出错。新增/改动工具后重跑本测试 + 生成脚本即可同步扣子插件定义。
 *
 * <p>本测试只写一个 target/ 下的中间文件，不修改源码、不依赖 DB/网络。</p>
 */
class CozePluginExportTest {

    @Test
    void exportToolSpecs() throws Exception {
        ObjectMapper om = new ObjectMapper();
        ArrayNode arr = om.createArrayNode();
        for (ToolSpec s : new ToolRegistry().all()) {
            ObjectNode o = om.createObjectNode();
            o.put("name", s.name());
            o.put("description", s.description());
            o.put("inputSchema", s.parametersJson() == null ? "{}" : s.parametersJson());
            o.put("requiredPerm", s.requiredPerm());
            arr.add(o);
        }
        Path out = Path.of("target", "coze-tool-specs.json");
        Files.writeString(out, om.writerWithDefaultPrettyPrinter().writeValueAsString(arr), StandardCharsets.UTF_8);
        System.out.println("[EXPORT] " + arr.size() + " tools -> " + out.toAbsolutePath());
    }
}
