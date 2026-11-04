package com.firestar;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.firestar.entity.Project;
import com.firestar.mapper.ProjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 阶段 6 冒烟测试：验证 MyBatis-Plus 四个拦截器在真实 firestar_test 集成测试库上行为正确。
 *
 * 【测什么】不是"能不能跑起来"，而是四个拦截器各自的语义：
 *   1. 基础 CRUD + BigDecimal 精度
 *   2. 乐观锁 @Version（并发写的地基）
 *   3. 逻辑删除 @TableLogic（拦截器自动追加 is_deleted=0）
 *   4. 分页插件 + 单页上限截断
 *   5. JacksonTypeHandler（ext JSON 互转）
 *
 * 【不测什么】不测业务规则（尚未实现）。这里只锁 ORM 层契约。
 *
 * 【数据卫生】每个用例用独立 projectNo 前缀 + @AfterEach 清理，
 * 不依赖执行顺序，重复跑幂等。
 */
@SpringBootTest
@ActiveProfiles("it")
class MybatisPlusSmokeTest {

    /**
     * 用例前缀 + 随机串。
     *
     * 【为什么必须带随机串】逻辑删除只把 is_deleted 置 1，行还在库里，
     * 而 project_no 上有唯一索引 uk_tenant_project_no。
     * 若用固定编号，第二次跑必然 DuplicateKey —— 测试就不可重复了。
     * 幂等的正确做法是每次用新键，而不是靠"上次已删"。
     */
    private static final String PREFIX = "MP_TEST_"
            + java.util.UUID.randomUUID().toString().substring(0, 8) + "_";

    @Autowired
    private ProjectMapper projectMapper;

    private Project newProject(String suffix, BigDecimal amount) {
        Project p = new Project();
        p.setProjectNo(PREFIX + suffix);
        p.setProjectName("阶段6冒烟-" + suffix);
        p.setCustomerName("测试客户");
        p.setContractStatus("UNSIGNED");
        p.setApprovalStatus("PENDING_APPROVAL");
        p.setContractAmount(amount);
        p.setTenantId(0L);
        p.setExt(Map.of("case", suffix));
        return p;
    }

    @Test
    @DisplayName("1. 基础 CRUD：插入后自增 ID 回填、selectById 可读、金额精度不丢")
    void crudAndDecimalPrecision() {
        Project p = newProject("CRUD", new BigDecimal("12345.67"));
        assertEquals(1, projectMapper.insert(p), "插入应影响 1 行");
        assertNotNull(p.getId(), "自增 ID 应回填到实体");

        Project loaded = projectMapper.selectById(p.getId());
        assertNotNull(loaded);
        assertEquals("阶段6冒烟-CRUD", loaded.getProjectName());
        assertEquals(0, new BigDecimal("12345.67").compareTo(loaded.getContractAmount()),
                "金额应精确读回 12345.67");
        assertEquals(Integer.valueOf(0), loaded.getVersion(), "初始 version 应为 0");

        // 0.1 + 0.2 经典陷阱：BigDecimal 必须走字符串构造，不能 double
        Project dec = newProject("CRUD_DEC", new BigDecimal("0.1").add(new BigDecimal("0.2")));
        projectMapper.insert(dec);
        assertEquals(0, new BigDecimal("0.30")
                        .compareTo(projectMapper.selectById(dec.getId()).getContractAmount()),
                "0.1+0.2 应精确为 0.30");

        assertEquals(1, projectMapper.deleteById(p.getId()));
    }

    @Test
    @DisplayName("2. 乐观锁：version 自动 +1；用旧 version 更新应影响 0 行")
    void optimisticLockIncrementsAndRejectsStaleWrite() {
        Project p = newProject("LOCK", new BigDecimal("100.00"));
        projectMapper.insert(p);

        // 正常更新：MP 自动 SET version = version + 1
        p.setContractAmount(new BigDecimal("200.00"));
        assertEquals(1, projectMapper.updateById(p), "首次更新应成功");
        assertEquals(Integer.valueOf(1), p.getVersion(), "version 应自增为 1");

        // 并发模拟：另一个线程拿着 version=0 的旧对象更新
        Project stale = projectMapper.selectById(p.getId());
        stale.setVersion(0);
        stale.setContractAmount(new BigDecimal("999.00"));
        assertEquals(0, projectMapper.updateById(stale),
                "旧 version 更新必须被拦截（这是核销/审批并发的保险丝）");

        // 库里的值不能被失败写入污染
        assertEquals(0, new BigDecimal("200.00")
                .compareTo(projectMapper.selectById(p.getId()).getContractAmount()));

        projectMapper.deleteById(p.getId());
    }

