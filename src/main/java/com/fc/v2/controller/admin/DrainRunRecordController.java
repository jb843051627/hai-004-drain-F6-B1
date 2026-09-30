package com.fc.v2.controller.admin;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.common.base.BaseController;
import com.fc.v2.common.domain.AjaxResult;
import com.fc.v2.common.domain.ResultTable;
import com.fc.v2.common.log.Log;
import com.fc.v2.model.auto.TDrainPumpUnit;
import com.fc.v2.model.auto.TDrainRunRecord;
import com.fc.v2.service.ITDrainPumpUnitService;
import com.fc.v2.service.ITDrainRunRecordService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.ModelMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * 机组运行记录单 Controller（batch-process 形状：写入口只有「登记」一处）
 *
 * 页面不替登记下结论：单号人手填，但须照档案标准号 YX-运行年-机组ID，服务层对档案校验；
 * 受录机组只从 /registrableUnits 取（停用站名下机组不在其列）；
 * 电耗费与评定档由系统照档案算，页面提交不带这两列、也不预判，登记以返回为准；
 * 创建人照当前登录人落。老单修改走服务层 update（只放时长/备注），单号钉死不许改。
 *
 * @author fuce
 * @date 2026-09-12
 */
@Api(value = "机组运行记录单")
@Controller
@RequestMapping("/DrainRunRecordController")
public class DrainRunRecordController extends BaseController {

    private final String prefix = "admin/drainRunRecord";

    @Autowired
    private ITDrainRunRecordService drainRunRecordService;

    @Autowired
    private ITDrainPumpUnitService drainPumpUnitService;

    @ApiOperation(value = "分页跳转", notes = "分页跳转")
    @GetMapping("/view")
    @RequiresPermissions("drain:drainRunRecord:view")
    public String view(ModelMap model) {
        return prefix + "/list";
    }

    @Log(title = "可登记机组下拉", action = "list")
    @ApiOperation(value = "可登记机组", notes = "登记页选机组只取这个口子：只摆在册未删且在用泵站名下机组；"
            + "泵站档案一停用，名下机组当场从下拉里消失，停用状态与登记在服务层联动")
    @GetMapping("/registrableUnits")
    @RequiresPermissions("drain:drainRunRecord:add")
    @ResponseBody
    public AjaxResult registrableUnits(TDrainPumpUnit query) {
        QueryWrapper<TDrainPumpUnit> wrapper = new QueryWrapper<>();
        if (query.getSiteNo() != null && !query.getSiteNo().trim().isEmpty()) {
            wrapper.eq("site_no", query.getSiteNo().trim());
        }
        if (query.getUnitNo() != null && !query.getUnitNo().trim().isEmpty()) {
            wrapper.eq("unit_no", query.getUnitNo().trim());
        }
        wrapper.orderByAsc("site_no", "unit_no", "id");
        return AjaxResult.successData(200, drainPumpUnitService.selectRegistrableUnitList(wrapper));
    }

    @Log(title = "机组运行记录单集合查询", action = "list")
    @ApiOperation(value = "分页查询", notes = "台账只摆未删单；按片区数据权限在服务层 where 里裁，旁片区一页都翻不到；"
            + "按创建时刻+主键倒序，翻页排列稳")
    @GetMapping("/list")
    @RequiresPermissions("drain:drainRunRecord:list")
    @ResponseBody
    public ResultTable list(TDrainRunRecord record) {
        QueryWrapper<TDrainRunRecord> queryWrapper = new QueryWrapper<TDrainRunRecord>();
        // 状态/单号/站号/年度筛选由页面给；不筛选时待复核/已复核/已封存都摆（服务层只兜底未删+片区）
        if (record.getStatus() != null) {
            queryWrapper.eq("status", record.getStatus());
        }
        if (record.getBillNo() != null && !record.getBillNo().trim().isEmpty()) {
            queryWrapper.like("bill_no", record.getBillNo().trim());
        }
        if (record.getSiteNo() != null && !record.getSiteNo().trim().isEmpty()) {
            queryWrapper.eq("site_no", record.getSiteNo().trim());
        }
        if (record.getRunYear() != null) {
            queryWrapper.eq("run_year", record.getRunYear());
        }
        // 时刻外再钉主键收尾，同一时刻落两笔也不会翻页串行
        queryWrapper.orderByDesc("create_time", "id");
        startPage();
        com.github.pagehelper.PageInfo<TDrainRunRecord> page =
                new com.github.pagehelper.PageInfo<>(drainRunRecordService.selectTDrainRunRecordList(queryWrapper));
        return pageTable(page.getList(), page.getTotal());
    }

    @Log(title = "机组运行记录单登记", action = "register")
    @ApiOperation(value = "登记", notes = "唯一写入口：单号人手填但必须照档案标准号 YX-运行年-机组ID（服务层对档案，"
            + "手改旁号当场退回）；一机组一年一单；受录机组取自 /registrableUnits（停用站名下机组不在其列）；"
            + "时长须为正且数量级正常；电耗费、评定档由系统照档案核算，页面不动手，登记结果以返回为准；"
            + "创建人照当前登录人落，表单带 createBy 不认")
    @PostMapping("/register")
    @RequiresPermissions("drain:drainRunRecord:add")
    @ResponseBody
    public AjaxResult register(TDrainRunRecord record) {
        // 这里不校验、不预判、不替登记下结论：原样交服务层，收不收、算出多少费与档全听服务层返回
        TDrainRunRecord saved = drainRunRecordService.registerTDrainRunRecord(
                record, v2Config.getDrainPowerPrice());
        return AjaxResult.success(200, "登记成功，电耗费与评定档以系统核算为准", saved);
    }
}
