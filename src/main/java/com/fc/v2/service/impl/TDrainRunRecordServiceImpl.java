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
 * 1. 写入口只有「登记」一处（{@link #registerTDrainRunRecord}），校验全在服务层，
 *    页面不替登记下结论；登记成不成以返回为准；
 * 2. 一个机组一年一单：受录机组必填，运行年度取系统登记年（不信表单），
 *    同机组当年已有一张就不再开第二张，库唯一键 (unit_id, run_year) 并发兜底；
 * 3. 运行单号照档案对标准号 YX-运行年-机组ID（种子 YX-2026-2001 同口径）：
 *    人手填可以，但服务层必须对档案，对不上当场退回，手改值不许直接落库；
 *    单号一旦落下即是锚点，编辑只许原样带过，不许改；
 * 4. 受录机组必须挂在册未删且在用（status=0）的泵站：停用站新单一律不收，
 *    名下老单原样留存；登记页可选机组也只摆在 用站名下未删机组（{@link TDrainPumpUnitMapper}
 *    另有 registrable 口子），站号/站主键照机组所属站回填，表单手填的一律不信；
 * 5. 运行时长只许为正：负、零、数量级明显不对（超过全年小时数）当场退回，不进流水；
 * 6. 电耗费=时长×核算单价、评定档照档案三档上限折，两边都由系统出，
 *    表单就算带了 fine_amt/grade_level 也照系统值覆盖，人不动手；
 *    档案列是「考核一档/二档/三档上限」，上限含本档：qty 正压上限仍归本档，
 *    高出三档上限才归超档(4)，与起排判线「临界取高一档」是两把尺，不许混用；
 * 7. 创建人/更新人由服务层照当前登录人钉死，表单带 createBy/updateBy 一律覆盖
 *    （MyBatis-Plus 的 fillStrategy 只在字段为空时才填，实体整绑表单时不钉就会被人手值顶掉）；
 * 8. 台账只摆未删单，片区权限在 where 层经泵站档案裁：旁片区的单一页都翻不到。
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

    /** 运行单号档案前缀，标准号 YX-运行年-机组ID */
    private static final String BILL_PREFIX = "YX-";

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

        // 受录机组必填：一机组一年一单的锚点是机组，不是站；先把档案锚点拿稳，号/年费/档全照它对
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

        // 单号人手填、必填，但必须照着档案标准号来：运行年取系统年、机组取受录机组，
        // 拼出的 YX-年-机组ID 才是正号，表单手改的旁号当场退回，两边口径不许分叉
        if (record.getBillNo() == null || record.getBillNo().trim().isEmpty()) {
            throw new DrainArchiveException("运行单号必须人手填写，不许空号");
        }
        int runYear = LocalDate.now().getYear();
        String canonicalBillNo = buildBillNo(runYear, unit.getId());
        String billNo = record.getBillNo().trim();
        if (!canonicalBillNo.equals(billNo)) {
            throw new DrainArchiveException("运行单号[" + billNo + "]与档案口径对不上："
                    + "机组[" + unit.getUnitNo() + "]" + runYear + "年的正号是[" + canonicalBillNo
                    + "]，手改旁号不收");
        }

        Integer yearDup = this.baseMapper.selectCount(new QueryWrapper<TDrainRunRecord>()
                .eq("unit_id", unit.getId()).eq("run_year", runYear).eq("del_flag", 0));
        if (yearDup != null && yearDup > 0) {
            throw new DrainArchiveException("机组[" + unit.getUnitNo() + "]" + runYear
                    + "年已有一张运行记录单，一机组一年只许一张");
        }

        BigDecimal price = powerPrice != null ? powerPrice : DEFAULT_POWER_PRICE;
        // 系统核算两边都由系统出：先清空表单可能带来的值，再照系统口径算，人不动手
        record.setBillNo(canonicalBillNo);
        record.setUnitId(unit.getId());
        record.setSiteId(station.getId());
        record.setSiteNo(station.getSiteNo());
        record.setRunYear(runYear);
        record.setFineAmt(qty.multiply(price).setScale(MONEY_SCALE, RoundingMode.HALF_UP));
        record.setGradeLevel(gradeOf(station, qty));

        // 新录即待复核、正常单：显式落记号，不靠列默认值
        record.setStatus(STATUS_WAIT);
        record.setDelFlag(0);
        // 落字的人照当前登录人钉死：整实体绑表单时 fillStrategy 见字段非空就不填，
        // 不显式覆盖，createBy 会被请求里的手填值顶成别人
        record.setCreateBy(currentOperator());
        record.setCreateTime(new Date());
        try {
            this.baseMapper.insert(record);
        } catch (DuplicateKeyException dupKey) {
            // 库唯一键兜底并发：同号或同机组同年两道闸，后到的这笔当场退回
            throw new DrainArchiveException("同号或同机组当年已有单据（并发撞单），本单退回");
        }
        return record;
    }

    @Override
    public int updateTDrainRunRecord(TDrainRunRecord record) {
        if (record == null || record.getId() == null) {
            return 0;
        }
        TDrainRunRecord exists = this.baseMapper.selectOne(new QueryWrapper<TDrainRunRecord>()
                .eq("id", record.getId()).eq("del_flag", 0));
        if (exists == null) {
            return 0;
        }
        TDrainStation station = stationByNo(exists.getSiteNo());
        if (station == null) {
            throw new DrainArchiveException("老单所挂泵站档案已不在册，单子改不动");
        }
        drainAreaScope.assertRoad(station.getRoadName());

        // 单号是照档案钉死的锚点：编辑时没动就原样放过，不许再拿 ne(id) 查重——
        // 历史上并发落下的同号重单会让「压根没动号」的保存也被误伤拦回；
        // 真改了号则当场退回，要另开请走登记
        if (record.getBillNo() != null && !record.getBillNo().trim().isEmpty()
                && !record.getBillNo().trim().equals(exists.getBillNo())) {
            throw new DrainArchiveException("运行单号[" + exists.getBillNo()
                    + "]照档案钉死不许改；要另开请登记新单");
        }

        // 只放行业务可改列（时长、备注），另起 patch 落库：
        // unit/site/year/status/del_flag/createBy 这些锚点与系统列，表单带了也一概不进门
        TDrainRunRecord patch = new TDrainRunRecord();
        patch.setId(exists.getId());
        if (record.getQty() != null) {
            BigDecimal qty = record.getQty();
            if (qty.compareTo(BigDecimal.ZERO) <= 0) {
                throw new DrainArchiveException("机组实际运行时长必须大于0，负值/零值当场退回");
            }
            if (qty.compareTo(MAX_YEAR_HOURS) > 0) {
                throw new DrainArchiveException("运行时长" + qty.toPlainString()
                        + "小时数量级明显不对（超过全年小时数），当场退回");
            }
            // 时长改了，费与档照档案重算，仍由系统说了算（站即便后来停用，名下老单照其档案阈值回算）
            BigDecimal price = currentPowerPrice();
            patch.setQty(qty);
            patch.setFineAmt(qty.multiply(price).setScale(MONEY_SCALE, RoundingMode.HALF_UP));
            patch.setGradeLevel(gradeOf(station, qty));
        }
        if (record.getRemark() != null) {
            patch.setRemark(record.getRemark());
        }
        patch.setUpdateBy(currentOperator());
        patch.setUpdateTime(new Date());
        return this.baseMapper.updateById(patch);
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

    /** 运行单号档案标准号：YX-运行年-机组ID（与 drain_run_seed.sql 的 YX-2026-2001 同口径）。 */
    private static String buildBillNo(int runYear, Long unitId) {
        return BILL_PREFIX + runYear + "-" + unitId;
    }

    /** 当前落字人：照 Shiro 登录名；无安全上下文（定时/单测）落 Task，与自动填充兜底一致。 */
    private static String currentOperator() {
        try {
            return ShiroUtils.getLoginName();
        } catch (RuntimeException e) {
            return "Task";
        }
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

    /**
     * 照档案阈值定运行评定档（考核口径，base=1，与 drain_seed.sql 档案三档上限同口径）：
     * 列名是「本档上限」，上限含本档——qty 正压着某档上限仍归该档；
     * qty 落在 (上一档上限, 本档上限] 取本档，高出三档上限归超档(4)；
     * 某档上限为 NULL 表示该档未划线，顺次往下着；读数缺失归 0。
     * 注意这把尺与起排判线 levelOf 不同：判线是水位警戒，压线取高一档；
     * 评定档是考核分档，压线留本档，两处各依各的业务，不许混用。
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
