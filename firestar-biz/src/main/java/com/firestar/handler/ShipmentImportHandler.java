package com.firestar.handler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.firestar.dto.ImportColumnDef;
import com.firestar.entity.Goods;
import com.firestar.entity.Item;
import com.firestar.entity.Project;
import com.firestar.entity.Shipment;
import com.firestar.entity.ShipmentLine;
import com.firestar.mapper.ItemMapper;
import com.firestar.mapper.ProjectMapper;
import com.firestar.mapper.ShipmentLineMapper;
import com.firestar.mapper.ShipmentMapper;
import com.firestar.service.GoodsService;
import com.firestar.service.ImportHandler;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 期初发货导入 Handler（P1，方案 v3 §5）。
 *
 * <p>只导在途发货单（默认 SHIPPED），直接落 fin_shipment + fin_shipment_line，
 * 不拉 Flowable、不生成应收（避免与期初应收双来源），receivable_id 留 NULL。
 * 明细格式：品名|数量|单价;品名|数量|单价。明细品名在项目下无 Item 时自动建档，
 * item.shipped_qty 直接覆盖为发货数量（不走乐观锁累加）。运单号查重。</p>
 */
@Component
public class ShipmentImportHandler implements ImportHandler {

    private static final String F_PROJECT = "projectName";
    private static final String F_BATCH = "batchNo";
    private static final String F_DATE = "shipmentDate";
    private static final String F_CARRIER = "carrier";
    private static final String F_TRACKING = "trackingNo";
    private static final String F_STATUS = "shipmentStatus";
    private static final String F_LINES = "lines";

    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ShipmentMapper shipmentMapper;
    private final ShipmentLineMapper shipmentLineMapper;
    private final ProjectMapper projectMapper;
    private final ItemMapper itemMapper;
    private final GoodsService goodsService;

    public ShipmentImportHandler(ShipmentMapper shipmentMapper, ShipmentLineMapper shipmentLineMapper,
                                 ProjectMapper projectMapper, ItemMapper itemMapper, GoodsService goodsService) {
        this.shipmentMapper = shipmentMapper;
        this.shipmentLineMapper = shipmentLineMapper;
        this.projectMapper = projectMapper;
        this.itemMapper = itemMapper;
        this.goodsService = goodsService;
    }

    @Override
    public String key() {
        return "shipment_opening";
    }

    @Override
    public String displayName() {
        return "期初发货";
    }

    @Override
    public String sheetName() {
        return "发货";
    }

    @Override
    public List<ImportColumnDef> columns() {
        return List.of(
                ImportColumnDef.required("项目名称", F_PROJECT, "必填，须已存在", "2025年度设备采购"),
                ImportColumnDef.of("发货批次号", F_BATCH, "选填，留空自动生成 FH-项目号-###", "FH-PJ-20250101-0001-001"),
                ImportColumnDef.required("发货日期", F_DATE, "必填，格式 yyyy-MM-dd HH:mm:ss", "2025-02-10 09:00:00"),
                ImportColumnDef.of("承运商", F_CARRIER, "选填", "顺丰速运"),
                ImportColumnDef.of("运单号", F_TRACKING, "选填但不可重复", "SF1234567890"),
                ImportColumnDef.of("发货状态", F_STATUS, "SHIPPED / PARTIAL_RECEIVED，留空默认 SHIPPED（在途）", "SHIPPED"),
                ImportColumnDef.required("明细", F_LINES, "必填，格式：品名|数量|单价;品名|数量|单价", "挖掘机配件|5|1000.00;机油|10|80.00")
        );
    }

    @Override
    public String notes() {
        return "1. 带 * 为必填项；2. 单次最多 2000 行；3. 只导在途发货（默认 SHIPPED，已收货单不要导）；"
                + "4. 明细格式：品名|数量|单价，多条用英文分号 ; 分隔；数量为正整数、单价为数字；"
                + "5. 明细品名在项目下不存在时会自动建档；6. 期初发货不生成应收单，请单独导期初应收；"
                + "7. 运单号不可与历史重复。";
    }

    @Override
    public ValidateResult validate(List<Map<String, String>> rows, Long operatorId) {
        List<RowError> errors = new ArrayList<>();
        Set<String> batches = new HashSet<>();
        Set<String> trackings = new HashSet<>();
        for (int i = 0; i < rows.size(); i++) {
            Map<String, String> row = rows.get(i);
            int rowNo = i + 1;
            StringBuilder err = new StringBuilder();
            String project = val(row, F_PROJECT);
            if (project.isEmpty()) {
                err.append("项目名称必填；");
            } else if (projectMapper.selectCount(new LambdaQueryWrapper<Project>()
                    .eq(Project::getProjectName, project)) == 0) {
                err.append("项目【" + project + "】不存在；");
            }
            String date = val(row, F_DATE);
            if (date.isEmpty()) {
                err.append("发货日期必填；");
            } else {
                try { LocalDateTime.parse(date, DTF); } catch (DateTimeParseException e) {
                    err.append("发货日期格式应为 yyyy-MM-dd HH:mm:ss；");
                }
            }
            String status = val(row, F_STATUS);
            if (!status.isEmpty() && !"SHIPPED".equals(status) && !"PARTIAL_RECEIVED".equals(status)) {
                err.append("发货状态只能是 SHIPPED/PARTIAL_RECEIVED（只导在途）；");
            }
            String batch = val(row, F_BATCH);
            if (!batch.isEmpty()) {
                if (!batches.add(batch)) {
                    err.append("批内批次号重复；");
                } else if (shipmentMapper.selectCount(new LambdaQueryWrapper<Shipment>()
                        .eq(Shipment::getBatchNo, batch)) > 0) {
                    err.append("批次号已存在；");
                }
            }
            String tracking = val(row, F_TRACKING);
            if (!tracking.isEmpty()) {
                if (!trackings.add(tracking)) {
                    err.append("批内运单号重复；");
                } else if (shipmentMapper.selectCount(new LambdaQueryWrapper<Shipment>()
                        .eq(Shipment::getTrackingNo, tracking)) > 0) {
                    err.append("运单号已存在；");
                }
            }
            // 明细格式校验
            List<String[]> lines = parseLines(val(row, F_LINES), err);
            if (lines.isEmpty() && !val(row, F_LINES).isEmpty()) {
                // parseLines 已追加错误
            } else if (lines.isEmpty()) {
                err.append("明细必填；");
            }
            if (err.length() > 0) errors.add(new RowError(rowNo, err.toString()));
        }
        return errors.isEmpty() ? ValidateResult.pass() : ValidateResult.fail(errors);
    }

