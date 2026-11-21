package com.firestar.common;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 主数据编号生成（客户 / 货物），与 ProjectServiceImpl 的编号规则严格一致：
 *   客户 CUS-{yyyyMMdd}-{id 四位补齐}
 *   货物 GOODS-{yyyyMMdd}-{id 四位补齐}
 *
 * <p>抽到公共类供「立项自动建档」与「导入建档」共用，避免两处规则漂移
 * （与 V68/V71 回填数据的编号格式也必须一致）。</p>
 */
public final class MasterDataNoUtil {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private MasterDataNoUtil() {
    }

    public static String customerNo(Long id) {
        return String.format("CUS-%s-%04d", LocalDate.now().format(DATE_FMT), id);
    }

    public static String goodsNo(Long id) {
        return String.format("GOODS-%s-%04d", LocalDate.now().format(DATE_FMT), id);
    }
}
