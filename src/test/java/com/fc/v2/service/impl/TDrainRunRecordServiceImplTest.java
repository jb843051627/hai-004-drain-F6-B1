package com.fc.v2.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.common.dataSources.DrainAreaScope;
import com.fc.v2.common.exception.DrainArchiveException;
import com.fc.v2.mapper.auto.TDrainPumpUnitMapper;
import com.fc.v2.mapper.auto.TDrainRunRecordMapper;
import com.fc.v2.mapper.auto.TDrainStationMapper;
import com.fc.v2.model.auto.TDrainPumpUnit;
import com.fc.v2.model.auto.TDrainRunRecord;
import com.fc.v2.model.auto.TDrainStation;

/**
 * 机组运行记录单登记红线测试（内存库模拟表）：
 * 单号照档案标准号 YX-年-机组ID、一机组一年一单、停用站不收、时长非法当场退回、
 * 电耗费与评定档系统算出（表单值被覆盖）、评定档压线归本档、
 * 创建人照登录人钉死（表单伪造值被覆盖）、编辑单号不动不拦/改号不收、并发撞键退回。
 *
 * @author fuce
 * @date 2026-09-29
 */
class TDrainRunRecordServiceImplTest {

    private TDrainRunRecordServiceImpl service;
    private final Map<Long, TDrainRunRecord> records = new LinkedHashMap<>();
    private final Map<Long, TDrainStation> stations = new LinkedHashMap<>();
    private final Map<Long, TDrainPumpUnit> units = new LinkedHashMap<>();
    private final AtomicLong seq = new AtomicLong(5000);
    private final BigDecimal price = new BigDecimal("0.80");
    /** 插库时是否替库唯一键抛撞键，模拟并发下第二笔 */
    private boolean failNextInsertWithDup;

