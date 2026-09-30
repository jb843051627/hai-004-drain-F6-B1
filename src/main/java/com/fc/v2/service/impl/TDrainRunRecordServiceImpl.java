package com.fc.v2.service.impl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fc.v2.common.conf.V2Config;
import com.fc.v2.common.dataSources.DrainAreaScope;
import com.fc.v2.common.exception.DrainArchiveException;
import com.fc.v2.common.support.ConvertUtil;
import com.fc.v2.mapper.auto.TDrainPumpUnitMapper;
import com.fc.v2.mapper.auto.TDrainRunRecordMapper;
import com.fc.v2.mapper.auto.TDrainStationMapper;
import com.fc.v2.model.auto.TDrainPumpUnit;
import com.fc.v2.model.auto.TDrainRunRecord;
import com.fc.v2.model.auto.TDrainStation;
import com.fc.v2.service.ITDrainRunRecordService;
import com.fc.v2.shiro.util.ShiroUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

/**
 * 机组运行记录单Service业务层处理
 *
 * 口径：
 * 1. 写入口有两处——「登记」{@link #registerTDrainRunRecord} 开新单、
 *    「编辑」{@link #updateTDrainRunRecord} 改老单；校验全在服务层，页面不替登记下结论；
 *    单号由人手填，登记成不成以返回为准；
 * 2. 一个机组一年一单：受录机组必填，运行年度取系统登记年（不信表单），
 *    同机组当年已有一张就不再开第二张；查重与插入在同一把条带锁内，同号/同机组同年
 *    并发两笔只有一笔进得来，库唯一键 (unit_id, run_year)、uk_bill_no 再多兜一道；
 * 3. 受录机组必须挂在册未删泵站，泵站在用（已停用不收，名下老单原样留存、可改）；
 *    站号/站主键照机组所属站回填，表单手填的一律不信；登记页选机组走 registerable，
 *    停用站名下机组压根不进下拉，服务层拦截再兜一道；
 * 4. 运行时长只许为正：负、零、数量级明显不对（超过全年小时数）当场退回，不进流水；
 * 5. 电耗费=时长×核算单价、评定档照档案三档上限折，两边都由系统出，
 *    表单就算带了 fine_amt/grade_level 也照系统值覆盖，人不动手；
 *    运行评定档口径是「上限含本档」：时长恰好等于某档上限仍归本档，超过才掉下一档，
 *    跟起排判线的「压线取高」是两把尺，各照各的业务口径，不共用；
 * 6. 创建者/更新者只认此刻登录人，由服务层显式落字——表单夹带的 createBy/updateBy
 *    （比如页面占位名「片区运行员」）一律覆盖，谁提交署谁的名；
 * 7. 台账只摆未删单，片区权限在 where 层经泵站档案裁：旁片区的单一页都翻不到。
 *
 * @author fuce
 * @date 2026-09-12
 */
@Service
public class TDrainRunRecordServiceImpl extends ServiceImpl<TDrainRunRecordMapper, TDrainRunRecord> implements ITDrainRunRecordService {

    /** 记录单状态 0待复核 1已复核 2已封存，新录一律落待复核 */
    private static final int STATUS_WAIT = 0;

    /** 档案/机组停用记号 */
    private static final int DISABLED = 1;

    /** 运行时长数量级上限（全年小时数，闰年）：超过就是数量级明显不对，当场退回 */
    private static final BigDecimal MAX_YEAR_HOURS = new BigDecimal("8784");

    /** 电耗费保留两位小数 */
    private static final int MONEY_SCALE = 2;

    /** 未配置核算单价时的兜底（元/运行小时） */
    private static final BigDecimal DEFAULT_POWER_PRICE = new BigDecimal("0.80");

    /** 查重-插入临界区条带数：同号/同机组同年的两笔必落同一把锁，不同键各走各的不互堵 */
    private static final int LOCK_STRIPES = 64;

    /** 登记并发条带锁：锁的是「查重+插入」这一小段，粒度细、持锁短 */
    private final Object[] registerLocks = new Object[LOCK_STRIPES];

    {
        for (int i = 0; i < LOCK_STRIPES; i++) {
            registerLocks[i] = new Object();
        }
    }

    @Resource
    private TDrainStationMapper drainStationMapper;

    @Resource
    private TDrainPumpUnitMapper drainPumpUnitMapper;

    @Resource
    private DrainAreaScope drainAreaScope;

    @Resource
    private V2Config v2Config;

