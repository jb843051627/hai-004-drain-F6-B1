package com.fc.v2.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
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
 * 单号人手填、一机组一年一单、停用站不收、时长负/零/数量级离谱当场退回、
 * 电耗费与评定档系统算出（表单值被覆盖）、台账三态都摆、片区 where 裁片；
 * 另覆盖：创建人只认当前登录人、评定档上限含本档、并发同号/同机组同年只一成、
 * 编辑不改号放行/改号校重号、编辑重算费档并署更新人。
 *
 * @author fuce
 * @date 2026-09-29
 */
class TDrainRunRecordServiceImplTest {

    private TDrainRunRecordServiceImpl service;
    // 并发用例要两线程同时打进来，内存表用并发 map，临界区锁保证同键两笔串行
    private final Map<Long, TDrainRunRecord> records = new ConcurrentHashMap<>();
    private final Map<Long, TDrainStation> stations = new ConcurrentHashMap<>();
    private final Map<Long, TDrainPumpUnit> units = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong(5000);
    private final BigDecimal price = new BigDecimal("0.80");

    /** 单测里的当前登录人，可随时换，验"谁提交署谁名" */
    private String currentUser = "duty_li";

    @BeforeEach
    void setUp() {
        records.clear();
        stations.clear();
        units.clear();
        seq.set(5000);
        currentUser = "duty_li";

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

        when(recordMapper.selectById(any())).thenAnswer(inv -> records.get(((Number) inv.getArgument(0)).longValue()));

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
            Long neId = neLong(p, "id");
            return (int) records.values().stream()
                    .filter(r -> !Integer.valueOf(1).equals(r.getDelFlag()))
                    .filter(r -> billNo == null || billNo.equals(r.getBillNo()))
                    .filter(r -> unitId == null || unitId.equals(r.getUnitId()))
                    .filter(r -> runYear == null || runYear.equals(r.getRunYear()))
                    .filter(r -> neId == null || !neId.equals(r.getId()))
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
            TDrainRunRecord r = inv.getArgument(0);
            if (r.getId() == null) {
                r.setId(seq.incrementAndGet());
            }
            records.put(r.getId(), r);
            return 1;
        });

        // 新编辑口走 updateById：传的是载出后改完的完整单，整单放回内存表
        when(recordMapper.updateById(any())).thenAnswer(inv -> {
            TDrainRunRecord r = inv.getArgument(0);
            if (r.getId() == null || !records.containsKey(r.getId())) {
                return 0;
            }
            records.put(r.getId(), r);
            return 1;
        });

        when(recordMapper.update(any(), any(UpdateWrapper.class))).thenAnswer(inv -> {
            TDrainRunRecord r = inv.getArgument(0);
            records.put(r.getId(), r);
            return 1;
        });

        service = new TDrainRunRecordServiceImpl() {
            // 不依赖 Shiro 容器：当前登录人由测试给定
            @Override
            protected String currentLoginName() {
                return currentUser;
            }
        };
        ReflectionTestUtils.setField(service, "baseMapper", recordMapper);
        ReflectionTestUtils.setField(service, "drainStationMapper", stationMapper);
        ReflectionTestUtils.setField(service, "drainPumpUnitMapper", unitMapper);
        // 无安全上下文：系统调用，不限片
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

    /** 自定义三档阈值的站，专测压线口径 */
    private TDrainStation stationThresholds(long id, String th1, String th2, String th3) {
        TDrainStation s = station(id, "PS" + id, 0, "城东片区");
        s.setTh1Max(th1 == null ? null : new BigDecimal(th1));
        s.setTh2Max(th2 == null ? null : new BigDecimal(th2));
        s.setTh3Max(th3 == null ? null : new BigDecimal(th3));
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

    private TDrainRunRecord form(String billNo, Long unitId, String qty) {
        TDrainRunRecord r = new TDrainRunRecord();
        r.setBillNo(billNo);
        r.setUnitId(unitId);
        r.setQty(qty == null ? null : new BigDecimal(qty));
        return r;
    }

    private TDrainRunRecord register(String billNo, long unitId, String qty) {
        return service.registerTDrainRunRecord(form(billNo, unitId, qty), price);
    }

    // 1. 登记一张：单号手填、系统回填站号/年度、落待复核，台账看得见
    @Test
    void register_ok_setsSystemFields() {
        station(0L, "PS00", 0, "城东片区");
        unit(2001L, "1#", 0L, "PS00");

        TDrainRunRecord saved = register("YX001", 2001L, "0.5");

        assertEquals(0, saved.getStatus().intValue(), "新登记即待复核");
        assertEquals(0, saved.getDelFlag().intValue());
        assertEquals("PS00", saved.getSiteNo(), "站号照机组所属站回填，不信表单");
        assertEquals(0L, saved.getSiteId().longValue());
        assertEquals(LocalDate.now().getYear(), saved.getRunYear().intValue(), "运行年度取系统年");
        assertEquals(1, service.selectTDrainRunRecordList(new QueryWrapper<>()).size());
    }

    // 2. 电耗费由系统照时长×单价核算；表单硬塞的值被覆盖
    @Test
    void register_fineAmtComputedBySystem_formValueOverwritten() {
        station(0L, "PS00", 0, "城东片区");
        unit(2001L, "1#", 0L, "PS00");
        TDrainRunRecord r = form("YX002", 2001L, "2");
        r.setFineAmt(new BigDecimal("9999.99")); // 人手想定的费，必须被系统覆盖
        r.setGradeLevel(1);                      // 人手想定的档，必须被系统覆盖

        TDrainRunRecord saved = service.registerTDrainRunRecord(r, price);

        assertEquals(new BigDecimal("1.60"), saved.getFineAmt(), "2h × 0.80 = 1.60，系统说了算");
        assertEquals(2, saved.getGradeLevel().intValue(), "2 在(一档上限1, 二档上限5]落二档，人手值被覆盖");
    }

    // 3. 单号人手填：空号退回
    @Test
    void register_blankBillNo_rejected() {
        station(0L, "PS00", 0, "城东片区");
        unit(2001L, "1#", 0L, "PS00");
        assertThrows(DrainArchiveException.class, () -> register("  ", 2001L, "0.5"));
        assertTrue(records.isEmpty());
    }

    // 4. 单号手填也不许重
    @Test
    void register_duplicateBillNo_rejected() {
        station(0L, "PS00", 0, "城东片区");
        unit(2001L, "1#", 0L, "PS00");
        unit(2002L, "2#", 0L, "PS00");
        register("YX-DUP", 2001L, "0.5");
        assertThrows(DrainArchiveException.class, () -> register("YX-DUP", 2002L, "0.5"));
        assertEquals(1, records.size());
    }

    // 5. 一机组一年一单：同机组当年第二张退回；跨年/跨机组不连坐
    @Test
    void register_oneUnitOneYear_secondRejected() {
        station(0L, "PS00", 0, "城东片区");
        unit(2001L, "1#", 0L, "PS00");
        unit(2002L, "2#", 0L, "PS00");
        register("YX-A", 2001L, "0.5");
        assertThrows(DrainArchiveException.class, () -> register("YX-B", 2001L, "0.6"),
                "同一机组当年已有一张，不开第二张");
        // 不同机组同年可以各开一张
        assertEquals("YX-C", register("YX-C", 2002L, "0.7").getBillNo());
        assertEquals(2, records.size());
    }

    // 6. 停用站不许挂新单；老单原样留存
    @Test
    void register_disabledStation_rejected_oldKept() {
        TDrainStation s = station(1L, "PS01", 1, "城西片区");
        unit(2011L, "1#", 1L, "PS01");
        // 名下老单
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

        assertThrows(DrainArchiveException.class, () -> register("YX-NEW", 2011L, "0.5"));
        assertEquals(1, records.size(), "新单不进流水，老单原样留");
        assertNotNull(service.selectTDrainRunRecordList(new QueryWrapper<>())
                .stream().filter(r -> "YX-OLD".equals(r.getBillNo())).findFirst().orElse(null));

        s.setStatus(0); // 档案改回在用，下一轮自然能开（老单是去年的，不撞今年一单）
        TDrainRunRecord reopened = service.registerTDrainRunRecord(form("YX-NEW", 2011L, "0.5"), price);
        assertNotNull(reopened.getId());
        assertEquals("PS01", reopened.getSiteNo());
    }

    // 7. 时长：负、零当场退回
    @Test
    void register_negativeOrZeroQty_rejected() {
        station(0L, "PS00", 0, "城东片区");
        unit(2001L, "1#", 0L, "PS00");
        assertThrows(DrainArchiveException.class, () -> register("YX-N1", 2001L, "-1"));
        assertThrows(DrainArchiveException.class, () -> register("YX-N2", 2001L, "0"));
        assertThrows(DrainArchiveException.class, () -> register("YX-N3", 2001L, null));
        assertTrue(records.isEmpty());
    }

    // 8. 时长数量级明显不对（超过全年小时数）当场退回
    @Test
    void register_absurdMagnitudeQty_rejected() {
        station(0L, "PS00", 0, "城东片区");
        unit(2001L, "1#", 0L, "PS00");
        assertThrows(DrainArchiveException.class, () -> register("YX-BIG", 2001L, "99999"));
        assertTrue(records.isEmpty(), "离谱时长不进流水");
    }

    // 9. 机组/泵站不存在不收
    @Test
    void register_missingUnitOrStation_rejected() {
        assertThrows(DrainArchiveException.class, () -> register("YX-M1", 404L, "0.5"),
                "机组不存在不开单");
        station(0L, "PS00", 0, "城东片区");
        TDrainPumpUnit ghost = unit(2099L, "9#", 0L, "PS00");
        stations.remove(0L); // 机组挂的站没了
        assertThrows(DrainArchiveException.class, () -> register("YX-M2", 2099L, "0.5"));
    }

    // 10. 缺机组锚点不收（一机组一年一单的锚点是机组不是站）
    @Test
    void register_withoutUnit_rejected() {
        TDrainRunRecord r = form("YX-NOUNIT", null, "0.5");
        assertThrows(DrainArchiveException.class,
                () -> service.registerTDrainRunRecord(r, price));
    }

    // 11. 台账三态都摆、不硬塞状态
    @Test
    void list_showsAllStatuses() {
        station(0L, "PS00", 0, "城东片区");
        unit(2001L, "1#", 0L, "PS00");
        unit(2002L, "2#", 0L, "PS00");
        unit(2003L, "3#", 0L, "PS00");
        TDrainRunRecord r1 = register("YX1", 2001L, "0.5");
        TDrainRunRecord r2 = register("YX2", 2002L, "0.5");
        TDrainRunRecord r3 = register("YX3", 2003L, "0.5");
        r1.setStatus(0);
        r2.setStatus(1);
        r3.setStatus(2);
        assertEquals(3, service.selectTDrainRunRecordList(new QueryWrapper<>()).size());
    }

    // 12. 创建人只认当前登录人：表单夹带的占位名「片区运行员」必须被盖掉
    @Test
    void register_creatorIsCurrentLogin_formCarrierOverwritten() {
        station(0L, "PS00", 0, "城东片区");
        unit(2001L, "1#", 0L, "PS00");
        currentUser = "duty_li";
        TDrainRunRecord r = form("YX-WHO", 2001L, "0.5");
        r.setCreateBy("片区运行员"); // 页面占位/上一人名字，绝不能落库
        r.setUpdateBy("片区运行员");

        TDrainRunRecord saved = service.registerTDrainRunRecord(r, price);

        assertEquals("duty_li", saved.getCreateBy(), "谁提交署谁名，占位名被登录人覆盖");
        assertNull(saved.getUpdateBy(), "新登记没有更新者，表单夹带的一并清掉");
    }

    // 13. 换个人提交，落的就是另一个人的名（不跟账号串）
    @Test
    void register_creatorFollowsLoginSwitch() {
        station(0L, "PS00", 0, "城东片区");
        unit(2001L, "1#", 0L, "PS00");
        unit(2002L, "2#", 0L, "PS00");
        currentUser = "duty_li";
        TDrainRunRecord a = register("YX-A", 2001L, "0.5");
        currentUser = "duty_wang";
        TDrainRunRecord b = register("YX-B", 2002L, "0.5");
        assertEquals("duty_li", a.getCreateBy());
        assertEquals("duty_wang", b.getCreateBy(), "换人登录，署名跟着换，不钉死在一个名字上");
    }

    // 14. 评定档「上限含本档」：恰好敲在上限不掉档；高出才掉下一档
    @Test
    void register_grade_upperBoundInclusive() {
        // 阈值 1/5/10
        stationThresholds(0L, "1", "5", "10");
        unit(2001L, "1#", 0L, "PS0");

        assertEquals(1, register("YX-G1", 2001L, "1").getGradeLevel(),
                "恰好等于一档上限1，仍归一档，不许掉成二档");
        units.clear();
        unit(2002L, "2#", 0L, "PS0");
        assertEquals(2, register("YX-G2", 2002L, "5").getGradeLevel(),
                "恰好等于二档上限5，仍归二档");
        units.clear();
        unit(2003L, "3#", 0L, "PS0");
        assertEquals(3, register("YX-G3", 2003L, "10").getGradeLevel(),
                "恰好等于三档上限10，仍归三档");
        // 临界点两侧必须差开：1.00 在一档，1.01 就进二档
        units.clear();
        unit(2004L, "4#", 0L, "PS0");
        assertEquals(2, register("YX-G4", 2004L, "1.01").getGradeLevel());
        units.clear();
        unit(2005L, "5#", 0L, "PS0");
        assertEquals(4, register("YX-G5", 2005L, "10.01").getGradeLevel(), "高出三档上限归四档");
    }

    // 15. 并发：同一单号两个请求同时登记，只许一张落库
    @Test
    void register_concurrentSameBillNo_onlyOneWins() throws Exception {
        station(0L, "PS00", 0, "城东片区");
        // 每轮换一台新机组，避免「一机组一年一单」干扰，单看同号这道闸
        for (int round = 0; round < 20; round++) {
            unit(2100L + round, (round + 10) + "#", 0L, "PS00");
            String billNo = "YX-CON-" + round;
            ConcurrentOutcome oc = runConcurrentRegister(billNo, 2100L + round, billNo, 2100L + round);
            assertEquals(1, oc.success.get(), "第" + round + "轮：同号并发只许一成");
            assertEquals(1, oc.rejected.get(), "第" + round + "轮：后到那笔必须被重号闸挡下");
        }
        assertEquals(20, records.size(), "每轮只落一张，库里不许出同号重单");
    }

    // 16. 并发：不同单号、但同机组同年，也只许一张落库（一机组一年一单）
    @Test
    void register_concurrentSameUnitYear_onlyOneWins() throws Exception {
        station(0L, "PS00", 0, "城东片区");
        // 每轮换新机组：两笔不同号同打一台，只有一张进得来
        for (int round = 0; round < 20; round++) {
            long uid = 2200L + round;
            unit(uid, (round + 20) + "#", 0L, "PS00");
            ConcurrentOutcome oc = runConcurrentRegister(
                    "YX-Y-A-" + round, uid, "YX-Y-B-" + round, uid);
            assertEquals(1, oc.success.get(), "第" + round + "轮：同机组同年并发只许一成");
            assertEquals(1, oc.rejected.get(), "第" + round + "轮：另一笔被一机组一年一单挡下");
        }
        assertEquals(20, records.size());
    }

    private ConcurrentOutcome runConcurrentRegister(String billA, long unitA,
                                                    String billB, long unitB) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (String bill : Arrays.asList(billA, billB)) {
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                    service.registerTDrainRunRecord(form(bill, unitA, "0.5"), price);
                    success.incrementAndGet();
                } catch (DrainArchiveException e) {
                    rejected.incrementAndGet();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();
        return new ConcurrentOutcome(success, rejected);
    }

    private static class ConcurrentOutcome {
        final AtomicInteger success;
        final AtomicInteger rejected;

        ConcurrentOutcome(AtomicInteger success, AtomicInteger rejected) {
            this.success = success;
            this.rejected = rejected;
        }
    }

    // ---- 编辑口径 ----

    // 17. 编辑老单、单号压根没动：保存必须放行，不许自己撞自己
    @Test
    void edit_billNoUnchanged_saved() {
        station(0L, "PS00", 0, "城东片区");
        unit(2001L, "1#", 0L, "PS00");
        TDrainRunRecord saved = register("YX-EDIT", 2001L, "0.5");

        TDrainRunRecord patch = new TDrainRunRecord();
        patch.setId(saved.getId());
        patch.setBillNo("YX-EDIT"); // 单号原样回显，没动
        patch.setRemark("补一句备注");
        int rows = service.updateTDrainRunRecord(patch);

        assertEquals(1, rows, "单号没动不该被重号闸拦，保存要成");
        TDrainRunRecord after = records.get(saved.getId());
        assertEquals("YX-EDIT", after.getBillNo());
        assertEquals("补一句备注", after.getRemark());
    }

    // 18. 编辑时单号字段干脆不带（null）：同样放行
    @Test
    void edit_billNoOmitted_saved() {
        station(0L, "PS00", 0, "城东片区");
        unit(2001L, "1#", 0L, "PS00");
        TDrainRunRecord saved = register("YX-EDIT2", 2001L, "0.5");

        TDrainRunRecord patch = new TDrainRunRecord();
        patch.setId(saved.getId());
        patch.setRemark("只改备注");
        assertEquals(1, service.updateTDrainRunRecord(patch));
        assertEquals("YX-EDIT2", records.get(saved.getId()).getBillNo(), "没带单号就维持原号");
    }

    // 19. 编辑时把手改单号改成已存在的号：必须拦下对档案
    @Test
    void edit_changeBillNo_duplicate_rejected() {
        station(0L, "PS00", 0, "城东片区");
        unit(2001L, "1#", 0L, "PS00");
        unit(2002L, "2#", 0L, "PS00");
        TDrainRunRecord a = register("YX-KEEP", 2001L, "0.5");
        register("YX-USED", 2002L, "0.5");

        TDrainRunRecord patch = new TDrainRunRecord();
        patch.setId(a.getId());
        patch.setBillNo("YX-USED"); // 手改成别人已用的号
        assertThrows(DrainArchiveException.class, () -> service.updateTDrainRunRecord(patch),
                "手改单号撞已有号，必须拦下");
        assertEquals("YX-KEEP", records.get(a.getId()).getBillNo(), "撞号保存不上，原号不动");
    }

    // 20. 编辑改成一个没人用的新号：放行
    @Test
    void edit_changeBillNo_fresh_saved() {
        station(0L, "PS00", 0, "城东片区");
        unit(2001L, "1#", 0L, "PS00");
        TDrainRunRecord saved = register("YX-OLDNO", 2001L, "0.5");

        TDrainRunRecord patch = new TDrainRunRecord();
        patch.setId(saved.getId());
        patch.setBillNo("YX-NEWNO");
        assertEquals(1, service.updateTDrainRunRecord(patch));
        assertEquals("YX-NEWNO", records.get(saved.getId()).getBillNo());
    }

    // 21. 编辑改时长：重校红线，费与档由系统照档案重算（上限含本档），更新者署当前登录人
    @Test
    void edit_qtyChanged_recalculatedAndStamped() {
        stationThresholds(0L, "1", "5", "10");
        unit(2001L, "1#", 0L, "PS0");
        currentUser = "duty_li";
        TDrainRunRecord saved = register("YX-RC", 2001L, "2");
        assertEquals(new BigDecimal("1.60"), saved.getFineAmt());

        currentUser = "duty_wang"; // 换个人来订正
        TDrainRunRecord patch = new TDrainRunRecord();
        patch.setId(saved.getId());
        patch.setQty(new BigDecimal("5")); // 压二档上限
        assertEquals(1, service.updateTDrainRunRecord(patch));

        TDrainRunRecord after = records.get(saved.getId());
        assertEquals(new BigDecimal("4.00"), after.getFineAmt(), "5 × 0.80 = 4.00，系统重算");
        assertEquals(2, after.getGradeLevel().intValue(), "压二档上限5仍归二档");
        assertEquals("duty_wang", after.getUpdateBy(), "谁改署谁名");
        assertNotNull(after.getUpdateTime());
        assertEquals("duty_li", after.getCreateBy(), "创建人不被编辑改写");
    }

    // 22. 编辑不存在/已删的单：返回 0，不凭空造
    @Test
    void edit_missing_returnsZero() {
        TDrainRunRecord patch = new TDrainRunRecord();
        patch.setId(999L);
        patch.setBillNo("YX-X");
        assertEquals(0, service.updateTDrainRunRecord(patch));
    }

    // 23. 编辑带非法时长（零/负）：照样退回
    @Test
    void edit_badQty_rejected() {
        station(0L, "PS00", 0, "城东片区");
        unit(2001L, "1#", 0L, "PS00");
        TDrainRunRecord saved = register("YX-BQ", 2001L, "0.5");
        TDrainRunRecord patch = new TDrainRunRecord();
        patch.setId(saved.getId());
        patch.setQty(BigDecimal.ZERO);
        assertThrows(DrainArchiveException.class, () -> service.updateTDrainRunRecord(patch));
    }

    // 24. 并发：两张不同老单同时改号成同一个新号，只许一张改成功（查重+落库同一锁）
    @Test
    void edit_concurrentChangeToSameBillNo_onlyOneWins() throws Exception {
        station(0L, "PS00", 0, "城东片区");
        unit(2001L, "1#", 0L, "PS00");
        unit(2002L, "2#", 0L, "PS00");
        long idA = register("YX-EA", 2001L, "0.5").getId();
        long idB = register("YX-EB", 2002L, "0.5").getId();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (long id : Arrays.asList(idA, idB)) {
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                    TDrainRunRecord patch = new TDrainRunRecord();
                    patch.setId(id);
                    patch.setBillNo("YX-SAME"); // 两笔都改成同一个号
                    if (service.updateTDrainRunRecord(patch) > 0) {
                        success.incrementAndGet();
                    }
                } catch (DrainArchiveException e) {
                    rejected.incrementAndGet();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();

        assertEquals(1, success.get(), "同一个新号并发改，只许一成");
        assertEquals(1, rejected.get(), "另一笔撞号必须退回");
        long onSameNo = records.values().stream()
                .filter(r -> "YX-SAME".equals(r.getBillNo())).count();
        assertEquals(1, onSameNo, "库里最终只能有一张挂着这个号");
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

    private static Long neLong(Map<String, List<Occ>> p, String column) {
        Object v = first(p, column, "<>");
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