    @BeforeEach
    void setUp() {
        records.clear();
        stations.clear();
        units.clear();
        seq.set(5000);
        failNextInsertWithDup = false;

        TDrainRunRecordMapper recordMapper = mock(TDrainRunRecordMapper.class);
        TDrainStationMapper stationMapper = mock(TDrainStationMapper.class);
        TDrainPumpUnitMapper unitMapper = mock(TDrainPumpUnitMapper.class);

        when(stationMapper.selectById(any())).thenAnswer(inv -> stations.get(((Number) inv.getArgument(0)).longValue()));
        when(stationMapper.selectOne(any())).thenAnswer(inv -> {
            QueryWrapper<TDrainStation> w = inv.getArgument(0);
            Map<String, List<Occ>> p = parse(w.getSqlSegment(), w.getParamNameValuePairs());
            String siteNo = strEq(p, "site_no");
            return siteNo == null ? null : stations.values().stream()
                    .filter(s -> siteNo.equals(s.getSiteNo())).findFirst().orElse(null);
        });
        when(unitMapper.selectById(any())).thenAnswer(inv -> units.get(((Number) inv.getArgument(0)).longValue()));

        when(recordMapper.selectOne(any())).thenAnswer(inv -> {
            QueryWrapper<TDrainRunRecord> w = inv.getArgument(0);
            Map<String, List<Occ>> p = parse(w.getSqlSegment(), w.getParamNameValuePairs());
            Object id = first(p, "id", "=");
            TDrainRunRecord r = id == null ? null : records.get(((Number) id).longValue());
            return r != null && !Integer.valueOf(1).equals(r.getDelFlag()) ? r : null;
        });

        when(recordMapper.selectCount(any())).thenAnswer(inv -> {
            QueryWrapper<TDrainRunRecord> w = inv.getArgument(0);
            Map<String, List<Occ>> p = parse(w.getSqlSegment(), w.getParamNameValuePairs());
            String billNo = strEq(p, "bill_no");
            Long unitId = eqLong(p, "unit_id");
            Integer runYear = intEq(p, "run_year");
            return (int) records.values().stream()
                    .filter(r -> !Integer.valueOf(1).equals(r.getDelFlag()))
                    .filter(r -> billNo == null || billNo.equals(r.getBillNo()))
                    .filter(r -> unitId == null || unitId.equals(r.getUnitId()))
                    .filter(r -> runYear == null || runYear.equals(r.getRunYear()))
                    .count();
        });

        when(recordMapper.selectList(any())).thenAnswer(inv -> {
            QueryWrapper<TDrainRunRecord> w = inv.getArgument(0);
            Map<String, List<Occ>> p = parse(w.getSqlSegment(), w.getParamNameValuePairs());
            Integer status = intEq(p, "status");
            String billLike = strEq(p, "bill_no");
            String siteNo = strEq(p, "site_no");
            return records.values().stream()
                    .filter(r -> !Integer.valueOf(1).equals(r.getDelFlag()))
                    .filter(r -> status == null || status.equals(r.getStatus()))
                    .filter(r -> billLike == null || (r.getBillNo() != null
                            && r.getBillNo().contains(billLike.replace("%", ""))))
                    .filter(r -> siteNo == null || siteNo.equals(r.getSiteNo()))
                    .collect(Collectors.toList());
        });

        when(recordMapper.insert(any())).thenAnswer(inv -> {
            if (failNextInsertWithDup) {
                throw new DuplicateKeyException("模拟库唯一键并发撞单");
            }
            TDrainRunRecord r = inv.getArgument(0);
            if (r.getId() == null) {
                r.setId(seq.incrementAndGet());
            }
            records.put(r.getId(), r);
            return 1;
        });

        when(recordMapper.updateById(any())).thenAnswer(inv -> {
            TDrainRunRecord patch = inv.getArgument(0);
            TDrainRunRecord cur = records.get(patch.getId());
            if (cur == null || Integer.valueOf(1).equals(cur.getDelFlag())) {
                return 0;
            }
            // 只收 patch 里非空列：模拟 MyBatis-Plus updateById 非空更新
            if (patch.getQty() != null) {
                cur.setQty(patch.getQty());
            }
            if (patch.getFineAmt() != null) {
                cur.setFineAmt(patch.getFineAmt());
            }
            if (patch.getGradeLevel() != null) {
                cur.setGradeLevel(patch.getGradeLevel());
            }
            if (patch.getRemark() != null) {
                cur.setRemark(patch.getRemark());
            }
            if (patch.getUpdateBy() != null) {
                cur.setUpdateBy(patch.getUpdateBy());
            }
            if (patch.getUpdateTime() != null) {
                cur.setUpdateTime(patch.getUpdateTime());
            }
            return 1;
        });

        service = new TDrainRunRecordServiceImpl();
        ReflectionTestUtils.setField(service, "baseMapper", recordMapper);
        ReflectionTestUtils.setField(service, "drainStationMapper", stationMapper);
        ReflectionTestUtils.setField(service, "drainPumpUnitMapper", unitMapper);
        // 无安全上下文：系统调用，不限片；currentOperator 落 Task
        ReflectionTestUtils.setField(service, "drainAreaScope", new DrainAreaScope());
    }

    private TDrainStation station(long id, String siteNo, Integer status, String roadName) {
        TDrainStation s = new TDrainStation();
        s.setId(id);
        s.setSiteNo(siteNo);
        s.setSiteName(siteNo + "号泵站");
        s.setRoadName(roadName);
        s.setStatus(status);
        s.setTh1Max(new BigDecimal("1"));
        s.setTh2Max(new BigDecimal("5"));
        s.setTh3Max(new BigDecimal("10"));
        s.setDelFlag(0);
        stations.put(id, s);
        return s;
    }

    private TDrainPumpUnit unit(long id, String unitNo, long siteId, String siteNo) {
        TDrainPumpUnit u = new TDrainPumpUnit();
        u.setId(id);
        u.setUnitNo(unitNo);
        u.setUnitName(unitNo + "号机组");
        u.setSiteId(siteId);
        u.setSiteNo(siteNo);
        u.setItemCode(siteNo + "-EM-0" + (id % 10));
        u.setSiteType("YS_AX");
        u.setDelFlag(0);
        units.put(id, u);
        return u;
    }

