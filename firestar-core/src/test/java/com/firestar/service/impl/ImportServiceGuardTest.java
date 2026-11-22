package com.firestar.service.impl;

import com.firestar.entity.ImportBatch;
import com.firestar.entity.FileStorage;
import com.firestar.exception.BizException;
import com.firestar.mapper.FileStorageMapper;
import com.firestar.mapper.ImportBatchMapper;
import com.firestar.mapper.UserMapper;
import com.firestar.service.FileStorageService;
import com.firestar.service.ImportHandler;
import com.firestar.service.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.mockito.Mockito.lenient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 历史数据导入的轻量契约回归（P1 测试补齐）。
 *
 * <p>{@code ImportServiceImpl} 此前零测试。submit 的异步写入链路依赖线程池与事务模板，
 * 放 it 集成测试覆盖；本类锁定三个同步守卫：handler 路由、类型清单排序、
 * 归档只允许终态批次——这三条是前端下拉框与「归档」按钮正确性的根基。</p>
 */
@ExtendWith(MockitoExtension.class)
class ImportServiceGuardTest {

    @Mock private ImportBatchMapper batchMapper;
    @Mock private FileStorageMapper fileStorageMapper;
    @Mock private FileStorageService storage;
    @Mock private UserMapper userMapper;
    @Mock private NotificationService notificationService;
    @Mock private PlatformTransactionManager txManager;
    @Mock private ImportHandler goodsHandler;
    @Mock private ImportHandler customerHandler;

    private ImportServiceImpl service;

    @BeforeEach
    void setUp() {
        when(goodsHandler.key()).thenReturn("goods");
        lenient().when(goodsHandler.displayName()).thenReturn("货物导入");
        when(customerHandler.key()).thenReturn("customer");
        lenient().when(customerHandler.displayName()).thenReturn("客户导入");
        service = new ImportServiceImpl(batchMapper, fileStorageMapper, storage, userMapper,
                notificationService, List.of(goodsHandler, customerHandler), Runnable::run, txManager);
    }

    @Test
    @DisplayName("未知导入类型必须抛业务异常，不允许静默落空批次")
    void requireHandler_unknownKey_rejected() {
        BizException ex = assertThrows(BizException.class, () -> service.requireHandler("unknown"));
        assertTrue(ex.getMessage().contains("未知的导入类型"));
    }

    @Test
    @DisplayName("已注册类型可正确路由到对应 handler")
    void requireHandler_knownKey_returnsHandler() {
        assertSame(goodsHandler, service.requireHandler("goods"));
        assertSame(customerHandler, service.requireHandler("customer"));
    }

    @Test
    @DisplayName("类型清单按 key 排序，供前端下拉稳定展示")
    void listKeys_sortedByKey() {
        List<Map<String, Object>> keys = service.listKeys();
        assertEquals(2, keys.size());
        assertEquals("customer", keys.get(0).get("key"));
        assertEquals("客户导入", keys.get(0).get("displayName"));
        assertEquals("goods", keys.get(1).get("key"));
    }

    @Test
    @DisplayName("归档守卫：非终态批次不得归档（防止导入中数据被冻结）")
    void archive_nonTerminal_rejected() {
        ImportBatch b = new ImportBatch();
        b.setId(1L);
        b.setStatus("RUNNING");
        when(batchMapper.selectById(1L)).thenReturn(b);

        BizException ex = assertThrows(BizException.class, () -> service.archive(1L, 9L));
        assertTrue(ex.getMessage().contains("只有已完成的批次才能归档"));
        verify(batchMapper, never()).updateById(any(ImportBatch.class));
    }

    @Test
    @DisplayName("归档终态批次：状态置 ARCHIVED 并记录操作人")
    void archive_terminalBatch_ok() {
        ImportBatch b = new ImportBatch();
        b.setId(2L);
        b.setStatus("SUCCESS");
        when(batchMapper.selectById(2L)).thenReturn(b);

        service.archive(2L, 9L);

        verify(batchMapper).updateById(argThat((ImportBatch u) ->
                u.getId().equals(2L) && "ARCHIVED".equals(u.getStatus()) && u.getUpdatedBy().equals(9L)));
    }
}