    @Override
    public int commit(List<Map<String, String>> rows, Long operatorId, String batchNo) {
        int n = 0;
        for (Map<String, String> row : rows) {
            Project project = projectMapper.selectOne(new LambdaQueryWrapper<Project>()
                    .eq(Project::getProjectName, val(row, F_PROJECT)).last("LIMIT 1"));
            String batch = val(row, F_BATCH);
            if (batch.isEmpty()) {
                long count = shipmentMapper.selectCount(new LambdaQueryWrapper<Shipment>()
                        .eq(Shipment::getProjectId, project.getId()));
                String no = project.getProjectNo() == null || project.getProjectNo().isBlank()
                        ? "NA" : project.getProjectNo();
                batch = String.format("FH-%s-%03d", no, count + 1);
            }
            List<String[]> lines = parseLines(val(row, F_LINES), new StringBuilder());
            BigDecimal totalAmount = BigDecimal.ZERO;
            for (String[] parts : lines) {
                BigDecimal price = new BigDecimal(parts[2]);
                totalAmount = totalAmount.add(price.multiply(BigDecimal.valueOf(Integer.parseInt(parts[1]))));
            }

            Shipment s = new Shipment();
            s.setProjectId(project.getId());
            s.setBatchNo(batch);
            s.setShipmentDate(LocalDateTime.parse(val(row, F_DATE), DTF));
            s.setShipmentAmount(totalAmount);
            s.setReceivedAmount(BigDecimal.ZERO);
            s.setReturnedAmount(BigDecimal.ZERO);
            String status = val(row, F_STATUS);
            s.setShipmentStatus(status.isEmpty() ? "SHIPPED" : status);
            s.setApprovalStatus("APPROVED");
            s.setApplicantId(operatorId);
            s.setShippedById(operatorId);
            s.setTrackingNo(val(row, F_TRACKING).isEmpty() ? null : val(row, F_TRACKING));
            s.setCarrier(val(row, F_CARRIER));
            s.setReceivableId(null); // 期初不生成应收，避免双来源
            s.setCreatedBy(operatorId);
            s.setExt(UserImportHandler.importExt(batchNo));
            shipmentMapper.insert(s);

            for (String[] parts : lines) {
                String itemName = parts[0];
                int qty = Integer.parseInt(parts[1]);
                BigDecimal price = new BigDecimal(parts[2]);
                Item item = itemMapper.selectOne(new LambdaQueryWrapper<Item>()
                        .eq(Item::getProjectId, project.getId()).eq(Item::getItemName, itemName).last("LIMIT 1"));
                if (item == null) {
                    // 自动建 Item + upsert 货物
                    Long goodsId = goodsService.upsert(itemName, Map.of(), true, "期初发货导入 batch=" + batchNo);
                    item = new Item();
                    item.setProjectId(project.getId());
                    item.setItemName(itemName);
                    item.setGoodsId(goodsId);
                    item.setQuantity(qty);
                    item.setUnitPrice(price);
                    item.setTotalAmount(price.multiply(BigDecimal.valueOf(qty)));
                    itemMapper.insert(item);
                }
                // shipped_qty 直接覆盖（不走乐观锁累加）
                item.setShippedQty(qty);
                itemMapper.updateById(item);

                ShipmentLine sl = new ShipmentLine();
                sl.setShipmentId(s.getId());
                sl.setItemId(item.getId());
                sl.setShipmentQty(qty);
                sl.setReceivedQty(0);
                sl.setReturnedQty(0);
                sl.setLineStatus("PENDING");
                sl.setUnitPrice(price);
                sl.setLineAmount(price.multiply(BigDecimal.valueOf(qty)));
                shipmentLineMapper.insert(sl);
            }
            n++;
        }
        return n;
    }

    /** 解析「品名|数量|单价;...」，返回 [name, qty, price]；错误追加到 err。 */
    private static List<String[]> parseLines(String s, StringBuilder err) {
        List<String[]> out = new ArrayList<>();
        if (s == null || s.isBlank()) return out;
        for (String seg : s.split(";")) {
            String[] parts = seg.split("\\|");
            if (parts.length != 3) {
                err.append("明细格式错误（应为 品名|数量|单价）；");
                continue;
            }
            try {
                String name = parts[0].trim();
                int qty = Integer.parseInt(parts[1].trim());
                BigDecimal price = new BigDecimal(parts[2].trim());
                if (name.isEmpty() || qty <= 0 || price.signum() < 0) {
                    err.append("明细品名/数量/单价非法；");
                    continue;
                }
                out.add(new String[]{name, String.valueOf(qty), parts[2].trim()});
            } catch (NumberFormatException e) {
                err.append("明细数量或单价格式错误；");
            }
        }
        return out;
    }

    private static String val(Map<String, String> row, String field) {
        String v = row.get(field);
        return v == null ? "" : v.trim();
    }
}