    /** 档案标准号 YX-运行年-机组ID（与 drain_run_seed.sql 同口径）。 */
    private static String stdNo(long unitId) {
        return "YX-" + LocalDate.now().getYear() + "-" + unitId;
    }

    private TDrainRunRecord form(String billNo, Long unitId, String qty) {
        TDrainRunRecord r = new TDrainRunRecord();
        r.setBillNo(billNo);
        r.setUnitId(unitId);
        r.setQty(qty == null ? null : new BigDecimal(qty));
        return r;
    }

    /** 正常登记：单号照档案标准号。 */
    private TDrainRunRecord register(long unitId, String qty) {
        return service.registerTDrainRunRecord(form(stdNo(unitId), unitId, qty), price);
    }

    /** 手填单号登记：用于测旁号/空号。 */
    private TDrainRunRecord register(String billNo, long unitId, String qty) {
        return service.registerTDrainRunRecord(form(billNo, unitId, qty), price);
    }

    // 1. 登记一张：单号照标准号、系统回填站号/年度、落待复核，台账看得见
    @Test
    void register_ok_setsSystemFields() {
        station(0L, "PS00", 0, "城东片区");
        unit(2001L, "1#", 0L, "PS00");

        TDrainRunRecord saved = register(2001L, "0.5");

        assertEquals(stdNo(2001L), saved.getBillNo(), "单号照档案标准号回填");
        assertEquals(0, saved.getStatus().intValue(), "新登记即待复核");
        assertEquals(0, saved.getDelFlag().intValue());
        assertEquals("PS00", saved.getSiteNo(), "站号照机组所属站回填，不信表单");
        assertEquals(0L, saved.getSiteId().longValue());
        assertEquals(LocalDate.now().getYear(), saved.getRunYear().intValue(), "运行年度取系统年");
        assertEquals(1, service.selectTDrainRunRecordList(new QueryWrapper<>()).size());
    }

    // 2. 电耗费由系统照时长×单价核算；表单硬塞的费/档被覆盖
    @Test
    void register_fineAmtComputedBySystem_formValueOverwritten() {
        station(0L, "PS00", 0, "城东片区");
        unit(2001L, "1#", 0L, "PS00");
        TDrainRunRecord r = form(stdNo(2001), 2001L, "2");
        r.setFineAmt(new BigDecimal("9999.99")); // 人手想定的费，必须被系统覆盖
        r.setGradeLevel(1);                      // 人手想定的档，必须被系统覆盖

        TDrainRunRecord saved = service.registerTDrainRunRecord(r, price);

        assertEquals(new BigDecimal("1.60"), saved.getFineAmt(), "2h × 0.80 = 1.60，系统说了算");
        assertEquals(2, saved.getGradeLevel().intValue(), "2 高出一档上限、压在二档上限内，落二档");
    }

    // 3. 创建人照当前登录人钉死：表单伪造「片区运行员」必须被覆盖，谁操作落谁的字
    @Test
    void register_createByForcedToOperator_formForgeryOverwritten() {
        station(0L, "PS00", 0, "城东片区");
        unit(2001L, "1#", 0L, "PS00");
        TDrainRunRecord r = form(stdNo(2001), 2001L, "0.5");
        r.setCreateBy("片区运行员"); // 表单冒名，必须被当前登录人覆盖

        TDrainRunRecord saved = service.registerTDrainRunRecord(r, price);

        assertNotEquals("片区运行员", saved.getCreateBy(), "冒名值不许落库");
        assertEquals("Task", saved.getCreateBy(), "无安全上下文按系统调用落 Task，生产即登录名");
    }

    // 4. 单号人手填：空号退回
    @Test
    void register_blankBillNo_rejected() {
        station(0L, "PS00", 0, "城东片区");
        unit(2001L, "1#", 0L, "PS00");
        assertThrows(DrainArchiveException.class, () -> register("  ", 2001L, "0.5"));
        assertTrue(records.isEmpty());
    }

