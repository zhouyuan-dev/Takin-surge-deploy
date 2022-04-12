/*
 * Copyright 2021 Shulie Technology, Co.Ltd
 * Email: shulie@shulie.io
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.shulie.surge.data.deploy.pradar;

import com.google.common.collect.Maps;
import com.google.inject.Inject;
import com.google.inject.Injector;
import io.shulie.surge.data.common.aggregation.AggregateSlot;
import io.shulie.surge.data.common.aggregation.Aggregation;
import io.shulie.surge.data.common.aggregation.Scheduler;
import io.shulie.surge.data.common.aggregation.metrics.CallStat;
import io.shulie.surge.data.common.aggregation.metrics.Metric;
import io.shulie.surge.data.common.utils.FormatUtils;
import io.shulie.surge.data.common.utils.Pair;
import io.shulie.surge.data.deploy.pradar.common.PradarRtConstant;
import io.shulie.surge.data.deploy.pradar.common.PradarStormConfigHolder;
import io.shulie.surge.data.deploy.pradar.common.StringUtil;
import io.shulie.surge.data.deploy.pradar.config.PradarSupplierConfiguration;
import io.shulie.surge.data.sink.influxdb.InfluxDBSupport;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.storm.task.TopologyContext;
import org.apache.storm.topology.BasicOutputCollector;
import org.apache.storm.topology.OutputFieldsDeclarer;
import org.apache.storm.topology.base.BaseBasicBolt;
import org.apache.storm.tuple.Tuple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PradarTrace2ReduceBolt extends BaseBasicBolt {
    private static Logger logger = LoggerFactory.getLogger(PradarTrace2ReduceBolt.class);
    protected transient Aggregation<Metric, CallStat> aggregation;

    @Inject
    private InfluxDBSupport influxDbSupport;

    private transient Scheduler scheduler;

    @Override
    public void declareOutputFields(OutputFieldsDeclarer outputFieldsDeclarer) {

    }

    @Override
    public void execute(Tuple input, BasicOutputCollector basicOutputCollector) {
        if (!input.getSourceStreamId().equals(PradarRtConstant.REDUCE_TRACE_METRICS_2_STREAM_ID)) {
            return;
        }
        Long slotKey = input.getLong(0);
        List<Pair<Metric, CallStat>> job = (List<Pair<Metric, CallStat>>) input.getValue(1);
        AggregateSlot<Metric, CallStat> slot = aggregation.getSlotByTimestamp(slotKey);
        if (slot != null) {
            for (Pair<Metric, CallStat> pair : job) {
                slot.addToSlot(pair.getFirst(), pair.getSecond());
            }
        } else {
            logger.info("no slot for " + slotKey + ": " + FormatUtils.toSecondTimeString(slotKey * 1000));
        }
    }

    @Override
    public void prepare(Map stormConf, TopologyContext context) {
        try {
            PradarStormConfigHolder.init(stormConf);
            Injector injector = new PradarSupplierConfiguration(context.getThisWorkerPort()).initDataRuntime().getInstance(Injector.class);
            influxDbSupport = injector.getInstance(InfluxDBSupport.class);

            scheduler = new Scheduler(1);
            aggregation = new Aggregation(PradarRtConstant.REDUCE_TRACE_SECONDS_INTERVAL,
                    PradarRtConstant.REDUCE_TRACE_SECONDS_LOWER_LIMIT);

            aggregation.start(scheduler, new TraceMetrics2CommitAction(influxDbSupport));
        } catch (Exception e) {
            logger.error("PradarTraceReduceBolt fail " + ExceptionUtils.getStackTrace(e));
            throw new RuntimeException(e);
        }
        logger.info("PradarTraceReduceBolt started...");
    }

    static class TraceMetrics2CommitAction implements Aggregation.CommitAction<Metric, CallStat> {
        private static Logger logger = LoggerFactory.getLogger(TraceMetrics2CommitAction.class);

        private InfluxDBSupport influxDbSupport;

        public TraceMetrics2CommitAction(InfluxDBSupport influxDbSupport) {
            this.influxDbSupport = influxDbSupport;
        }

        @Override
        public void commit(long slotKey, AggregateSlot<Metric, CallStat> slot) {
            try {
                slot.toMap().entrySet().forEach(metricCallStatEntry -> {
                    Metric metric = metricCallStatEntry.getKey();
                    CallStat callStat = metricCallStatEntry.getValue();
                    String[] tags = metric.getPrefixes();
                    Map<String, String> influxdbTags = Maps.newHashMap();
                    influxdbTags.put("clusterTest", tags[0]);
                    influxdbTags.put("appName", StringUtil.formatString(tags[1]));
                    //放入租户标识
                    influxdbTags.put("tenantAppKey", tags[2]);
                    //放入环境标识
                    influxdbTags.put("envCode", tags[3]);

                    influxdbTags.put("hostIp",tags[4]);
                    influxdbTags.put("agentId",tags[5]);

                    // 总次数/成功次数/totalRt/错误次数/hitCount/totalQps/totalTps/总次数(不计算采样率)/e2e成功次数/e2e失败次数/maxRt
                    Map<String, Object> fields = Maps.newHashMap();
                    fields.put("totalCount", callStat.get(0));
                    fields.put("successCount", callStat.get(1));
                    fields.put("totalRt", callStat.get(2));
                    fields.put("errorCount", callStat.get(3));
                    fields.put("hitCount", callStat.get(4));
                    fields.put("totalTps", callStat.get(5));
                    fields.put("total", callStat.get(6));
                    fields.put("maxRt", callStat.get(7));
                    //计算平均耗时
                    if (callStat.get(0) == 0) {
                        // 如果总调用次数为0,直接取总耗时
                        fields.put("avgRt", (double) callStat.get(2));
                    } else {
                        fields.put("avgRt", callStat.get(2) / (double) callStat.get(0));
                    }
                    fields.put("avgTps", (double) callStat.get(5) / PradarRtConstant.REDUCE_TRACE_SECONDS_INTERVAL);
                    fields.put("traceId", callStat.getTraceId());
                    fields.put("log_time", FormatUtils.toDateTimeSecondString(slotKey * 1000));

                    Map<String, Object> finalFields = new HashMap<>(fields);

                    influxDbSupport.write("pradar", "waterline_trace_metrics", influxdbTags, finalFields, slotKey * 1000);
                });
            } catch (Throwable e) {
                logger.error("write fail influxdb " + ExceptionUtils.getStackTrace(e));
            }
        }
    }
}