    @Override
    public TDrainRunRecord selectTDrainRunRecordById(Long id) {
        TDrainRunRecord r = this.baseMapper.selectOne(new QueryWrapper<TDrainRunRecord>()
                .eq("id", id)
                .eq("del_flag", 0));
        if (r == null) {
            return null;
        }
        TDrainStation station = stationByNo(r.getSiteNo());
        if (station == null || !canReadRoad(station.getRoadName())) {
            return null;
        }
        return r;
    }

    @Override
    public List<TDrainRunRecord> selectTDrainRunRecordList(Wrapper<TDrainRunRecord> queryWrapper) {
        QueryWrapper<TDrainRunRecord> wrapper = queryWrapper instanceof QueryWrapper
                ? (QueryWrapper<TDrainRunRecord>) queryWrapper
                : new QueryWrapper<TDrainRunRecord>();
        // 台账只摆未删单；状态口径（待复核/已复核/已封存）交调用方按筛选条件决定，不在这里硬塞
        wrapper.eq("del_flag", 0);
        // 片区钉进 where：本表无片区列，经泵站档案子查询裁；授权空集 1=0 裁空
        DrainAreaScope.restrictByStationRoads(wrapper, "site_no", drainAreaScope.roadNamesOrNull());
        return this.baseMapper.selectList(wrapper);
    }

    @Override
    public int insertTDrainRunRecord(TDrainRunRecord record) {
        registerTDrainRunRecord(record, currentPowerPrice());
        return 1;
    }

    @Override
    public TDrainRunRecord registerTDrainRunRecord(TDrainRunRecord record, BigDecimal powerPrice) {
        if (record == null) {
            throw new DrainArchiveException("登记内容为空");
        }
        // 单号人手填、必填，服务层校重号
        if (record.getBillNo() == null || record.getBillNo().trim().isEmpty()) {
            throw new DrainArchiveException("运行单号必须人手填写，不许空号");
        }
        String billNo = record.getBillNo().trim();

        // 受录机组必填：一机组一年一单的锚点是机组，不是站
        if (record.getUnitId() == null) {
            throw new DrainArchiveException("请选择受录机组，一机组一年一单");
        }
        TDrainPumpUnit unit = this.drainPumpUnitMapper.selectById(record.getUnitId());
        if (unit == null || (unit.getDelFlag() != null && unit.getDelFlag() == 1)) {
            throw new DrainArchiveException("受录机组档案不存在或已删除，开不了单");
        }
        TDrainStation station = this.drainStationMapper.selectById(unit.getSiteId());
        if (station == null || (station.getDelFlag() != null && station.getDelFlag() == 1)) {
            throw new DrainArchiveException("机组所属泵站档案不存在，开不了单");
        }
        if (station.getStatus() != null && station.getStatus() == DISABLED) {
            // 停用泵站不许新单挂它；名下老单原样留存，只挡新开
            throw new DrainArchiveException("泵站[" + station.getSiteNo()
                    + "]已停用，新运行记录单不许再挂它；名下老单原样留存");
        }
        // 片区权限：旁片区的站，片区人开单时在服务层就被挡，不是页面藏
        drainAreaScope.assertRoad(station.getRoadName());

        // 运行时长：负、零、数量级离谱当场退回，别放进流水
        BigDecimal qty = record.getQty();
        validateQty(qty);

        // 运行年度取系统登记年，表单给的不信
        int runYear = LocalDate.now().getYear();
        BigDecimal price = powerPrice != null ? powerPrice : DEFAULT_POWER_PRICE;

        // 两道重号闸（同号、同机组同年）的查重与插入放进同一临界区：
        // 先查后插本身拦不住并发两笔，条带锁把同键的两笔串成一前一后，
        // 后到那笔在锁内就看得见先进来的单，当场退回，不靠撞库键才发现。
        withBillLocks(billNo, "u" + unit.getId() + "y" + runYear, () -> {
            Integer billDup = this.baseMapper.selectCount(new QueryWrapper<TDrainRunRecord>()
                    .eq("bill_no", billNo).eq("del_flag", 0));
            if (billDup != null && billDup > 0) {
                throw new DrainArchiveException("运行单号[" + billNo + "]已存在，单号人手填也不许重");
            }
            Integer yearDup = this.baseMapper.selectCount(new QueryWrapper<TDrainRunRecord>()
                    .eq("unit_id", unit.getId()).eq("run_year", runYear).eq("del_flag", 0));
            if (yearDup != null && yearDup > 0) {
                throw new DrainArchiveException("机组[" + unit.getUnitNo() + "]" + runYear
                        + "年已有一张运行记录单，一机组一年只许一张");
            }

            // 系统核算两边都由系统出：先清空表单可能带来的值，再照系统口径算，人不动手
            record.setBillNo(billNo);
            record.setUnitId(unit.getId());
            record.setSiteId(station.getId());
            record.setSiteNo(station.getSiteNo());
            record.setRunYear(runYear);
            record.setQty(qty);
            record.setFineAmt(qty.multiply(price).setScale(MONEY_SCALE, RoundingMode.HALF_UP));
            record.setGradeLevel(gradeOf(station, qty));

            // 新录即待复核、正常单：显式落记号，不靠列默认值
            record.setStatus(STATUS_WAIT);
            record.setDelFlag(0);
            // 谁提交署谁的名：登录人由服务层显式落，表单夹带的占位名一律盖掉
            record.setCreateBy(currentLoginName());
            record.setUpdateBy(null);
            record.setCreateTime(new Date());
            record.setUpdateTime(null);
            try {
                this.baseMapper.insert(record);
            } catch (DuplicateKeyException dupKey) {
                // 库唯一键兜底并发（多实例部署条带锁管不到时）：同号或同机组同年两道闸，后到的这笔当场退回
                throw new DrainArchiveException("同号或同机组当年已有单据（并发撞单），本单退回");
            }
        });
        return record;
    }