    // 5. 手改旁号不照档案：当场退回，不许直接落库造成两边口径分叉
    @Test
    void register_wrongBillNo_againstArchive_rejected() {
        station(0L, "PS00", 0, "城东片区");
        unit(2001L, "1#", 0L, "PS00");
        unit(2002L, "2#", 0L, "PS00");
        register(2001L, "0.5");
        // 给 2002 登记，却手填成 2001 的标准号（旁号），服务层对档案当场退回
        assertThrows(DrainArchiveException.class, () -> register(stdNo(2001L), 2002L, "0.5"));
        assertEquals(1, records.size(), "旁号不进流水");
    }

    // 6. 一机组一年一单：同机组当年第二张（号照标准号也不行）退回；跨机组不连坐
    @Test
    void register_oneUnitOneYear_secondRejected() {
        station(0L, "PS00", 0, "城东片区");
        unit(2001L, "1#", 0L, "PS00");
        unit(2002L, "2#", 0L, "PS00");
        register(2001L, "0.5");
        assertThrows(DrainArchiveException.class, () -> register(2001L, "0.6"),
                "同一机组当年已有一张，不开第二张");
        // 不同机组同年各开一张，单号各自照档案标准号
        assertEquals(stdNo(2002L), register(2002L, "0.7").getBillNo());
        assertEquals(2, records.size());
    }

    // 7. 停用站不许挂新单；老单原样留存；档案改回在用即能开
    @Test
    void register_disabledStation_rejected_oldKept() {
        TDrainStation s = station(1L, "PS01", 1, "城西片区");
        unit(2011L, "1#", 1L, "PS01");
        TDrainRunRecord old = new TDrainRunRecord();
        old.setId(4002L);
        old.setBillNo("YX-OLD");
        old.setUnitId(2011L);
        old.setSiteNo("PS01");
        old.setRunYear(LocalDate.now().getYear() - 1);
        old.setQty(new BigDecimal("2"));
        old.setGradeLevel(2);
        old.setStatus(2);
        old.setDelFlag(0);
        records.put(4002L, old);

        assertThrows(DrainArchiveException.class, () -> register(2011L, "0.5"));
        assertEquals(1, records.size(), "新单不进流水，老单原样留");
        assertNotNull(service.selectTDrainRunRecordList(new QueryWrapper<>())
                .stream().filter(r -> "YX-OLD".equals(r.getBillNo())).findFirst().orElse(null));

        s.setStatus(0); // 档案改回在用，下一轮自然能开（老单是去年的，不撞今年一单）
        TDrainRunRecord reopened = register(2011L, "0.5");
        assertNotNull(reopened.getId());
        assertEquals("PS01", reopened.getSiteNo());
    }

    // 8. 时长：负、零当场退回
    @Test
    void register_negativeOrZeroQty_rejected() {
        station(0L, "PS00", 0, "城东片区");
        unit(2001L, "1#", 0L, "PS00");
        assertThrows(DrainArchiveException.class, () -> register(2001L, "-1"));
        assertThrows(DrainArchiveException.class, () -> register(2001L, "0"));
        assertThrows(DrainArchiveException.class,
                () -> service.registerTDrainRunRecord(form(stdNo(2001), 2001L, null), price));
        assertTrue(records.isEmpty());
    }

    // 9. 时长数量级明显不对（超过全年小时数）当场退回
    @Test
    void register_absurdMagnitudeQty_rejected() {
        station(0L, "PS00", 0, "城东片区");
        unit(2001L, "1#", 0L, "PS00");
        assertThrows(DrainArchiveException.class, () -> register(2001L, "99999"));
        assertTrue(records.isEmpty(), "离谱时长不进流水");
    }

    // 10. 评定档压线归本档（考核口径）：正压 1/5/10 仍归 1/2/3 档，高出三档上限才归超档
    @Test
    void register_gradeBoundary_upperLimitBelongsToSameTier() {
        station(0L, "PS00", 0, "城东片区");
        unit(2001L, "1#", 0L, "PS00");
        unit(2002L, "2#", 0L, "PS00");
        unit(2003L, "3#", 0L, "PS00");
        unit(2004L, "4#", 0L, "PS00");

        assertEquals(1, register(2001L, "1").getGradeLevel().intValue(), "正压一档上限 1，留一档");
        assertEquals(2, register(2002L, "5").getGradeLevel().intValue(), "正压二档上限 5，留二档");
        assertEquals(3, register(2003L, "10").getGradeLevel().intValue(), "正压三档上限 10，留三档");
        assertEquals(4, register(2004L, "10.01").getGradeLevel().intValue(), "高出三档上限归超档");
    }

