package com.fc.v2.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fc.v2.model.auto.TDrainPumpUnit;

import java.util.List;

/**
 * 泵站机组档案 Service接口
 *
 * @author fuce
 * @date 2026-09-29
 */
public interface ITDrainPumpUnitService {

    /** 按主键查询（已删/跨片区查不到） */
    TDrainPumpUnit selectTDrainPumpUnitById(Long id);

    /** 机组台账：条件由调用方给，片区权限服务层钉死 */
    List<TDrainPumpUnit> selectTDrainPumpUnitList(Wrapper<TDrainPumpUnit> queryWrapper);

    /**
     * 可登记机组：只摆「在用」泵站名下未删机组。
     * 运行记录单选机组走这个口子——泵站档案一旦停用，名下机组立刻从可选集消失，
     * 不必等页面藏，停用状态与登记在服务层联动。片区权限照常在 where 层裁。
     */
    List<TDrainPumpUnit> selectRegistrableUnitList(Wrapper<TDrainPumpUnit> queryWrapper);

    /** 新增机组 */
    int insertTDrainPumpUnit(TDrainPumpUnit unit);

    /** 编辑机组 */
    int updateTDrainPumpUnit(TDrainPumpUnit unit);
}