    @Override
    public int updateTDrainRunRecord(TDrainRunRecord record) {
        if (record == null || record.getId() == null) {
            return 0;
        }
        TDrainRunRecord exists = this.baseMapper.selectById(record.getId());
        if (exists == null || (exists.getDelFlag() != null && exists.getDelFlag() == 1)) {
            // 老单不存在/已删：无单可改
            return 0;
        }
        // 片区写权限照老单所属站过：旁片区的人翻不到也改不动
        TDrainStation station = stationByNo(exists.getSiteNo());
        if (station == null) {
            throw new DrainArchiveException("老单所属泵站档案已不在册，交人工核对，不直接改");
        }
        drainAreaScope.assertRoad(station.getRoadName());

        // 单号：没动（没带或跟原号一致）原样留，绝不自己撞自己；手改了才对未删单校重号
        String oldBillNo = exists.getBillNo();
        String newBillNo = record.getBillNo() == null ? null : record.getBillNo().trim();
        boolean billChanged = newBillNo != null && !newBillNo.isEmpty() && !newBillNo.equals(oldBillNo);

        // 机组/站/年度是这张单的身份锚点，编辑不许挪；状态/删除标记也不走这个口子
        // 时长带了就重校、重算费与档（档案三档上限照此刻在册值折）；没带维持原值
        if (record.getQty() != null) {
            BigDecimal qty = record.getQty();
            validateQty(qty);
            exists.setQty(qty);
            exists.setFineAmt(qty.multiply(currentPowerPrice()).setScale(MONEY_SCALE, RoundingMode.HALF_UP));
            exists.setGradeLevel(gradeOf(station, qty));
        }
        if (record.getRemark() != null) {
            String remark = record.getRemark().trim();
            exists.setRemark(remark.isEmpty() ? null : remark);
        }
        // 更新者只认此刻登录人，不信表单夹带的 updateBy
        exists.setUpdateBy(currentLoginName());
        exists.setUpdateTime(new Date());

        // 改号的查重与落库收在同一把锁内：与并发登记/另一笔改号在同号条带上互斥，
        // 后到那笔锁内查重就看得见先进来的号，当场退回；多实例再由库唯一键兜一道。
        if (billChanged) {
            final String billNo = newBillNo;
            return withLockReturning(billNo, () -> {
                Integer dupCnt = this.baseMapper.selectCount(new QueryWrapper<TDrainRunRecord>()
                        .eq("bill_no", billNo).ne("id", exists.getId()).eq("del_flag", 0));
                if (dupCnt != null && dupCnt > 0) {
                    throw new DrainArchiveException("运行单号[" + billNo + "]与别的单重号");
                }
                exists.setBillNo(billNo);
                return doUpdate(exists);
            });
        }
        return doUpdate(exists);
    }

    private int doUpdate(TDrainRunRecord exists) {
        try {
            return this.baseMapper.updateById(exists);
        } catch (DuplicateKeyException dupKey) {
            // 库唯一键兜底（多实例部署条带锁管不到时）：本笔退回，不抹先进来的号
            throw new DrainArchiveException("运行单号改后与别的单撞号（并发），本单退回");
        }
    }

    @Override
    public int deleteTDrainRunRecordByIds(String ids) {
        Long[] idArr = ConvertUtil.toLongArray(ids);
        return this.baseMapper.deleteBatchIds(Arrays.asList(idArr));
    }