    // 11. 并发撞键：两道同号/同机组同年，库唯一键替后到的这笔抛错，服务层转业务退回，不重单
    @Test
    void register_concurrentDuplicateKey_translatedToBusinessReject() {
        station(0L, "PS00", 0, "城东片区");
        unit(2001L, "1#", 0L, "PS00");
        failNextInsertWithDup = true;
        assertThrows(DrainArchiveException.class, () -> register(2001L, "0.5"),
                "库唯一键并发撞单，转成业务退回");
        assertTrue(records.isEmpty(), "撞键的这笔不进流水");
    }

    // 12. 机组/泵站不存在不收；缺机组锚点不收
    @Test
    void register_missingUnitOrStationOrNoUnit_rejected() {
        assertThrows(DrainArchiveException.class, () -> register(404L, "0.5"), "机组不存在不开单");
        station(0L, "PS00", 0, "城东片区");
        TDrainPumpUnit ghost = unit(2099L, "9#", 0L, "PS00");
        stations.remove(0L); // 机组挂的站没了
        assertThrows(DrainArchiveException.class, () -> register(2099L, "0.5"));
        assertThrows(DrainArchiveException.class,
                () -> service.registerTDrainRunRecord(form(stdNo(0), null, "0.5"), price));
    }

    // 13. 台账三态都摆、不硬塞状态
    @Test
    void list_showsAllStatuses() {
        station(0L, "PS00", 0, "城东片区");
        unit(2001L, "1#", 0L, "PS00");
        unit(2002L, "2#", 0L, "PS00");
        unit(2003L, "3#", 0L, "PS00");
        TDrainRunRecord r1 = register(2001L, "0.5");
        TDrainRunRecord r2 = register(2002L, "0.5");
        TDrainRunRecord r3 = register(2003L, "0.5");
        r1.setStatus(0);
        r2.setStatus(1);
        r3.setStatus(2);
        assertEquals(3, service.selectTDrainRunRecordList(new QueryWrapper<>()).size());
    }

    // 14. 编辑老单、单号压根没动：照常保存，不再因历史重单的 ne(id) 查重被误伤
    @Test
    void update_billNoUnchanged_savesAndRecomputes() {
        station(0L, "PS00", 0, "城东片区");
        unit(2001L, "1#", 0L, "PS00");
        TDrainRunRecord saved = register(2001L, "2"); // 2h → 1.60，二档
        TDrainRunRecord patch = new TDrainRunRecord();
        patch.setId(saved.getId());
        patch.setBillNo(saved.getBillNo()); // 单号原样带回，没动
        patch.setQty(new BigDecimal("6"));  // 6 ≤10、>5 → 三档；6×0.80=4.80

        assertEquals(1, service.updateTDrainRunRecord(patch));
        TDrainRunRecord after = records.get(saved.getId());
        assertEquals(new BigDecimal("4.80"), after.getFineAmt(), "时长改了，费照系统重算");
        assertEquals(3, after.getGradeLevel().intValue(), "档照档案重算落三档");
        assertEquals("Task", after.getUpdateBy(), "更新人照当前操作人落");
    }

    // 15. 编辑真改单号：当场退回，号是照档案钉死的锚点
    @Test
    void update_billNoChanged_rejected() {
        station(0L, "PS00", 0, "城东片区");
        unit(2001L, "1#", 0L, "PS00");
        TDrainRunRecord saved = register(2001L, "2");
        TDrainRunRecord patch = new TDrainRunRecord();
        patch.setId(saved.getId());
        patch.setBillNo("YX-HACKED");

        assertThrows(DrainArchiveException.class, () -> service.updateTDrainRunRecord(patch));
        assertEquals(stdNo(2001L), records.get(saved.getId()).getBillNo(), "单号原样未动");
    }