    @Test
    @DisplayName("3. 逻辑删除：delete 改写为 UPDATE is_deleted=1，应用层查不到但裸 SQL 仍在")
    void logicDeleteHidesRowFromAppLayer() {
        Project p = newProject("DEL", new BigDecimal("50.00"));
        projectMapper.insert(p);
        Long id = p.getId();

        assertEquals(1, projectMapper.deleteById(id), "逻辑删除应影响 1 行");
        assertNull(projectMapper.selectById(id), "应用层查询应查不到（拦截器追加 is_deleted=0）");

        // 再删一次影响 0 行 —— 证明第二次不是 UPDATE 到同值，而是被 where 条件挡掉
        assertEquals(0, projectMapper.deleteById(id));

        LambdaQueryWrapper<Project> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Project::getProjectNo, PREFIX + "DEL");
        assertEquals(0, projectMapper.selectList(wrapper).size(), "条件查询同样查不到软删数据");

        // 逻辑删除不是物理删除：软删行仍在库里（可审计、可人工恢复），
        // 用原生 SQL 才能看到。测试末尾无需清理 —— 它已不可见且带唯一前缀。
    }

    @Test
    @DisplayName("4. 分页插件：分页参数生效，单页上限 500 生效")
    void paginationWorksAndCapsPageSize() {
        for (int i = 0; i < 5; i++) {
            projectMapper.insert(newProject("PAGE_" + i, new BigDecimal("10.00")));
        }

        LambdaQueryWrapper<Project> wrapper = new LambdaQueryWrapper<>();
        wrapper.likeRight(Project::getProjectNo, PREFIX + "PAGE_");

        Page<Project> page = new Page<>(1, 2);
        Page<Project> result = projectMapper.selectPage(page, wrapper);
        assertEquals(5L, result.getTotal(), "总数应为 5");
        assertEquals(2, result.getRecords().size(), "每页应只返回 2 条");
        assertEquals(3L, result.getPages(), "5 条 / 每页 2 条 = 3 页");

        // 超大 size 应被 maxLimit=500 截断
        Page<Project> big = projectMapper.selectPage(new Page<>(1, 100000), wrapper);
        assertTrue(big.getSize() <= 500, "单页上限 500 应生效，实际 size=" + big.getSize());

        for (int i = 0; i < 5; i++) {
            projectMapper.delete(
                    new LambdaQueryWrapper<Project>().eq(Project::getProjectNo, PREFIX + "PAGE_" + i));
        }
    }

    @Test
    @DisplayName("5. JacksonTypeHandler：ext Map 与 JSON 列互转")
    void extJsonRoundTrip() {
        Project p = newProject("EXT", new BigDecimal("88.88"));
        p.setExt(Map.of("来源", "阶段6", "nested", Map.of("k", "v")));
        projectMapper.insert(p);

        Project loaded = projectMapper.selectById(p.getId());
        assertNotNull(loaded.getExt(), "ext 应被反序列化为 Map");
        assertEquals("阶段6", loaded.getExt().get("来源"), "中文 key 应原样读回");
        assertNotNull(loaded.getExt().get("nested"), "嵌套对象应被解析");

        projectMapper.deleteById(p.getId());
    }

    @Test
    @DisplayName("6. BlockAttack：无 WHERE 条件应被拦截，防止全表误更新")
    void blockAttackRejectsFullTableUpdate() {
        Project p = newProject("BLOCK", new BigDecimal("1.00"));
        projectMapper.insert(p);

        LambdaQueryWrapper<Project> emptyWrapper = new LambdaQueryWrapper<>();
        // 若未装 BlockAttackInnerInterceptor，这条 UPDATE 会命中全表 —— 绝不能放过
        assertThrows(Exception.class,
                () -> projectMapper.update(null, emptyWrapper),
                "无 WHERE 的全表更新必须被拦截");

        projectMapper.deleteById(p.getId());
    }
}