    @Override
    public int deleteTDrainRunRecordById(Long id) {
        return this.baseMapper.deleteById(id);
    }

    private BigDecimal currentPowerPrice() {
        if (v2Config != null && v2Config.getDrainPowerPrice() != null) {
            return v2Config.getDrainPowerPrice();
        }
        return DEFAULT_POWER_PRICE;
    }

    private TDrainStation stationByNo(String siteNo) {
        if (siteNo == null || siteNo.trim().isEmpty()) {
            return null;
        }
        return this.drainStationMapper.selectOne(new QueryWrapper<TDrainStation>()
                .eq("site_no", siteNo).eq("del_flag", 0));
    }

    /** 片区读权限。 */
    private boolean canReadRoad(String roadName) {
        List<String> roads = drainAreaScope.roadNamesOrNull();
        if (roads == null) {
            return true;
        }
        return roadName != null && roads.contains(roadName.trim());
    }

    /** 运行时长红线：必填、为正、不超过全年小时数。 */
    private void validateQty(BigDecimal qty) {
        if (qty == null) {
            throw new DrainArchiveException("机组实际运行时长必须填写");
        }
        if (qty.compareTo(BigDecimal.ZERO) <= 0) {
            throw new DrainArchiveException("机组实际运行时长必须大于0，负值/零值当场退回");
        }
        if (qty.compareTo(MAX_YEAR_HOURS) > 0) {
            throw new DrainArchiveException("运行时长" + qty.toPlainString()
                    + "小时数量级明显不对（超过全年小时数），当场退回");
        }
    }

    /**
     * 当前登录人：谁提交就署谁的名。无安全上下文（定时任务/未起容器的单测）返回 null，
     * 不替系统调用安个人名；包成 protected 方法，单测可覆写造身份，不依赖 mockStatic。
     */
    protected String currentLoginName() {
        try {
            String name = ShiroUtils.getLoginName();
            return name == null || name.trim().isEmpty() ? null : name.trim();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 取两把条带锁跑临界区（按锁序号排序后依次进入，避免交叉死锁）；
     * 只给一个键时第二把不取。key 为 null 表示这道闸本轮不关（如编辑只改单号不涉及机组年）。
     */
    private void withBillLocks(String key1, String key2, RunnableEx action) {
        int s1 = stripeOf(key1);
        int s2 = key2 == null ? -1 : stripeOf(key2);
        int lo = s2 < 0 ? s1 : Math.min(s1, s2);
        int hi = s2 < 0 ? s1 : Math.max(s1, s2);
        synchronized (registerLocks[lo]) {
            if (hi == lo) {
                action.run();
                return;
            }
            synchronized (registerLocks[hi]) {
                action.run();
            }
        }
    }

    private static int stripeOf(String key) {
        return Math.floorMod(key.hashCode(), LOCK_STRIPES);
    }

    /** 单号条带锁内执行并拿返回值（编辑改号：查重+落库同一把锁）。 */
    private <T> T withLockReturning(String key, CallableEx<T> action) {
        synchronized (registerLocks[stripeOf(key)]) {
            return action.call();
        }
    }

    /** 临界区内允许抛业务异常、有返回值的小函数式接口。 */
    private interface CallableEx<T> {
        T call();
    }

    /** 临界区内允许抛业务异常的小函数式接口。 */
    private interface RunnableEx {
        void run();
    }

    /**
     * 照档案阈值定运行评定档（运行考核口径：上限含本档，base=1）：
     * qty ≤ th1Max 归一档，th1Max &lt; qty ≤ th2Max 归二档，th2Max &lt; qty ≤ th3Max 归三档，
     * 高出三档上限归四档——时长恰好敲在上限仍归本档，不掉档。
     * 某档上限为 NULL 表示该档未划线，顺次往下着。
     * 注意与起排判线 {@code TDrainLevelStdServiceImpl.levelOf} 的区别：那是水位起排口径，
     * 临界（压线）取高一档；这是运行时长考核口径，上限就是本档天花板、压线仍在本档。
     * 两边业务口径不同，不许强行并成一把尺。读数缺失归 0。
     */
    private int gradeOf(TDrainStation arch, BigDecimal qty) {
        if (qty == null) {
            return 0;
        }
        if (arch.getTh1Max() != null && qty.compareTo(arch.getTh1Max()) <= 0) {
            return 1;
        }
        if (arch.getTh2Max() != null && qty.compareTo(arch.getTh2Max()) <= 0) {
            return 2;
        }
        if (arch.getTh3Max() != null && qty.compareTo(arch.getTh3Max()) <= 0) {
            return 3;
        }
        return 4;
    }
}