    // 16. 编辑只放时长/备注：表单带的锚点列与系统列一概不进门
    @Test
    void update_anchorAndSystemColumns_notTouched() {
        station(0L, "PS00", 0, "城东片区");
        unit(2001L, "1#", 0L, "PS00");
        TDrainRunRecord saved = register(2001L, "2");
        Long originUnit = saved.getUnitId();
        String originSite = saved.getSiteNo();
        Integer originYear = saved.getRunYear();
        Integer originStatus = saved.getStatus();

        TDrainRunRecord patch = new TDrainRunRecord();
        patch.setId(saved.getId());
        patch.setBillNo(saved.getBillNo());
        patch.setRemark("复核备注");
        patch.setUnitId(2099L);   // 想换机组
        patch.setSiteNo("PS99"); // 想换站
        patch.setRunYear(1999);  // 想改年度
        patch.setStatus(2);      // 想直接封存
        patch.setDelFlag(1);     // 想顺带删
        patch.setCreateBy("片区运行员"); // 想改创建人
        patch.setFineAmt(new BigDecimal("0.01")); // 想手压费
        patch.setGradeLevel(1);  // 想手压档

        assertEquals(1, service.updateTDrainRunRecord(patch));
        TDrainRunRecord after = records.get(saved.getId());
        assertEquals(originUnit, after.getUnitId());
        assertEquals(originSite, after.getSiteNo());
        assertEquals(originYear, after.getRunYear());
        assertEquals(originStatus, after.getStatus());
        assertEquals(0, after.getDelFlag().intValue());
        assertNotEquals("片区运行员", after.getCreateBy());
        assertEquals(new BigDecimal("1.60"), after.getFineAmt(), "未改时长，费不被手压");
        assertEquals(2, after.getGradeLevel().intValue());
        assertEquals("复核备注", after.getRemark());
    }

    // 17. 编辑不存在的单：返回 0
    @Test
    void update_missing_returnsZero() {
        TDrainRunRecord patch = new TDrainRunRecord();
        patch.setId(999999L);
        patch.setQty(new BigDecimal("1"));
        assertEquals(0, service.updateTDrainRunRecord(patch));
    }

    // ---- 参数抠取小工具（QueryWrapper 参数按 "?" 全局先后次序绑定） ----

    private static Map<String, List<Occ>> parse(String seg, Map<String, Object> params) {
        Map<String, List<Occ>> out = new LinkedHashMap<>();
        Matcher m = Pattern.compile(
                "(\\w+)\\s*(=|<>|LIKE)\\s*#\\{ew\\.paramNameValuePairs\\.(MPGENVAL\\d+)\\}").matcher(seg);
        while (m.find()) {
            out.computeIfAbsent(m.group(1), k -> new ArrayList<>())
                    .add(new Occ(m.group(2), params.get(m.group(3))));
        }
        return out;
    }

    private static Object first(Map<String, List<Occ>> parsed, String column, String op) {
        List<Occ> occs = parsed.get(column);
        if (occs == null) {
            return null;
        }
        for (Occ o : occs) {
            if (op == null || o.op.equals(op)) {
                return o.value;
            }
        }
        return null;
    }

    private static String strEq(Map<String, List<Occ>> p, String column) {
        Object v = first(p, column, "=");
        if (v == null) {
            v = first(p, column, "LIKE");
        }
        return v == null ? null : v.toString();
    }

    private static Integer intEq(Map<String, List<Occ>> p, String column) {
        Object v = first(p, column, "=");
        return v instanceof Number ? ((Number) v).intValue() : null;
    }

    private static Long eqLong(Map<String, List<Occ>> p, String column) {
        Object v = first(p, column, "=");
        return v instanceof Number ? ((Number) v).longValue() : null;
    }

    private static final class Occ {
        final String op;
        final Object value;

        Occ(String op, Object value) {
            this.op = op;
            this.value = value;
        }
    }
}
