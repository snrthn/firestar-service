package com.firestar.ai.controller;

import com.firestar.ai.entity.AiConversation;
import com.firestar.ai.mapper.AiConversationMapper;
import com.firestar.ai.mapper.AiMessageMapper;
import com.firestar.ai.mapper.AiToolCallMapper;
import com.firestar.ai.service.AiChatService;
import com.firestar.entity.User;
import com.firestar.exception.GlobalExceptionHandler;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.firestar.mapper.UserMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 端点级安全契约：AI 草稿越权访问返回 HTTP 404。
 *
 * <p>用 standaloneSetup 拉起真实 Controller + 真实 Service（仅 Mapper 打桩），
 * 不启动 Spring 上下文、不连数据库。{@code @RequirePerm} 属 AOP 层，
 * standalone 不加载，故此处只验证「已通过权限校验后，越权数据仍被归属闸拦成 404」。</p>
 */
@ExtendWith(MockitoExtension.class)
class AiDraftEndpointSecurityTest {

    @Mock
    private AiConversationMapper convMapper;
    @Mock
    private AiMessageMapper msgMapper;
    @Mock
    private AiToolCallMapper toolCallMapper;
    @Mock
    private UserMapper userMapper;

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        // standalone 未启动 Spring 上下文，LambdaQueryWrapper<User> 解析 lambda 需要 TableInfo 缓存，
        // 否则抛 MybatisPlusException「can not find lambda cache」被兜底 handler 吞成 200。
        MybatisConfiguration cfg = new MybatisConfiguration();
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(cfg, ""), User.class);
    }

    @BeforeEach
    void setUp() {
        // standalone 不加载 JWT filter，需显式模拟已登录身份：
        // DataScopeHelper.currentUserId() 内部会读 SecurityContextHolder，认证为空会抛异常被兜底成 200。
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("staff", null,
                        List.of(new SimpleGrantedAuthority("ROLE_STAFF"))));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void latestDraft_otherUsersConversation_returns404() throws Exception {
        // 真实编排服务
        AiChatService chatService = new AiChatService(convMapper, msgMapper, toolCallMapper);
        AiConversationController controller = new AiConversationController(chatService, userMapper);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        // 会话属于用户 1；当前登录用户是 2 → 越权
        AiConversation other = new AiConversation();
        other.setId(10L);
        other.setUserId(1L);
        when(convMapper.selectById(10L)).thenReturn(other);

        User me = new User();
        me.setId(2L);
        me.setUsername("staff");
        when(userMapper.selectOne(any())).thenReturn(me);

        mvc.perform(get("/ai/conversation/10/latest-draft"))
                .andExpect(status().isNotFound());
    }
}
