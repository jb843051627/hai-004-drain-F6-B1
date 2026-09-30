-- drain 泵站档案种子（id=0 在用、id=1 已停用，供档案联动与停用引用场景使用）
-- 三级阈值 1/5/10 与运行评定档口径一致（考核分档：列名「本档上限」，压线归本档、base=1）；
-- 注意与起排判线（水位警戒，压线取高一档）是两把尺，别拿判线口径对评定档
INSERT INTO t_drain_station (id, site_no, site_name, site_type, road_name, th1_max, th2_max, th3_max, status, del_flag, create_by, create_time)
VALUES
 (0, 'PS00', '临湖一号泵站', '雨水', '城东片区', 1.00, 5.00, 10.00, 0, 0, 'seed', NOW()),
 (1, 'PS01', '老闸口泵站', '合流', '城西片区', 1.00, 5.00, 10.00, 1, 0, 'seed', NOW())
ON DUPLICATE KEY UPDATE site_name = VALUES(site_name), th1_max = VALUES(th1_max),
 th2_max = VALUES(th2_max), th3_max = VALUES(th3_max), status = VALUES(status), del_flag = 0;
